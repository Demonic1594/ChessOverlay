package io.github.chessoverlay.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Sharpens where the board is.
 *
 * The locator says roughly where the board is. Mostly its corners are off
 * by a few pixels; now and then it misses part of the board, a file or a
 * rank, which shows in its box not being square. The board's own edge says
 * exactly: there the picture changes from the page around it to the board.
 * For each side, lines across that side are followed from outside the
 * located edge in, each to where the picture stops being the page and
 * stays so; what most lines agree on is the edge. The page is the median
 * colour of a band beyond the range looked in, on that side where it has
 * one that is one colour (a band that lies on the board is not), else what
 * another side shows.
 *
 * The board is square. A side with no page beyond it to compare with (the
 * board spans the width of a phone) is put a side's length from the
 * opposite one when a pair of sides was found; about where the locator had
 * it when neither of two opposite sides has page; where nothing shows it,
 * it stays where the locator put it. Only a board that stands square in
 * the picture is sharpened; a tilted one keeps the locator's corners.
 */
object BoardEdges {
    /** How far inside the located edge the true one may be looked for (a fraction of the board's side). */
    private const val INWARD = 0.07f
    /** How far outside it: when the located box is about square, and when it is not (a file or a rank missed). */
    private const val NEAR = 0.07f
    private const val WIDE = 0.20f
    /** From this ratio between the located box's sides down (the shorter over the longer) it is not about square. */
    private const val SQUARE = 0.97f
    /** Where the page beyond a side is read: beyond the range looked in. */
    private const val NEAR_BAND = 0.09f
    private const val WIDE_BAND = 0.22f
    private const val BAND_HALF = 0.005f
    /** A range of at least this is needed to look for an edge at all. */
    private const val MIN_RANGE = 0.015f
    /** The ends of each side are left out (a clock, an avatar). */
    private const val ENDS = 0.08f
    private const val LINES = 64
    /** A picture that differs from the page in a colour channel by this much is the board. */
    private const val DIFFERS = 28
    /** Pixels that must differ in a row for it to be the board, not a speck. */
    private const val RUN = 3
    /** A band whose colour varies more than this between its lower and its upper quarter is no page. */
    private const val EVEN = 16
    /** The lines that agree on an edge must be at least this share of those that found one. */
    private const val AGREE = 0.35f

    /** One side of the board: `at` is where the locator put the edge, `from` and `to` its extent along the board,
     *  and the page around the board lies at greater coordinates than the edge when `outward` is +1 (the right
     *  and the bottom side), at smaller ones when it is -1. */
    private class Side(val alongX: Boolean, val at: Int, val from: Float, val to: Float, val outward: Int) {
        /** The picture's last pixel away from the board. */
        fun limit(img: Img) = if (outward > 0) (if (alongX) img.h - 1 else img.w - 1) else 0
    }

    /** What a side has to be looked for in: how far out from the located edge, and the colour of the page beyond it
     *  where the side has some that is one colour. */
    private class Range(val out: Int, val page: IntArray?)

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

