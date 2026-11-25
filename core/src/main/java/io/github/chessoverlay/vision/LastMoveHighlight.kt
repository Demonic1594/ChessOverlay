package io.github.chessoverlay.vision

import io.github.chessoverlay.chess.Board
import kotlin.math.abs
import kotlin.math.max

/**
 * Who moved last, from the site's last-move highlight.
 *
 * Chess sites tint the two squares of the last move (in any colour, with
 * either side at the bottom, also while stepping through the move list).
 * The board's usual light and dark colours are learned from its empty
 * squares; a square is tinted when a ring of points close to its edges
 * (where even a queen's or king's outline does not reach, and away from the
 * corners where sites print the rank and file) mostly shows another colour.
 *
 * Only a clear picture counts: exactly two tinted squares (a king in check
 * may be tinted too, and is left out), one empty and one holding a piece
 * that can have come from it. Then that piece's colour moved last. No
 * highlight, a premove or the user's own marks (more squares), a board
 * with too few empty squares to learn from: null, and nothing is assumed.
 */
object LastMoveHighlight {
    /** The highlighted move (UCI) and whether White played it. */
    data class Seen(val move: String, val moverWhite: Boolean)

    /** Ring points per square, as (u, v) within the square: 3 along each edge, INSET from it. */
    private const val INSET = 0.065f
    private val RING: List<Pair<Float, Float>> = listOf(0.3f, 0.5f, 0.7f).flatMap { t ->
        listOf(t to INSET, t to 1 - INSET, INSET to t, 1 - INSET to t)
    }
    private val PER = RING.size

    /** Screen points of the rings for a board with these corners: 64 x PER, drawn order (top-left square first). */
    fun ringPoints(k: List<FloatArray>): Pair<IntArray, IntArray> {
        val xs = IntArray(64 * PER)
        val ys = IntArray(64 * PER)
        for (row in 0 until 8) for (col in 0 until 8) for ((j, p) in RING.withIndex()) {
            val u = (col + p.first) / 8f
            val v = (row + p.second) / 8f
            val i = (row * 8 + col) * PER + j
            xs[i] = ((1 - u) * (1 - v) * k[0][0] + u * (1 - v) * k[1][0] + u * v * k[2][0] + (1 - u) * v * k[3][0]).toInt()
            ys[i] = ((1 - u) * (1 - v) * k[0][1] + u * (1 - v) * k[1][1] + u * v * k[2][1] + (1 - u) * v * k[3][1]).toInt()
        }
        return xs to ys
    }
    private const val SAME = 24        // largest channel difference: the same colour
    private const val MIN_EMPTY = 3    // empty squares of each shade needed to learn its colour

    fun find(img: Img, corners: List<FloatArray>, b: Board, blackAtBottom: Boolean): Seen? {
        val (xs, ys) = ringPoints(corners)
        return find(BoardTracker.sample(img, xs, ys, 0, 0), b, blackAtBottom)
    }

    /** `px`: colours at [ringPoints]. */
    fun find(px: IntArray, b: Board, blackAtBottom: Boolean): Seen? {
        if (px.size != 64 * PER) return null
        fun ring(sq: Int): List<Int> {
            val d = PositionDecoder.drawnIndex(sq, blackAtBottom)
            return (0 until PER).map { px[d * PER + it] }.filter { it >= 0 }
        }
        fun light(sq: Int) = (sq % 8 + sq / 8) % 2 == 1
        // The usual colour of each shade: the median ring colour of its empty squares.
        val normal = IntArray(2)
        for (shade in 0..1) {
            val cols = (0 until 64).filter { b[it] == '.' && (if (light(it)) 1 else 0) == shade }.mapNotNull { median(ring(it)) }
            if (cols.size < MIN_EMPTY) return null
            normal[shade] = median(cols) ?: return null
        }
        val tinted = ArrayList<Int>()
        for (sq in 0 until 64) {
            val r = ring(sq)
            if (r.size < PER / 2) return null   // off the image: no verdict
            val usual = normal[if (light(sq)) 1 else 0]
            val asUsual = r.count { distance(it, usual) <= SAME }
            if (asUsual * 3 >= r.size) continue           // a third or more as usual: not tinted
            // Tinted: most of the ring agrees on one other colour (not a piece's own mix).
            val other = median(r.filter { distance(it, usual) > SAME }) ?: continue
            if (r.count { distance(it, other) <= SAME } * 2 >= r.size) tinted += sq
        }
        // A king in check may be tinted as well.
        for (white in listOf(true, false)) {
            val k = b.kingSquare(white)
            if (k >= 0 && k in tinted && tinted.size > 2 && b.inCheck(white)) tinted.remove(k)
        }
        if (tinted.size != 2) return null
        val (a, c) = tinted
        val (from, to) = when {
            b[a] == '.' && b[c] != '.' -> a to c
            b[c] == '.' && b[a] != '.' -> c to a
            else -> return null
        }
        val p = b[to]
        if (!couldHaveMoved(b, p, from, to)) return null
        return Seen(Board.squareName(from) + Board.squareName(to), p.isUpperCase())
    }

    /** Whether `p` (now on `to`) can have just moved there from `from` (empty now). */
    private fun couldHaveMoved(b: Board, p: Char, from: Int, to: Int): Boolean {
        val white = p.isUpperCase()
        val df = to % 8 - from % 8
        val dr = to / 8 - from / 8
        if (p.lowercaseChar() == 'p') {
            val dir = if (white) 1 else -1
            return (df == 0 && (dr == dir || (dr == 2 * dir && from / 8 == (if (white) 1 else 6)))) ||
                (abs(df) == 1 && dr == dir)
        }
        // A promoted piece arrives from the rank before the last as a pawn would.
        val lastRank = if (white) 7 else 0
        if (to / 8 == lastRank && from / 8 == lastRank - (if (white) 1 else -1) && abs(df) <= 1 &&
            p.lowercaseChar() in "qrbn") return true
        // Put it back and see whether it reaches `to` (the square was empty or held what it took).
        return b.with(from to p, to to '.').canMove(from, to)
    }

    private fun distance(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
        max(abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)), abs((a and 0xff) - (b and 0xff))),
    )

    /** Per-channel median, or null for none. */
    private fun median(cols: List<Int>): Int? {
        if (cols.isEmpty()) return null
        fun ch(shift: Int) = cols.map { (it shr shift) and 0xff }.sorted()[cols.size / 2]
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
