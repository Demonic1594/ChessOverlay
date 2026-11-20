package io.github.chessoverlay.engine

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A UCI engine running as a child process. Thread-safe for one analysis at
 * a time: analyse() stops any running search first.
 */
class UciEngine(private val executable: File, private val workDir: File) {

    var name: String = executable.name
        private set
    val options = mutableListOf<UciOption>()

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val lock = Object()

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
        if (!uciOk!!.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            stopProcess()
            throw IllegalStateException("${executable.name} did not answer \"uci\"")
        }
    }

    val isAlive: Boolean get() = process?.isAlive == true

    fun send(cmd: String) {
        synchronized(lock) {
            val w = writer ?: return
            w.write(cmd)
            w.write("\n")
            w.flush()
        }
    }

    fun setOption(name: String, value: String) {
        val opt = options.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        if (opt.type == "button") send("setoption name ${opt.name}")
        else send("setoption name ${opt.name} value $value")
    }

    fun isReady(timeoutMs: Long = 5_000): Boolean {
        val l = CountDownLatch(1)
        readyOk = l
        send("isready")
        return l.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Analyse `fen`. `onUpdate(lines, finished)` is called from the reader
     * thread with the current lines sorted by multipv. `movetimeMs` 0 =
     * infinite (until stop()); `depth` > 0 limits the depth instead.
     */
    fun analyse(
        fen: String,
        multipv: Int,
        movetimeMs: Long,
        depth: Int = 0,
        onUpdate: (List<PvLine>, Boolean) -> Unit,
    ) {
        stop()
        synchronized(lines) { lines.clear() }
        if (options.any { it.name.equals("MultiPV", true) }) setOption("MultiPV", multipv.toString())
        setOption("UCI_AnalyseMode", "true")
        isReady()
        listener = onUpdate
        bestMove = CountDownLatch(1)
        send("position fen $fen")
        send(
            when {
                depth > 0 -> "go depth $depth"
                movetimeMs > 0 -> "go movetime $movetimeMs"
                else -> "go infinite"
            }
        )
    }

    /** Stop the current search and wait for its bestmove. */
    fun stop(timeoutMs: Long = 3_000) {
        val l = bestMove ?: return
        if (l.count == 0L) return
        send("stop")
        l.await(timeoutMs, TimeUnit.MILLISECONDS)
        listener = null
    }

    fun quit() {
        try {
            stop(1_000)
            send("quit")
            process?.waitFor(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        stopProcess()
    }

    private fun stopProcess() {
        process?.destroy()
        process = null
        writer = null
    }

    private fun readLoop(p: Process) {
        val r = BufferedReader(InputStreamReader(p.inputStream))
        while (true) {
            val line = try { r.readLine() } catch (_: Exception) { null } ?: break
            when {
                line.startsWith("id name ") -> name = line.removePrefix("id name ").trim()
                line.startsWith("option ") -> UciOption.parse(line)?.let { options.add(it) }
                line == "uciok" -> uciOk?.countDown()
                line == "readyok" -> readyOk?.countDown()
                line.startsWith("info ") -> PvLine.parse(line)?.let { pv ->
                    val snapshot = synchronized(lines) {
                        // A new depth for line 1 makes older deeper lines stale
                        // only if they are shallower; keep the latest per multipv.
                        lines[pv.multipv] = pv
                        lines.values.toList()
                    }
                    listener?.invoke(snapshot, false)
                }
                line.startsWith("bestmove") -> {
                    val snapshot = synchronized(lines) { lines.values.toList() }
                    listener?.invoke(snapshot, true)
                    bestMove?.countDown()
                }
            }
        }
        // Process ended: release anyone waiting.
        uciOk?.countDown(); readyOk?.countDown(); bestMove?.countDown()
    }
}
