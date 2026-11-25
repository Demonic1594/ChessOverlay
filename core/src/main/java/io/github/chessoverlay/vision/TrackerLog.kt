package io.github.chessoverlay.vision

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the game tracker decided, read by read: the last [MAX] decisions
 * (a move followed, a position taken at once or read again, a correction,
 * the board lost), each with the position it led to. Copied with the debug
 * log, so a report of "it froze on this move" shows what the tracker made
 * of every read before it, without the rest of the log around it.
 * Thread-safe; kept in memory for the app's life (it outlives the overlay).
 */
object TrackerLog {
    const val MAX = 200
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var last: String? = null
    private var repeats = 0

    @Synchronized
    fun add(event: String, fen: String? = null) {
        val text = if (fen != null) "$event | $fen" else event
        if (text == last) { repeats++; return }
        flushRepeats()
        last = text
        entries.addLast("${fmt.format(Date())} $text")
        while (entries.size > MAX) entries.removeFirst()
    }

    private fun flushRepeats() {
        if (repeats > 0) {
            entries.addLast("  (again, $repeats times)")
            while (entries.size > MAX) entries.removeFirst()
        }
        repeats = 0
    }

    @Synchronized
    fun dump(): String {
        flushRepeats()
        last = null
        return entries.joinToString("\n")
    }

    @Synchronized
    fun clear() { entries.clear(); last = null; repeats = 0 }
}
