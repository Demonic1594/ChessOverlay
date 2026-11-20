package io.github.chessoverlay.engine

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
    private val lines = sortedMapOf<Int, PvLine>()

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
     * reader thread with the lines so far, sorted by multipv. `movetimeMs`
     * 0 = until stop(); `depth` > 0 limits the depth instead.
     */
    fun go(fen: String, multipv: Int, movetimeMs: Long, depth: Int = 0,
           onUpdate: (List<PvLine>, Boolean) -> Unit): Boolean {
        synchronized(lines) { lines.clear() }
        if (hasOption("MultiPV")) setOption("MultiPV", multipv.toString())
        if (hasOption("UCI_AnalyseMode")) setOption("UCI_AnalyseMode", "true")
        if (!isReady()) return false
        bestMove = CountDownLatch(1)
        listener = onUpdate
        if (!send("position fen $fen")) return false
        return send(
            when {
                depth > 0 -> "go depth $depth"
                movetimeMs > 0 -> "go movetime $movetimeMs"
                else -> "go infinite"
            }
        )
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
        try { listener?.invoke(snapshot, finished) } catch (_: Throwable) {}
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
                line.startsWith("info ") -> PvLine.parse(line)?.let { pv ->
                    val snapshot = synchronized(lines) {
                        lines[pv.multipv] = pv
                        lines.values.toList()
                    }
                    notify(snapshot, false)
                }
                line.startsWith("bestmove") -> {
                    val snapshot = synchronized(lines) { lines.values.toList() }
                    notify(snapshot, true)
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
}
