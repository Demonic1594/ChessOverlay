package io.github.chessoverlay.engine

import io.github.chessoverlay.DebugLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Test the engine": starts the engine as the overlay would (same options,
 * same watchdog), analyses one position for a few seconds and says what
 * happened, in words for the user and in the debug log.
 */
object EngineCheck {
    const val FEN = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"

    data class Report(val ok: Boolean, val text: String)

    fun run(executable: File, workDir: File, options: Map<String, String>, seconds: Int = 5,
            giveUpMs: Long = 45_000, alternative: File? = null): Report {
        val updates = CopyOnWriteArrayList<Pair<Long, AnalysisUpdate>>()
        val status = CopyOnWriteArrayList<String>()
        val t0 = System.currentTimeMillis()
        DebugLog.i(TAG, "testing ${executable.name} with ${options.size} saved options")
        val host = EngineHost(executable, workDir, options,
            onUpdate = { updates.add(System.currentTimeMillis() to it) },
            onStatus = { status.add(it) }, alternative = alternative)
        var name: String
        var fallback: Int
        var sent: List<String>
        var used: File
        host.use {
            val id = host.analyse(FEN, 3, 0)
            while (true) {
                val now = System.currentTimeMillis()
                val first = updates.firstOrNull { it.second.id == id && it.second.lines.isNotEmpty() }
                if (first != null && now - first.first >= seconds * 1000L) break
                if (first == null && now - t0 > giveUpMs) break
                if (status.any { it.startsWith("The engine") || it.startsWith("Could not") }) break
                Thread.sleep(100)
            }
            name = host.engineName
            fallback = host.fallback
            sent = host.sentOptions
            used = host.executable
        }
        val first = updates.firstOrNull { it.second.lines.isNotEmpty() }
        val last = updates.lastOrNull { it.second.lines.isNotEmpty() }?.second
        val sb = StringBuilder()
        sb.append(name).append('\n')
        if (alternative != null && used != executable) sb.append("The faster build cannot run on this phone; ${used.name} was used.\n")
        val ok = first != null && last != null
        if (ok) {
            val top = last!!.lines[0]
            sb.append("First lines ${secs(first!!.first - t0)} after starting it.\n")
            sb.append("Depth ${top.depth} after ${secs(updates.last().first - first.first)} more, ${last.lines.size} lines.\n")
            sb.append("Best: ${top.pv.take(6).joinToString(" ")} (${top.scoreText(true)})")
        } else {
            sb.append("No lines within ${secs(System.currentTimeMillis() - t0)}.")
        }
        if (sent.isNotEmpty()) sb.append("\nOptions: ").append(sent.joinToString(", "))
        if (fallback > 0) sb.append("\nIt was silent at first and was restarted (now: ").append(
            when (fallback) { 1 -> "same settings"; 2 -> "one thread"; else -> "one thread, no smother prover" }).append(").")
        if (status.isNotEmpty()) sb.append("\nMessages: ").append(status.distinct().joinToString("; "))
        DebugLog.i(TAG, "result: " + sb.toString().replace('\n', ' '))
        return Report(ok, sb.toString())
    }

    private fun secs(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

    private const val TAG = "EngineCheck"
}
