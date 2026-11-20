package io.github.chessoverlay.engine

import io.github.chessoverlay.DebugLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One analysis result for the request with this `id`. */
data class AnalysisUpdate(val id: Long, val fen: String, val lines: List<PvLine>, val finished: Boolean)

/**
 * Keeps one engine running and analyses on request. All engine calls happen
 * on one thread, so requests never interleave. If the engine dies (crash,
 * killed by Android, or a write fails with EPIPE) it is restarted, its
 * options are sent again and the current analysis resumes. A position that
 * kills the engine twice is skipped. Every event goes to DebugLog.
 */
class EngineHost(
    private val executable: File,
    private val workDir: File,
    private val options: Map<String, String>,
    private val onUpdate: (AnalysisUpdate) -> Unit,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-host").apply { isDaemon = true } }
    private val ids = AtomicLong(0)
    @Volatile private var engine: UciEngine? = null
    @Volatile private var closed = false
    private var current: Request? = null
    private val crashesByFen = HashMap<String, Int>()
    private val restartTimes = ArrayDeque<Long>()

    @Volatile var engineName: String = executable.name
        private set

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
            if (r.id == ids.get()) onUpdate(AnalysisUpdate(r.id, r.fen, lines, finished))
        }
        if (!ok) DebugLog.w(TAG, "go failed (engine dead=${e.dead}) for ${r.fen}")
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
            e.start()
            engineName = e.name
            applyOptions(e)
            if (!e.isReady()) throw IllegalStateException("no readyok after setting options")
            DebugLog.i(TAG, "started ${e.name} (${executable.name}); ${e.options.size} options")
            engine = e
            e
        } catch (ex: Exception) {
            DebugLog.e(TAG, "could not start ${executable.name}", ex)
            onStatus("Could not start the engine: ${ex.message}")
            null
        }
    }

    private fun applyOptions(e: UciEngine) {
        val cores = Runtime.getRuntime().availableProcessors()
        // Sensible defaults for a phone unless the user set them.
        val defaults = mapOf(
            "Threads" to maxOf(1, minOf(4, cores / 2)).toString(),
            "Hash" to "64",
        )
        for ((k, v) in defaults) if (options.keys.none { it.equals(k, true) } && e.hasOption(k)) e.setOption(k, v)
        for ((k, v) in options) e.setOption(k, v)
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
    }
}
