package io.github.chessoverlay.vision

/**
 * Decides when auto mode reads the board again, from samples of the
 * board's change-detection points (BoardTracker.points) taken every few
 * tens of milliseconds with the overlay showing.
 *
 * - READ when at least `minChanged` squares differ from the last read (or
 *   one does and another is hidden under the overlay's drawing), and the
 *   board has not moved for `stillMs`: pieces glide for a few frames, and a
 *   piece read in flight is a hallucinated position.
 * - Squares hidden under the drawing are also looked at every `blindMs`.
 * - [captureMatches] then checks that the clean capture still shows the
 *   board as it was when READ was decided; if a piece started moving in
 *   between, the capture is thrown away.
 */
class ChangeWatcher(
    private val stillMs: Long = 120,
    private val minChanged: Int = 2,
    private val blindMs: Long = 20_000,
) {
    enum class Decision { WAIT, READ }

    private var last: IntArray? = null
    private var stillSince = 0L

    /** The colours when the last READ was decided (with the cover in force then). */
    var still: IntArray? = null
        private set
    var stillCover: BoardTracker.Cover? = null
        private set

    /** Forget the last sample (after a read, or when auto mode starts). */
    fun reset() { last = null }

    fun offer(now: Long, px: IntArray, tracker: BoardTracker, cover: BoardTracker.Cover?, lastRead: Long): Decision {
        val (xs, ys) = tracker.points() ?: return Decision.WAIT
        val before = last
        last = px
        if (before == null || BoardTracker.compare(before, px, xs, ys, cover).changed > 0) {
            stillSince = now
            return Decision.WAIT
        }
        if (now - stillSince < stillMs) return Decision.WAIT
        val c = tracker.compare(px, cover)
        val read = c.changed >= minChanged || (c.changed >= 1 && c.blind > 0) ||
            (c.blind > 0 && now - lastRead >= blindMs)
        if (!read) return Decision.WAIT
        still = px
        stillCover = cover
        return Decision.READ
    }

    /** Whether `img` (the clean capture) shows the board as it was at the READ decision. */
    fun captureMatches(img: Img, tracker: BoardTracker): Boolean {
        val s = still ?: return true
        val (xs, ys) = tracker.points() ?: return true
        return BoardTracker.compare(s, BoardTracker.sample(img, xs, ys, 0, 0), xs, ys, stillCover).changed == 0
    }
}
