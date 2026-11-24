package io.github.chessoverlay.engine

import io.github.chessoverlay.DebugLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A UCI engine running as a child process.
 *
 * Writes never throw: if the process has died, send() returns false and the
 * engine is marked dead (a write to a dead engine is the "EPIPE" error).
 * When the process ends, `onExit` is called once from the reader thread with
 * the exit code. Use one search at a time (EngineHost serialises calls).
 */
class UciEngine(
    private val executable: File,
    private val workDir: File,
    private val onExit: (UciEngine, Int?) -> Unit = { _, _ -> },
) {
    var name: String = executable.name
        private set
    val options = java.util.concurrent.CopyOnWriteArrayList<UciOption>()

    @Volatile var dead = false
        private set
    @Volatile var exitCode: Int? = null
        private set

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val writeLock = Object()
    private val tail = ArrayDeque<String>()

    @Volatile private var uciOk: CountDownLatch? = null
    @Volatile private var readyOk: CountDownLatch? = null
    @Volatile private var bestMove: CountDownLatch? = null
    @Volatile private var listener: ((List<PvLine>, Boolean) -> Unit)? = null
    @Volatile private var collector = PvCollector(1)
    /** Counts lines added, so a flush knows whether more came after it was planned. */
    @Volatile private var lineSeq = 0L

    // What the engine said since the last "go", for the watchdog and the log.
    @Volatile var goAt = 0L
        private set
    /** Lines with a principal variation since the last "go". */
    @Volatile var pvSinceGo = 0
        private set
    /** Search output (any "info" with a depth, nodes or pv) since the last "go". */
    @Volatile var searchOutputSinceGo = 0
        private set
    @Volatile var bestMoveLine: String? = null
        private set
    private val sinceGo = ArrayDeque<String>()
    @Volatile private var listenerFailed = false

    /** Start the process and run the "uci" handshake. Throws on failure. */
    fun start(timeoutMs: Long = 10_000) {
        val p = ProcessBuilder(executable.absolutePath)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = p
        writer = OutputStreamWriter(p.outputStream)
        uciOk = CountDownLatch(1)
        Thread({ readLoop(p) }, "uci-reader").apply { isDaemon = true }.start()
        send("uci")
        if (!uciOk!!.await(timeoutMs, TimeUnit.MILLISECONDS) || dead) {
            kill()
            throw IllegalStateException("${executable.name} did not answer \"uci\"" +
                (exitCode?.let { " (exit code $it)" } ?: "") + tailText())
        }
    }

    /** False if the engine is dead (the command was not delivered). */
    fun send(cmd: String): Boolean {
        synchronized(writeLock) {
            val w = writer ?: return false
            if (dead) return false
            return try {
                w.write(cmd)
                w.write("\n")
                w.flush()
                true
            } catch (_: java.io.IOException) {
                dead = true
                false
            }
        }
    }

    fun setOption(name: String, value: String): Boolean {
        val opt = options.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        return if (opt.type == "button") send("setoption name ${opt.name}")
        else send("setoption name ${opt.name} value $value")
    }

    fun hasOption(name: String) = options.any { it.name.equals(name, ignoreCase = true) }

    fun isReady(timeoutMs: Long = 5_000): Boolean {
        val l = CountDownLatch(1)
        readyOk = l
        if (!send("isready")) return false
        return l.await(timeoutMs, TimeUnit.MILLISECONDS) && !dead
    }

    /**
     * Start searching `fen`. `onUpdate(lines, finished)` is called from the
     * reader thread with each whole iteration (every line at one depth, see
     * PvCollector), sorted by multipv. `movetimeMs`
     * 0 = until stop(); `depth` > 0 limits the depth instead.
     */
    fun go(fen: String, multipv: Int, movetimeMs: Long, depth: Int = 0,
           onUpdate: (List<PvLine>, Boolean) -> Unit): Boolean {
        val multi = hasOption("MultiPV")
        collector = PvCollector(if (multi) multipv else 1)
        if (multi) setOption("MultiPV", multipv.toString())
        if (hasOption("UCI_AnalyseMode")) setOption("UCI_AnalyseMode", "true")
        if (!isReady()) {
            DebugLog.w(TAG, "$name: no readyok within 5 s before go (dead=$dead)${tailText()}")
            return false
        }
        bestMove = CountDownLatch(1)
        listener = onUpdate
        listenerFailed = false
        synchronized(sinceGo) { sinceGo.clear() }
        pvSinceGo = 0
        searchOutputSinceGo = 0
        bestMoveLine = null
        goAt = System.currentTimeMillis()
        if (!send("position fen $fen")) return false
        return send(
            when {
                depth > 0 -> "go depth $depth"
                movetimeMs > 0 -> "go movetime $movetimeMs"
                else -> "go infinite"
            }
        )
    }

    /** What the engine printed since the last "go" (at most the first and last few lines). */
    fun sinceGoText(): String = synchronized(sinceGo) {
        if (sinceGo.isEmpty()) "nothing" else sinceGo.joinToString(" | ")
    }

    /** Stop the current search and wait for its bestmove. False on timeout. */
    fun stop(timeoutMs: Long = 3_000): Boolean {
        val l = bestMove ?: return true
        if (l.count == 0L) { listener = null; return true }
        if (!send("stop")) { listener = null; return false }
        val ok = l.await(timeoutMs, TimeUnit.MILLISECONDS)
        listener = null
        return ok && !dead
    }

    fun quit() {
        try {
            stop(1_000)
            send("quit")
            process?.waitFor(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        kill()
    }

    fun kill() {
        process?.destroy()
        dead = true
    }

    /** The engine's last output lines (for crash reports). */
    fun outputTail(): List<String> = synchronized(tail) { tail.toList() }

    private fun tailText(): String {
        val t = outputTail()
        return if (t.isEmpty()) "" else "; last output: " + t.takeLast(5).joinToString(" | ")
    }

    private fun notify(snapshot: List<PvLine>, finished: Boolean) {
        try {
            listener?.invoke(snapshot, finished)
        } catch (t: Throwable) {
            // Never let it escape the reader thread, but say so (once per search).
            if (!listenerFailed) {
                listenerFailed = true
                DebugLog.e(TAG, "handling engine lines failed at ${t.stackTrace.firstOrNull()}", t)
            }
        }
    }

    /**
     * A set of lines still incomplete FLUSH_MS after its last line is all
     * this iteration gives (fewer legal moves than lines asked, or a capped
     * depth): pass it on rather than wait for lines that never come.
     */
    private fun flushLater(c: PvCollector, seq: Long) {
        try {
            flusher.schedule({
                if (seq == lineSeq && c === collector) {
                    val set = synchronized(c) { c.flush() }
                    if (set != null) notify(set, false)
                }
            }, FLUSH_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun noteSinceGo(line: String) {
        if (goAt == 0L) return
        val t = System.currentTimeMillis() - goAt
        synchronized(sinceGo) {
            // Keep the first lines (how the search began) and the latest ones.
            if (sinceGo.size >= SINCE_GO_LINES) sinceGo.removeAt(SINCE_GO_LINES / 2)
            sinceGo.addLast("+${t}ms ${line.take(140)}")
        }
    }

    private fun readLoop(p: Process) {
        val r = BufferedReader(InputStreamReader(p.inputStream))
        while (true) {
            val line = try { r.readLine() } catch (_: Exception) { null } ?: break
            if (!line.startsWith("info ") || line.startsWith("info string")) {
                synchronized(tail) { tail.addLast(line); while (tail.size > 40) tail.removeFirst() }
            }
            when {
                line.startsWith("id name ") -> name = line.removePrefix("id name ").trim()
                line.startsWith("option ") -> UciOption.parse(line)?.let { options.add(it) }
                line == "uciok" -> uciOk?.countDown()
                line == "readyok" -> readyOk?.countDown()
                goAt != 0L && !line.startsWith("info ") && !line.startsWith("bestmove") -> noteSinceGo(line)
                line.startsWith("info ") -> {
                    val searching = !line.startsWith("info string") &&
                        (" depth " in line || " nodes " in line || " pv " in line)
                    if (searching) searchOutputSinceGo++
                    if (searching && searchOutputSinceGo <= 3 || !searching) noteSinceGo(line)
                    PvLine.parse(line)?.let { pv ->
                        if (pv.pv.isNotEmpty()) {
                            pvSinceGo++
                            val c = collector
                            val seq = ++lineSeq
                            var waiting = false
                            val set = synchronized(c) { c.add(pv).also { waiting = c.waiting } }
                            if (set != null) notify(set, false)
                            if (waiting) flushLater(c, seq)
                        }
                    }
                }
                line.startsWith("bestmove") -> {
                    noteSinceGo(line)
                    bestMoveLine = line
                    val c = collector
                    lineSeq++
                    notify(synchronized(c) { c.finish() }, true)
                    bestMove?.countDown()
                }
            }
        }
        // The process ended (crash, kill, or quit).
        dead = true
        exitCode = try { p.waitFor(); p.exitValue() } catch (_: Exception) { null }
        uciOk?.countDown(); readyOk?.countDown(); bestMove?.countDown()
        // Never let a listener's exception escape this thread: on Android an
        // uncaught exception here would take the whole app down.
        try { onExit(this, exitCode) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "UciEngine"
        private const val SINCE_GO_LINES = 16
        /** Quiet this long after an incomplete set of lines: the set is complete. */
        const val FLUSH_MS = 120L
        private val flusher by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "uci-flush").apply { isDaemon = true }
            }
        }
    }
}
