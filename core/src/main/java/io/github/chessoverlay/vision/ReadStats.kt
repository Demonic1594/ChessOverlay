package io.github.chessoverlay.vision

/**
 * How reading went over an overlay session, for one line in the debug log:
 * how many moves were seen at once from the changed squares, how many of
 * those the recognizer confirmed or corrected, how long its reads took, and
 * how often a read failed. One log then tells how a whole game went.
 */
class ReadStats {
    private var positions = 0
    private var seenAtOnce = 0
    private var confirmed = 0
    private var corrected = 0
    private var misreads = 0
    private var noBoard = 0
    private var sideFromHighlight = 0
    private val readMs = ArrayList<Long>()

    @Synchronized fun position() { positions++ }
    @Synchronized fun seenAtOnce() { seenAtOnce++ }
    @Synchronized fun confirmed() { confirmed++ }
    @Synchronized fun corrected() { corrected++ }
    @Synchronized fun misread() { misreads++ }
    @Synchronized fun noBoard() { noBoard++ }
    @Synchronized fun sideFromHighlight() { sideFromHighlight++ }
    @Synchronized fun read(ms: Long) {
        readMs += ms
        if (readMs.size > MAX_READS) readMs.removeAt(0)
    }

    /** Positions analysed so far. */
    @Synchronized fun positions(): Int = positions

    @Synchronized fun summary(): String {
        val sb = StringBuilder("$positions positions analysed")
        if (seenAtOnce > 0) {
            sb.append("; $seenAtOnce moves seen at once ($confirmed confirmed, $corrected corrected")
            val open = seenAtOnce - confirmed - corrected
            if (open > 0) sb.append(", $open unchecked")
            sb.append(")")
        }
        if (readMs.isNotEmpty()) {
            val s = readMs.sorted()
            sb.append("; ${s.size} reads, median ${s[s.size / 2]} ms, 90% within ${s[(s.size * 9 / 10).coerceAtMost(s.size - 1)]} ms, slowest ${s.last()} ms")
        }
        if (sideFromHighlight > 0) sb.append("; side to move from the highlight $sideFromHighlight times")
        if (misreads > 0 || noBoard > 0) sb.append("; $misreads misread, $noBoard without a board")
        return sb.toString()
    }

    companion object {
        private const val MAX_READS = 2000
        /** A summary line every this many positions, and when the overlay closes. */
        const val EVERY = 25
    }
}
