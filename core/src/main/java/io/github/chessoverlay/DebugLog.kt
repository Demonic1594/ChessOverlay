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
    /** The open log file (opened once, not for every line) and its size. */
    private var out: java.io.Writer? = null
    private var size = 0L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    /** The last message written, without its time, and how often it came again since. */
    private var last: String? = null
    private var repeats = 0

    /** Log to `dir/debug.log` too (call once, e.g. with filesDir). */
    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        file = File(dir, "debug.log")
        if (file!!.length() > MAX_FILE) rotate()
    }

    /** Tests: log to `dir` from now on, whatever init() was given. */
    @Synchronized
    internal fun useDir(dir: File) {
        try { out?.close() } catch (_: Exception) {}
        out = null
        file = null
        init(dir)
    }

    /** The log file, opened at the first line written to it. */
    private fun writer(): java.io.Writer? {
        out?.let { return it }
        val f = file ?: return null
        size = f.length()
        out = try { java.io.BufferedWriter(java.io.FileWriter(f, true), 8192) } catch (_: Exception) { null }
        return out
    }

    /** The current file becomes debug.log.1 (the one before goes), and a new one begins. */
    private fun rotate() {
        val f = file ?: return
        try { out?.close() } catch (_: Exception) {}
        out = null
        val old = File(f.parentFile, "debug.log.1")
        old.delete()
        f.renameTo(old)
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
        val w = writer() ?: return
        try {
            w.write(line)
            w.write("\n")
            // Flushed at once: after a crash the log must hold the lines before it.
            w.flush()
            size += line.length + 1
            if (size > MAX_FILE) rotate()
        } catch (_: Exception) {
            try { w.close() } catch (_: Exception) {}
            out = null
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
        file?.let {
            try { out?.close() } catch (_: Exception) {}
            out = null
            it.delete(); File(it.parentFile, "debug.log.1").delete()
        }
    }
}
