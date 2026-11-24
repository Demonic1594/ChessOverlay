package io.github.chessoverlay.engine

import io.github.chessoverlay.DebugLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One analysis result for the request with this `id`. `noLegalMove`: the
 * engine ended the search with "bestmove (none)": the position has no legal
 * move (checkmate or stalemate), which the overlay then says.
 */
data class AnalysisUpdate(
    val id: Long,
    val fen: String,
    val lines: List<PvLine>,
    val finished: Boolean,
    val noLegalMove: Boolean = false,
)

/**
 * Keeps one engine running and analyses on request. All engine calls happen
 * on one thread, so requests never interleave. If the engine dies (crash,
 * killed by Android, or a write fails with EPIPE) it is restarted, its
 * options are sent again and the current analysis resumes. A position that
 * kills the engine twice is skipped. Every event goes to DebugLog.
 *
 * An engine that is alive but says nothing after "go" (no search output
 * for SILENT_MS), or ends an analysis by itself without a single line, is
 * logged with everything it printed since "go" and restarted, each time
 * with safer settings (see [fallback]); the overlay shows what happens.
 */
class EngineHost(
    private val executable: File,
    private val workDir: File,
    private val options: Map<String, String>,
    private val onUpdate: (AnalysisUpdate) -> Unit,
    private val onStatus: (String) -> Unit,
    private val startTimeoutMs: Long = 30_000,
    private val silentMs: Long = SILENT_MS,
) : AutoCloseable {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine-host").apply { isDaemon = true } }
    private val ids = AtomicLong(0)
    @Volatile private var engine: UciEngine? = null
    @Volatile private var closed = false
    private var current: Request? = null
    private val crashesByFen = HashMap<String, Int>()
    private val restartTimes = ArrayDeque<Long>()

    @Volatile var engineName: String = executable.name
        private set

    /**
     * How far the settings were stepped down after the engine went silent:
     * 0 as configured, 1 restarted, 2 one thread, 3 one thread and no
     * smothered-mate prover. Kept while this host lives.
     */
    @Volatile var fallback = 0
        private set
    @Volatile private var startedAt = 0L
    /** The options sent at the last start, as "name=value". */
    @Volatile var sentOptions: List<String> = emptyList()
        private set
    @Volatile private var firstLinesPending = false

    private data class Request(val id: Long, val fen: String, val multipv: Int, val movetimeMs: Long)

    /** Start the engine now (otherwise the first analyse() starts it). */
    fun start() = submit { ensureEngine() }

    /**
     * Analyse `fen`; returns the request id that updates will carry. Any
     * previous analysis is stopped first. `movetimeMs` 0 = until the next
     * request or stop().
     */
    fun analyse(fen: String, multipv: Int, movetimeMs: Long): Long {
        val r = Request(ids.incrementAndGet(), fen, multipv, movetimeMs)
        submit { run(r) }
        return r.id
    }

    fun stop() = submit {
        current = null
        engine?.stop()
    }

    override fun close() {
        closed = true
        submit {
            engine?.quit()
            engine = null
        }
        exec.shutdown()
        exec.awaitTermination(3, TimeUnit.SECONDS)
    }

    /** Run on the engine thread; after close() the task is dropped instead
     *  of throwing (an exception there would crash the app from the engine's
     *  reader thread when the engine exits during shutdown). */
    private fun submit(task: () -> Unit) {
        try {
            exec.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun run(r: Request) {
        if (closed || r.id != ids.get()) return  // a newer request came in meanwhile
        if ((crashesByFen[r.fen] ?: 0) >= 2) {
            onStatus("The engine crashed twice on this position; skipped")
            return
        }
        val e = ensureEngine() ?: return
        e.stop()
        current = r
        val ok = e.go(r.fen, r.multipv, r.movetimeMs) { lines, finished ->
            if (lines.isNotEmpty() && firstLinesPending) {
                firstLinesPending = false
                DebugLog.i(TAG, "first lines ${System.currentTimeMillis() - e.goAt} ms after go, " +
                    "${System.currentTimeMillis() - startedAt} ms after the start (depth ${lines[0].depth})")
            }
            if (finished && lines.isEmpty()) submit { endedWithoutLines(r, e) }
            if (r.id == ids.get()) onUpdate(AnalysisUpdate(r.id, r.fen, lines, finished))
        }
        if (!ok) {
            DebugLog.w(TAG, "go failed (engine dead=${e.dead}) for ${r.fen}")
            if (!e.dead) silent(r, e, "no answer to isready before go")
            return
        }
        try {
            exec.schedule({ watch(r, e) }, silentMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** `silentMs` after "go": has the engine started searching? */
    private fun watch(r: Request, e: UciEngine) {
        if (closed || current !== r || engine !== e || e.dead || e.bestMoveLine != null) return
        if (e.pvSinceGo > 0) return
        if (e.searchOutputSinceGo > 0) {
            DebugLog.w(TAG, "${e.name} searches but gives no lines after ${silentMs} ms; since go: ${e.sinceGoText()}")
            return
        }
        silent(r, e, "no search output ${silentMs} ms after go")
    }

    /** The engine finished a search without any line. */
    private fun endedWithoutLines(r: Request, e: UciEngine) {
        if (closed || current !== r || engine !== e) return  // stopped by us for a newer request
        if (r.movetimeMs > 0 && e.pvSinceGo > 0) return
        val bm = e.bestMoveLine?.split(" ")?.getOrNull(1)
        if (bm == "(none)" || bm == "0000") {
            // Checkmate or stalemate: there is no line to give. The overlay is
            // told, so it does not wait for lines (its own rule check may have
            // allowed a move that a picture cannot show to be impossible).
            DebugLog.i(TAG, "${e.name}: no legal move in $r.fen (${e.bestMoveLine})")
            if (r.id == ids.get()) onUpdate(AnalysisUpdate(r.id, r.fen, emptyList(), finished = true, noLegalMove = true))
            return
        }
        silent(r, e, "ended the search without a line (${e.bestMoveLine})")
    }

    /** Log what the engine said, restart it with safer settings, and analyse again. */
    private fun silent(r: Request, e: UciEngine, why: String) {
        DebugLog.w(TAG, "${e.name} $why; since go: ${e.sinceGoText()}")
        if (fallback >= MAX_FALLBACK) {
            onStatus("The engine gives no lines; please send Copy debug log")
            return
        }
        fallback++
        DebugLog.w(TAG, "restarting ${e.name}: ${fallbackText()}")
        onStatus("Engine silent; restarting it (${fallbackText()})…")
        engine = null
        e.kill()
        run(r)
    }

    private fun fallbackText() = when (fallback) {
        0 -> "as configured"
        1 -> "same settings"
        2 -> "one thread"
        else -> "one thread, no smother prover"
    }

    /** The running engine, starting or restarting it if needed. */
    private fun ensureEngine(): UciEngine? {
        engine?.let { if (!it.dead) return it }
        if (closed) return null
        // Back off if the engine keeps dying: at most 5 starts a minute.
        val now = System.currentTimeMillis()
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > 60_000) restartTimes.removeFirst()
        if (restartTimes.size >= 5) {
            onStatus("The engine keeps stopping; see Copy debug log in the app")
            DebugLog.e(TAG, "giving up: 5 engine starts within a minute")
            return null
        }
        restartTimes.addLast(now)
        return try {
            val e = UciEngine(executable, workDir) { dead, code -> submit { onDeath(dead, code) } }
            e.start(startTimeoutMs)
            engineName = e.name
            val sent = applyOptions(e)
            sentOptions = sent
            if (!e.isReady()) throw IllegalStateException("no readyok after setting options")
            DebugLog.i(TAG, "started ${e.name} (${executable.name}) in ${System.currentTimeMillis() - now} ms; " +
                "${e.options.size} options; set ${sent.joinToString(", ")}" +
                (if (fallback > 0) "; fallback: ${fallbackText()}" else ""))
            startedAt = now
            firstLinesPending = true
            engine = e
            e
        } catch (ex: Exception) {
            DebugLog.e(TAG, "could not start ${executable.name}", ex)
            onStatus("Could not start the engine: ${ex.message}")
            null
        }
    }

    /** Sends the options; returns them as "name=value" for the log. */
    private fun applyOptions(e: UciEngine): List<String> {
        val cores = Runtime.getRuntime().availableProcessors()
        // Sensible defaults for a phone unless the user set them.
        val defaults = mapOf(
            "Threads" to maxOf(1, minOf(4, cores / 2)).toString(),
            "Hash" to "64",
        )
        val all = LinkedHashMap<String, String>()
        for ((k, v) in defaults) if (options.keys.none { it.equals(k, true) }) all[k] = v
        all.putAll(options)
        if (fallback >= 2) { all.keys.removeAll { it.equals("Threads", true) }; all["Threads"] = "1" }
        if (fallback >= 3) { all.keys.removeAll { it.equals("SmotherMate", true) }; all["SmotherMate"] = "false" }
        val sent = ArrayList<String>()
        for ((k, v) in all) if (e.hasOption(k) && e.setOption(k, v)) sent.add("$k=$v")
        return sent
    }

    private fun onDeath(dead: UciEngine, code: Int?) {
        if (dead !== engine || closed) return  // an engine we already replaced, or shutting down
        engine = null
        val r = current
        DebugLog.e(TAG, "engine exited (code $code) while analysing ${r?.fen ?: "nothing"}; " +
            "last output: ${dead.outputTail().takeLast(8).joinToString(" | ")}")
        if (r != null) crashesByFen[r.fen] = (crashesByFen[r.fen] ?: 0) + 1
        onStatus("Engine stopped (code $code); restarting…")
        // Resume the request that was running, unless a newer one is queued.
        if (r != null && r.id == ids.get()) run(r) else ensureEngine()
    }

    companion object {
        private const val TAG = "EngineHost"
        /** No search output this long after "go" = a silent engine. */
        const val SILENT_MS = 6_000L
        private const val MAX_FALLBACK = 3
    }
}
