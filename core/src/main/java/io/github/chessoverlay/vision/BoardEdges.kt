package io.github.chessoverlay.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Sharpens where the board is.
 *
 * The locator says roughly where the board is: its corners are off by a
 * few per cent, and it takes in what lies next to the board now and then.
 * The board's own edge says exactly: there the picture changes from the
 * page around it to the board. For each side, lines across that side are
 * followed from a little outside the located edge in, each to where the
 * picture stops being the page and stays so; the median of those is the
 * edge. The board is square, so a side with no page beyond it to compare
 * with (the board spans the width of a phone) is taken from the board's
 * being square where a pair of sides was found; where nothing shows it,
 * it stays where the locator put it. Only a board that stands square in
 * the picture is sharpened; a tilted one keeps the locator's corners.
 */
object BoardEdges {
    /** How far from the located edge the true one may be looked for, as a fraction of the board's side. */
    private const val SEARCH = 0.07f
    /** Where the page beyond a side is read, and how wide that band is. */
    private const val BAND = 0.09f
    private const val BAND_HALF = 0.005f
    /** How far out an edge is looked for at the very least (when there is little page beyond it). */
    private const val MIN_SEARCH = 0.015f
    /** The ends of each side are left out (a clock, an avatar). */
    private const val ENDS = 0.08f
    private const val LINES = 64
    /** A picture that differs from the page in a colour channel by this much is the board. */
    private const val DIFFERS = 28
    /** Pixels that must differ in a row for it to be the board, not a speck. */
    private const val RUN = 3

    /** The page beyond a side: its colour, and how far out from the located edge the edge was looked for there. */
    private class Page(val colour: IntArray, val searchOut: Int)

    /** One side of the board: `at` is where the locator put the edge, `from` and `to` its extent along the board,
     *  and the page around the board lies at greater coordinates than the edge when `outward` is +1 (the right
     *  and the bottom side), at smaller ones when it is -1. */
    private class Side(val alongX: Boolean, val at: Int, val from: Float, val to: Float, val outward: Int) {
        /** The picture's last pixel away from the board. */
        fun limit(img: Img) = if (outward > 0) (if (alongX) img.h - 1 else img.w - 1) else 0
    }

    /** The corners with the board's edges where the picture shows them; `corners` (TL, TR, BR, BL) else. */
    fun refine(img: Img, corners: List<FloatArray>): List<FloatArray> {
        if (corners.size != 4) return corners
        val k = corners
        val x0 = k.minOf { it[0] }
        val y0 = k.minOf { it[1] }
        val x1 = k.maxOf { it[0] }
        val y1 = k.maxOf { it[1] }
        val side = max(x1 - x0, y1 - y0)
        if (side <= 0f) return corners
        // Only a board that stands square: each corner within 4 % of the box's corner.
        val box = listOf(floatArrayOf(x0, y0), floatArrayOf(x1, y0), floatArrayOf(x1, y1), floatArrayOf(x0, y1))
        if (k.indices.any { i -> hypot(k[i][0] - box[i][0], k[i][1] - box[i][1]) > 0.04f * side }) return corners

        val sides = listOf(
            Side(alongX = false, at = x0.toInt(), from = y0, to = y1, outward = -1),   // 0: left
            Side(alongX = false, at = x1.toInt(), from = y0, to = y1, outward = 1),    // 1: right
            Side(alongX = true, at = y0.toInt(), from = x0, to = x1, outward = -1),    // 2: top
            Side(alongX = true, at = y1.toInt(), from = x0, to = x1, outward = 1),     // 3: bottom
        )
        fun find(i: Int): Float? {
            val s = sides[i]
            val p = page(img, s, side) ?: return null
            return edge(img, s, side, p)
        }
        var left = find(0)
        var right = find(1)
        var top = find(2)
        var bottom = find(3)
        if (left == null && right == null && top == null && bottom == null) return corners   // the picture showed none
        // The board is square: a side's length from a pair of sides that were found.
        val square = (if (top != null && bottom != null) bottom - top else null)
            ?: (if (left != null && right != null) right - left else null)
        if (square != null) {
            if (left == null && right != null) left = right - square
            else if (right == null && left != null) right = left + square
            if (top == null && bottom != null) top = bottom - square
            else if (bottom == null && top != null) bottom = top + square
            // Neither of two opposite sides had page beyond it (the board spans the width of a phone): as wide
            // as it is high, about where the locator had it.
            if (left == null && right == null) {
                val centre = (x0 + x1) / 2f
                left = centre - square / 2f
                right = centre + square / 2f
            }
            if (top == null && bottom == null) {
                val centre = (y0 + y1) / 2f
                top = centre - square / 2f
                bottom = centre + square / 2f
            }
        }
        val nx0 = left ?: x0
        val nx1 = right ?: x1
        val ny0 = top ?: y0
        val ny1 = bottom ?: y1
        val w = nx1 - nx0
        val h = ny1 - ny0
        // What was found must still be the board: about the size it was said to be, and about square.
        val moved = (2 * SEARCH + 0.02f) * side
        if (abs(w - (x1 - x0)) > moved || abs(h - (y1 - y0)) > moved || abs(w - h) > 0.12f * max(w, h)) return corners
        return listOf(floatArrayOf(nx0, ny0), floatArrayOf(nx1, ny0), floatArrayOf(nx1, ny1), floatArrayOf(nx0, ny1))
    }

