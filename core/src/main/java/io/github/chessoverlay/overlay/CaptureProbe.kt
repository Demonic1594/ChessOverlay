package io.github.chessoverlay.overlay

/**
 * "Are our windows in the screen capture?", found out without waiting.
 *
 * A small square of a colour no board shows is put up on our board layer,
 * and the frames that come after it are looked at as the auto mode's checks
 * go by: a frame that shows it means the whole screen is shared (reads must
 * take our drawing off first); frames well after it that do not show it
 * mean one app is shared (Android 14 and later), and reads can take the
 * frame as it is.
 *
 * The answer can take a while: with one app shared, a frame comes only
 * when that app changes. The square stays up until then, at most [waitMs],
 * and nothing else waits for it. No such frame: not known, and the careful
 * way stays. It is tried again later, at most [tries] times.
 */
class CaptureProbe(
    private val waitMs: Long = WAIT_MS,
    private val sureMs: Long = SURE_MS,
    private val tries: Int = TRIES,
) {
    enum class Answer { IN_CAPTURE, NOT_IN_CAPTURE, NOT_KNOWN }

    /** When the square was put up (0 while it is only asked for), and when it was asked for; 0 when no probe runs. */
    private var drawnAt = 0L
    private var startedAt = 0L
    private var started = 0

    val running: Boolean get() = startedAt != 0L

    /** Whether another probe may start. */
    val mayStart: Boolean get() = !running && started < tries

    /** A probe starts now; the square is to be put up. */
    fun start(now: Long) {
        startedAt = now
        drawnAt = 0L
        started++
    }

    /** The square is up (any thread may report it; read on the next offer). */
    @Volatile var drawn = 0L

    /**
     * The newest frame, from `frameTime`, and whether it shows the square
     * (null: not looked at, the frame is too old). Returns the answer once
     * there is one, null while the probe goes on.
     */
    fun offer(now: Long, frameTime: Long, showsSquare: () -> Boolean): Answer? {
        if (!running) return null
        if (drawnAt == 0L) drawnAt = drawn
        val d = drawnAt
        if (d != 0L && frameTime > d + FRAME_MS) {
            if (showsSquare()) return finish(Answer.IN_CAPTURE)
            // A frame without it, from long enough after it went up that ours would have been drawn into it.
            if (frameTime > d + sureMs) return finish(Answer.NOT_IN_CAPTURE)
        }
        if (now - (if (d != 0L) d else startedAt) >= waitMs) return finish(Answer.NOT_KNOWN)
        return null
    }

    /** Stop a running probe (the board was lost, the screen turned): no answer. */
    fun cancel() {
        startedAt = 0L
        drawnAt = 0L
        drawn = 0L
    }

    /** Back to the start: a new capture (the screen turned), so the probes start over. */
    fun reset() {
        cancel()
        started = 0
    }

    private fun finish(a: Answer): Answer {
        cancel()
        return a
    }

    companion object {
        /** A frame from this long after the square went up may show it. */
        const val FRAME_MS = 16L
        /** A frame from this long after it went up that does not show it: our windows are not captured. */
        const val SURE_MS = 120L
        const val WAIT_MS = 6_000L
        const val TRIES = 4

        /**
         * Where to put the square on a `w` x `h` screen: off the board and off
         * our card (screen boxes x0 y0 x1 y1, or null: a window over the
         * square would hide it, and a capture without it would be taken for
         * one app shared), and away from the system bars (they lie over our
         * windows). Null when there is no such place: no probe then.
         */
        fun place(w: Int, h: Int, board: IntArray?, card: IntArray?, size: Int): IntArray? {
            val top = h * 8 / 100
            val bottom = h * 92 / 100
            val margin = size * 3
            val ys = if (board == null) listOf(h / 2)
                else listOf((top + board[1]) / 2, (board[3] + bottom) / 2)
            for (y in ys) for (x in listOf(w / 2, w / 6, w * 5 / 6)) {
                val r = intArrayOf(x - size / 2, y - size / 2, x + size / 2, y + size / 2)
                if (r[1] < top || r[3] > bottom) continue
                if (overlaps(r, board, margin) || overlaps(r, card, margin)) continue
                return r
            }
            return null
        }

        private fun overlaps(r: IntArray, b: IntArray?, margin: Int) = b != null &&
            r[0] < b[2] + margin && r[2] > b[0] - margin && r[1] < b[3] + margin && r[3] > b[1] - margin
    }
}
