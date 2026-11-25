package io.github.chessoverlay.overlay

/**
 * "Is anything on the screen moving?" before a board is known: a coarse
 * grid of points over the whole screen, compared from one check to the
 * next. Still means no more than 1 % of the points changed for [stillMs]:
 * a board caught in a site's opening animation would be found in the
 * wrong place, so the search for one waits for this.
 */
class ScreenMotion(private val stillMs: Long = STILL_MS) {
    private var xs: IntArray? = null
    private var ys: IntArray? = null
    private var sizeW = 0
    private var sizeH = 0
    private var last: IntArray? = null
    private var stillSince = 0L

    /** The grid's points on a `w` x `h` screen (the same arrays while the size stays). */
    fun points(w: Int, h: Int): Pair<IntArray, IntArray> {
        if (xs == null || w != sizeW || h != sizeH) {
            sizeW = w; sizeH = h; last = null
            xs = IntArray(GRID_W * GRID_H) { (it % GRID_W) * w / GRID_W + w / (2 * GRID_W) }
            ys = IntArray(GRID_W * GRID_H) { (it / GRID_W) * h / GRID_H + h / (2 * GRID_H) }
        }
        return xs!! to ys!!
    }

    /** The grid's pixels now (RGB, from points()); true once the screen has been still for stillMs. */
    fun offer(now: Long, px: IntArray): Boolean {
        val before = last
        last = px
        var moving = before == null || before.size != px.size
        if (!moving) {
            var diff = 0
            for (i in px.indices) {
                val a = px[i]; val b = before!![i]
                val d = maxOf(kotlin.math.abs((a shr 16 and 0xff) - (b shr 16 and 0xff)),
                    kotlin.math.abs((a shr 8 and 0xff) - (b shr 8 and 0xff)), kotlin.math.abs((a and 0xff) - (b and 0xff)))
                if (d > 24) diff++
            }
            moving = diff > px.size / 100   // more than 1% of the grid
        }
        if (moving) stillSince = now
        return now - stillSince >= stillMs
    }

    companion object {
        const val GRID_W = 18
        const val GRID_H = 36
        const val STILL_MS = 300L
    }
}