    /**
     * The page beyond a side: the median colour of a band outside the located edge, beyond the range the
     * edge is looked for in on that side. That range is as far out as ever when the picture has room for
     * the band beyond it, and only as far as leaves room for it otherwise. Null when there is no such range.
     */
    private fun page(img: Img, s: Side, side: Float): Page? {
        val far = (SEARCH * side).toInt().coerceAtLeast(1)
        val half = (BAND_HALF * side).toInt().coerceAtLeast(1)
        val room = (s.limit(img) - s.at) * s.outward
        val searchOut = min(far, room - 2 * half - 2)
        if (searchOut < (MIN_SEARCH * side).toInt().coerceAtLeast(1)) return null
        val out = min((BAND * side).toInt(), room - half)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        val span = s.to - s.from
        var q = s.from + ENDS * span
        while (q < s.to - ENDS * span) {
            var d = out - half
            while (d <= out + half) {
                val p = s.at + s.outward * d
                val x = if (s.alongX) q.toInt() else p
                val y = if (s.alongX) p else q.toInt()
                if (x in 0 until img.w && y in 0 until img.h) {
                    val c = img[x, y]
                    reds.add((c shr 16) and 0xff)
                    greens.add((c shr 8) and 0xff)
                    blues.add(c and 0xff)
                }
                d++
            }
            q += max(1f, span / 64f)
        }
        if (reds.size < 20) return null
        fun med(a: ArrayList<Int>) = a.sorted()[a.size / 2]
        return Page(intArrayOf(med(reds), med(greens), med(blues)), searchOut)
    }

    /**
     * The edge of one side. Lines across the side are followed from outside the located edge in, each to
     * where the picture is the board (it differs from the page, and goes on differing) and stays so; the
     * median of those is the edge. Null when too few lines say, or they do not agree.
     */
    private fun edge(img: Img, s: Side, side: Float, beyond: Page): Float? {
        fun colour(p: Int, q: Int): Int? {
            val x = if (s.alongX) q else p
            val y = if (s.alongX) p else q
            return if (x in 0 until img.w && y in 0 until img.h) img[x, y] else null
        }
        val page = beyond.colour
        fun differs(c: Int) = max(abs(((c shr 16) and 0xff) - page[0]),
            max(abs(((c shr 8) and 0xff) - page[1]), abs((c and 0xff) - page[2]))) > DIFFERS
        val far = (SEARCH * side).toInt().coerceAtLeast(1)
        val limit = (0.004f * side).toInt().coerceAtLeast(1)
        val span = s.to - s.from
        val start = s.at + s.outward * beyond.searchOut
        val stop = s.at - s.outward * far
        val found = ArrayList<Int>()
        for (i in 0 until LINES) {
            val line = (s.from + ENDS * span + (span * (1 - 2 * ENDS)) * (i + 0.5f) / LINES).toInt()
            var p = start
            var hit: Int? = null
            while (if (s.outward > 0) p >= stop else p <= stop) {
                val c = colour(p, line) ?: break
                if (differs(c)) {
                    // It must go on: the board is not a speck.
                    var run = 1
                    while (run < RUN) {
                        val next = colour(p - s.outward * run, line) ?: break
                        if (!differs(next)) break
                        run++
                    }
                    if (run >= RUN) { hit = p; break }
                }
                p -= s.outward
            }
            val h = hit ?: continue
            // An edge at either end of the range says the edge is further off than was looked for.
            val inward = (s.at - h) * s.outward
            if (inward > far - limit - 1 || -inward > beyond.searchOut - limit - 1) continue
            found.add(h)
        }
        if (found.size < LINES / 5) return null
        found.sort()
        // What most lines say, leaving out the outer fifth on either side, so that lines over something
        // next to the board do not count.
        val mid = found.subList(found.size / 5, found.size - found.size / 5)
        // The first pixel of the board from outside: at the right and the bottom that is the last of the board.
        return mid.sum().toFloat() / mid.size + if (s.outward > 0) 1f else 0f
    }
}
