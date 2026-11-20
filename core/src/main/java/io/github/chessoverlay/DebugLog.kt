package io.github.chessoverlay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log the user can copy from the app (Settings -> Copy debug log):
 * the last few hundred lines in memory and on disk, so a crash on someone's
 * phone can be diagnosed without adb. Thread-safe.
 */
object DebugLog {
    private const val MAX_LINES = 600
    private const val MAX_FILE = 512 * 1024L
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Log to `dir/debug.log` too (call once, e.g. with filesDir). */
    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "debug.log")
        if (f.length() > MAX_FILE) {
            File(dir, "debug.log.1").delete()
            f.renameTo(File(dir, "debug.log.1"))
        }
        file = File(dir, "debug.log")
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)

    fun w(tag: String, msg: String) = add("W", tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("E", tag, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    /** Everything kept: the previous file, the current file (or memory). */
    @Synchronized
    fun dump(): String {
        val f = file ?: return lines.joinToString("\n")
        val old = File(f.parentFile, "debug.log.1")
        val sb = StringBuilder()
        if (old.exists()) sb.append(old.readText())
        if (f.exists()) sb.append(f.readText())
        return if (sb.isEmpty()) lines.joinToString("\n") else sb.toString().takeLast(200_000)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        file?.let { it.delete(); File(it.parentFile, "debug.log.1").delete() }
    }
}
