package io.github.chessoverlay

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log the user can copy from the app (the tools: Copy the debug log):
 * the last few hundred lines in memory and on disk, so a crash on someone's
 * phone can be diagnosed without adb. Thread-safe.
 *
 * A message that keeps coming is written once and counted ("again, 40
 * times"): an error at every board check (33 a second) would otherwise
 * fill the log in seconds and spend the board checks writing it.
 */
object DebugLog {
    private const val MAX_LINES = 600
    private const val MAX_FILE = 512 * 1024L
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    /** The last message written, without its time, and how often it came again since. */
    private var last: String? = null
    private var repeats = 0

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
        val key = "$level/$tag: $msg"
        if (key == last) {
            repeats++
            return
        }
        flushRepeats()
        write("${fmt.format(Date())} $key")
        last = key
    }

    private fun write(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    /** Writes how often the last message came again, when it did. */
    private fun flushRepeats() {
        if (repeats > 0) write("  (again, $repeats times)")
        repeats = 0
        last = null
    }

    /** Everything kept: the previous file, the current file (or memory). */
    @Synchronized
    fun dump(): String {
        flushRepeats()
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
        last = null; repeats = 0
        file?.let { it.delete(); File(it.parentFile, "debug.log.1").delete() }
    }
}
