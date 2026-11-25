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
    executable: File,
    private val workDir: File,
    private val options: Map<String, String>,
    private val onUpdate: (AnalysisUpdate) -> Unit,
    private val onStatus: (String) -> Unit,
    private val startTimeoutMs: Long = 30_000,
    private val silentMs: Long = SILENT_MS,
    /** Hash in MB unless the user set one (the app picks it from the phone's memory). */
    private val defaultHashMb: Int = 64,
    /**
     * Another build of the same engine to use if this one cannot run here
     * (the faster dotprod build on a CPU without those instructions: it
     * stops at once, "illegal instruction"). Used from then on.
     */
    private var alternative: File? = null,
) : AutoCloseable {
    /** The binary started: `executable`, or `alternative` once that was needed. */
    @Volatile var executable: File = executable
        private set
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
    /** "ucinewgame" is sent before the next search. */
    @Volatile private var pendingNewGame = false

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

    /**
     * A new game begins: the engine is told before the next analysis, so
     * what it remembers of the last game (its hash) does not steer this one.
     */
    fun newGame() { pendingNewGame = true }

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
        var e = ensureEngine() ?: return
        if (!e.stop() && !e.dead) {
            // No bestmove after "stop" (an engine stuck in its search): its late bestmove would end the next
            // search. A new process instead.
            DebugLog.w(TAG, "${e.name}: no bestmove within 3 s of stop; restarting it")
            engine = null
            e.kill()
            e = ensureEngine() ?: return
        }
        if (pendingNewGame) {
            pendingNewGame = false
            if (!e.newGame()) DebugLog.w(TAG, "${e.name}: no readyok after ucinewgame")
        }
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
    private fun watch(r: Request, e: UciEngine, first: Boolean = true) {
        if (closed || current !== r || engine !== e || e.dead || e.bestMoveLine != null) return
        if (e.pvSinceGo == 0 && e.searchOutputSinceGo == 0) {
            silent(r, e, "no search output ${silentMs} ms after go")
            return
        }
        if (first && e.pvSinceGo == 0) {
            DebugLog.w(TAG, "${e.name} searches but gives no lines after ${silentMs} ms; since go: ${e.sinceGoText()}")
        }
        // It searched; while it goes on, it is watched. A deep search can be quiet for long, so quiet alone is no
        // fault: then it is asked "isready", which an engine answers even in the middle of a search. No answer is
        // an engine that hung.
        if (System.currentTimeMillis() - e.lastLineAt >= silentMs && !e.isReady(READY_MS)) {
            if (current === r && engine === e && !e.dead && e.bestMoveLine == null) {
                silent(r, e, "no answer to isready during the search")
            }
            return
        }
        try {
            exec.schedule({ watch(r, e, first = false) }, silentMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
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
            DebugLog.i(TAG, "${e.name}: no legal move in ${r.fen} (${e.bestMoveLine})")
            if (r.id == ids.get()) onUpdate(AnalysisUpdate(r.id, r.fen, emptyList(), finished = true, noLegalMove = true))
            return
        }
        silent(r, e, "ended the search without a line (${e.bestMoveLine})")
    }

    /** Log what the engine said, restart it with safer settings, and analyse again. */
    private fun silent(r: Request, e: UciEngine, why: String) {
        DebugLog.w(TAG, "${e.name} $why; since go: ${e.sinceGoText()}")
        if (fallback >= MAX_FALLBACK) {
            // No safer settings left: still a new process (as configured last), never the hung one kept. The
            // limit of starts a minute keeps this from looping.
            onStatus("The engine gives no lines; please send Copy debug log")
            engine = null
            e.kill()
            ensureEngine()
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
        var starting: UciEngine? = null
        return try {
            val e = UciEngine(executable, workDir) { dead, code -> submit { onDeath(dead, code) } }
            starting = e
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
            // The other build only when this one cannot run here: it could not be started at all, or it was killed
            // by "illegal instruction". Not for a passing failure (low memory, a slow start): the faster build
            // would be given up for the whole session.
            starting?.kill()   // started but not ready: not left running
            val code = starting?.exitCode
            val cannotRun = ex is java.io.IOException || code == SIGILL_EXIT || code == SIGILL
            if (cannotRun && useAlternative("it did not start (${code?.let { "exit code $it" } ?: ex.javaClass.simpleName})"))
                return ensureEngine()
            onStatus("Could not start the engine: ${ex.message}")
            null
        }
    }

    /** Switches to the other build, once; false if there is none. */
    private fun useAlternative(why: String): Boolean {
        val alt = alternative ?: return false
        alternative = null
        DebugLog.w(TAG, "${executable.name}: $why; using ${alt.name} instead")
        executable = alt
        restartTimes.clear()
        return true
    }

    /** Sends the options; returns them as "name=value" for the log. */
    private fun applyOptions(e: UciEngine): List<String> {
        val cores = Runtime.getRuntime().availableProcessors()
        // Sensible defaults for a phone unless the user set them.
        val defaults = mapOf(
            "Threads" to maxOf(1, minOf(4, cores / 2)).toString(),
            "Hash" to defaultHashMb.toString(),
            // The win/draw/loss chances with the score (shown on the card), where the engine gives them.
            "UCI_ShowWDL" to "true",
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
        if (r != null) {
            // Only the positions of this session that crashed it; kept small.
            if (crashesByFen.size >= 64) crashesByFen.clear()
            crashesByFen[r.fen] = (crashesByFen[r.fen] ?: 0) + 1
        }
        // Killed by "illegal instruction" (SIGILL: 128 + 4, or 4 where the signal itself is given): a build this
        // CPU cannot run, not a crash to retry.
        if ((code == SIGILL_EXIT || code == SIGILL) && useAlternative("illegal instruction (exit code $code)")) {
            crashesByFen.clear()
        } else {
            onStatus("Engine stopped (code $code); restarting…")
        }
        // Resume the request that was running, unless a newer one is queued.
        if (r != null && r.id == ids.get()) run(r) else ensureEngine()
    }

    companion object {
        private const val TAG = "EngineHost"
        /** No search output this long after "go" = a silent engine. */
        const val SILENT_MS = 6_000L
        private const val MAX_FALLBACK = 3
        /** How long an engine may take to answer "isready" during a search. */
        private const val READY_MS = 5_000L
        private const val SIGILL = 4
        private const val SIGILL_EXIT = 128 + SIGILL
    }
}