        // A located box that is not about square missed part of the board: its edges are looked for further out.
        val wide = min(x1 - x0, y1 - y0) < SQUARE * side
        val out = if (wide) WIDE else NEAR
        val bandAt = if (wide) WIDE_BAND else NEAR_BAND
        val sides = listOf(
            Side(alongX = false, at = x0.toInt(), from = y0, to = y1, outward = -1),   // 0: left
            Side(alongX = false, at = x1.toInt(), from = y0, to = y1, outward = 1),    // 1: right
            Side(alongX = true, at = y0.toInt(), from = x0, to = x1, outward = -1),    // 2: top
            Side(alongX = true, at = y1.toInt(), from = x0, to = x1, outward = 1),     // 3: bottom
        )
        val ranges = sides.map { range(img, it, side, out, bandAt) }
        // The page where a side has none of its own that is one colour: what the sides that have one show.
        val own = ranges.mapNotNull { it?.page }
        val common = if (own.isEmpty()) null else IntArray(3) { c -> own.map { it[c] }.sorted()[own.size / 2] }
        fun find(i: Int): Float? {
            val r = ranges[i] ?: return null
            val page = r.page ?: common ?: return null
            return edge(img, sides[i], side, page, r.out)
        }
        val left = find(0)
        val right = find(1)
        val top = find(2)
        val bottom = find(3)
        if (left == null && right == null && top == null && bottom == null) return corners   // the picture showed none
        val maxMove = (out + INWARD + 0.01f) * side
        // The board is square: a side's length from a pair of sides that were found.
        val square = (if (top != null && bottom != null) bottom - top else null)
            ?: (if (left != null && right != null) right - left else null)
        if (square != null) {
            // What was found must be the board the locator gave: of about its size.
            if (abs(square - side) > maxMove) return corners
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            // A side that was not found lies a side's length from the opposite one; when neither of two opposite
            // sides has page beyond it (the board spans the width of a phone), about where the locator had it.
            val l = left ?: (if (right != null) right - square else cx - square / 2f)
            val r = right ?: (if (left != null) left + square else cx + square / 2f)
            val t = top ?: (if (bottom != null) bottom - square else cy - square / 2f)
            val b = bottom ?: (if (top != null) top + square else cy + square / 2f)
            return listOf(floatArrayOf(l, t), floatArrayOf(r, t), floatArrayOf(r, b), floatArrayOf(l, b))
        }
        // No pair was found: what single edges were, if that is still about the board the locator gave.
        val nx0 = left ?: x0
        val nx1 = right ?: x1
        val ny0 = top ?: y0
        val ny1 = bottom ?: y1
        val w = nx1 - nx0
        val h = ny1 - ny0
        if (w <= 0f || h <= 0f) return corners
        if (abs(w - (x1 - x0)) > maxMove || abs(h - (y1 - y0)) > maxMove || abs(w - h) > 0.12f * max(w, h)) return corners
        return listOf(floatArrayOf(nx0, ny0), floatArrayOf(nx1, ny0), floatArrayOf(nx1, ny1), floatArrayOf(nx0, ny1))
    }

    /**
     * What a side has to be looked for in. The range reaches out from the located edge as far as given when
     * the picture has room for the band of page beyond it, and only as far as leaves room for it otherwise.
     * Null when there is no such range (no page beyond the edge at all). The band is no page when its colour
     * varies: it lies on the board, or on something next to it.
     */
    private fun range(img: Img, s: Side, side: Float, outRange: Float, bandAt: Float): Range? {
        val half = (BAND_HALF * side).toInt().coerceAtLeast(1)
        val room = (s.limit(img) - s.at) * s.outward
        val out = min((outRange * side).toInt(), room - 2 * half - 2)
        if (out < (MIN_RANGE * side).toInt().coerceAtLeast(1)) return null
        val band = min((bandAt * side).toInt(), room - half)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        val span = s.to - s.from
        var q = s.from + ENDS * span
        while (q < s.to - ENDS * span) {
            var d = band - half
            while (d <= band + half) {
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
        if (reds.size < 20) return Range(out, null)
        fun med(a: ArrayList<Int>) = a.sorted()[a.size / 2]
        fun spread(a: ArrayList<Int>): Int { val sorted = a.sorted(); return sorted[sorted.size * 3 / 4] - sorted[sorted.size / 4] }
        val page = if (max(spread(reds), max(spread(greens), spread(blues))) > EVEN) null
            else intArrayOf(med(reds), med(greens), med(blues))
        return Range(out, page)
    }

    /**
     * The edge of one side. Lines across the side are followed from outside the located edge in, each to
     * where the picture is the board (it differs from the page, and goes on differing) and stays so; the edge
     * is where most of the lines that found one agree. Null when too few lines say, or they do not agree.
     */
    private fun edge(img: Img, s: Side, side: Float, page: IntArray, out: Int): Float? {
        fun colour(p: Int, q: Int): Int? {
            val x = if (s.alongX) q else p
            val y = if (s.alongX) p else q
            return if (x in 0 until img.w && y in 0 until img.h) img[x, y] else null
        }
        fun differs(c: Int) = max(abs(((c shr 16) and 0xff) - page[0]),
            max(abs(((c shr 8) and 0xff) - page[1]), abs((c and 0xff) - page[2]))) > DIFFERS
        val far = (INWARD * side).toInt().coerceAtLeast(1)
        val limit = (0.004f * side).toInt().coerceAtLeast(1)
        val span = s.to - s.from
        val start = s.at + s.outward * out
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
            if (inward > far - limit - 1 || -inward > out - limit - 1) continue
            found.add(h)
        }
        if (found.size < LINES / 5) return null
        found.sort()
        // What most lines say: the most lines within a few pixels of each other. Scattered lines (over
        // something next to the board) do not count, and when no group holds enough of them there is no edge.
        val width = max(4, (0.005f * side).toInt())
        var low = 0
        var bestLow = 0
        var bestHigh = 0
        for (high in found.indices) {
            while (found[high] - found[low] > width) low++
            if (high - low > bestHigh - bestLow) { bestLow = low; bestHigh = high }
        }
        if (bestHigh - bestLow + 1 < found.size * AGREE) return null
        val group = found.subList(bestLow, bestHigh + 1)
        // The first pixel of the board from outside: at the right and the bottom that is the last of the board.
        return group[group.size / 2].toFloat() + if (s.outward > 0) 1f else 0f
    }
}
