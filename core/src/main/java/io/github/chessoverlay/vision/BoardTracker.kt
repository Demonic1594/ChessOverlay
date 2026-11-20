package io.github.chessoverlay.vision

import io.github.chessoverlay.chess.Board
import kotlin.math.abs
import kotlin.math.max

/**
 * Follows one game on screen.
 *
 * - Change detection: the colours at 49 points of every square, kept from
 *   the last clean read, are compared with the screen. Points the overlay
 *   draws on are skipped (see Cover), so this works with the overlay
 *   showing; the recognizer (the expensive part, and the only step that
 *   needs the overlay hidden) runs only when squares really changed.
 * - A read that is not a reachable position (a piece caught mid-move) is
 *   reported as Invalid and changes nothing here, so the next check sees
 *   the same change and reads again.
 * - Game tracking: consecutive positions are linked by the exact move
 *   between them (Board.findMove), which gives the side to move and the move
 *   just played; a jump of several moves or a new game falls back to the
 *   colour that left squares, then to the side at the bottom. Two moves at
 *   once (a quick reply) are found too and keep the side to move.
 */
class BoardTracker {
    var corners: List<FloatArray>? = null
        private set
    var blackAtBottom = false
        private set
    var board: Board? = null
        private set
    var whiteToMove = true
        private set
    // Sample points (screen pixels) for the current corners, and their
    // colours in the last clean read: 64 squares x PER points, row-major from
    // the top-left square as drawn.
    private var xs: IntArray? = null
    private var ys: IntArray? = null
    private var ref: IntArray? = null

    /** Which screen pixels the overlay draws on (they are not compared). */
    fun interface Cover {
        fun covered(x: Int, y: Int): Boolean
    }

    sealed class Result {
        /** Nothing on the board changed. */
        object Same : Result()
        /** A new position: `move` was played (by `mover`) when it is known. */
        data class Position(
            val board: Board,
            val whiteToMove: Boolean,
            val move: String?,
            val previous: Board?,
            val previousWhiteToMove: Boolean,
        ) : Result()
        /** Recognised, but not a position an engine can take. */
        data class Invalid(val reason: String) : Result()
    }

    fun reset() {
        corners = null; board = null; xs = null; ys = null; ref = null
    }

    /** The board's bounding box on screen (x0, y0, x1, y1), or null. */
    fun region(): IntArray? {
        val k = corners ?: return null
        return intArrayOf(k.minOf { it[0] }.toInt(), k.minOf { it[1] }.toInt(),
            k.maxOf { it[0] }.toInt() + 1, k.maxOf { it[1] }.toInt() + 1)
    }

    /** Screen positions of the change-detection points (x, y), once a board is known. */
    fun points(): Pair<IntArray, IntArray>? {
        val x = xs ?: return null
        val y = ys ?: return null
        return x to y
    }

    /**
     * Compare colours at [points] (`now`, -1 = not on screen) with the last
     * clean read. Points the `cover` says the overlay draws on are skipped.
     */
    fun compare(now: IntArray, cover: Cover?): Comparison {
        val x = xs; val y = ys; val r = ref
        if (x == null || y == null || r == null || now.size != r.size) return Comparison(64, 0)
        return compare(r, now, x, y, cover)
    }

    /** Squares that changed since the last clean read, in `img` (a crop whose top-left is screen (ox, oy)). */
    fun changedSquares(img: Img, ox: Int = 0, oy: Int = 0, cover: Cover? = null): Int {
        val x = xs ?: return 64
        val y = ys ?: return 64
        return compare(sample(img, x, y, ox, oy), cover).changed
    }

    /** Remember the board's colours from a clean (overlay-free) image. */
    private fun remember(img: Img, k: List<FloatArray>) {
        val (px, py) = samplePoints(k)
        xs = px; ys = py
        ref = sample(img, px, py, 0, 0)
    }

    /** A small change no move explains, read once: it must be read again the same way. */
    private var suspect: String? = null

    /**
     * Take a recognition. `sideOverride` forces the side to move (null = work
     * it out). `strict` (auto mode): a small change that neither one nor two
     * moves explain is most likely a misread, so it counts only when the
     * next read sees the same. Returns what changed.
     */
    fun accept(img: Img, rec: Recognition, sideOverride: Boolean?, strict: Boolean = false): Result {
        val b = try {
            Board.fromPlacement(rec.placement)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid(e.message ?: "bad board")
        }
        val prev = board
        val prevWhite = whiteToMove
        val flipped = prev != null && rec.blackAtBottom != blackAtBottom
        val sameBoard = prev != null && !flipped && prev.placement() == b.placement()
        if (sameBoard && sideOverride == null) {
            corners = rec.corners
            remember(img, rec.corners)
            return Result.Same
        }

        val linked = prev != null && !flipped
        val move = if (linked) Board.findMove(prev!!, b) else null
        // Both sides moved before the screen was read (a quick reply): the
        // same side is to move again.
        val two = if (linked && move == null) Board.findTwoMoves(prev!!, b, prevWhite) else null
        if (linked && move == null && two == null && strict && sideOverride == null) {
            val diff = (0..63).count { prev!![it] != b[it] }
            if (diff <= SMALL_CHANGE && suspect != b.placement()) {
                suspect = b.placement()
                return Result.Invalid("no move explains it; reading again")
            }
        }
        suspect = null
        val prefer: Boolean = sideOverride ?: when {
            move != null -> !b[Board.squareOf(move.substring(2, 4))].isUpperCase()
            two != null -> prevWhite
            linked -> Board.mover(prev!!, b)?.let { !it } ?: !rec.blackAtBottom
            else -> !rec.blackAtBottom
        }
        // A position no game can reach is a misread (a piece caught mid-move):
        // keep the last good read, and its colours, so the change is still
        // seen and read again.
        val white = Board.sideToMove(b, prefer)
            ?: return Result.Invalid(b.problem(prefer) ?: "not a legal position")
        corners = rec.corners
        blackAtBottom = rec.blackAtBottom
        remember(img, rec.corners)
        if (sameBoard && white == prevWhite) return Result.Same
        board = b
        whiteToMove = white
        return Result.Position(b, white, move, if (flipped) null else prev, prevWhite)
    }

    /** `changed` squares look different; `blind` ones had too few uncovered points to tell. */
    data class Comparison(val changed: Int, val blind: Int)

    companion object {
        /** Changed squares up to which an unexplained change is suspected to be a misread. */
        private const val SMALL_CHANGE = 4

        /** Points per square side: 7 x 7 over the inner 70% of each square. */
        private const val SAMPLES = 7
        const val PER = SAMPLES * SAMPLES
        /** Visible points a square needs for a verdict. */
        private const val MIN_POINTS = 5
        /** Mean colour change (largest channel, 0-255) that counts as changed. */
        private const val THRESHOLD = 18

        /** Screen positions of the sample points for a board with these corners. */
        fun samplePoints(k: List<FloatArray>): Pair<IntArray, IntArray> {
            val xs = IntArray(64 * PER)
            val ys = IntArray(64 * PER)
            for (row in 0 until 8) for (col in 0 until 8) for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                val u = (col + 0.15f + 0.7f * (sx + 0.5f) / SAMPLES) / 8f
                val v = (row + 0.15f + 0.7f * (sy + 0.5f) / SAMPLES) / 8f
                val i = (row * 8 + col) * PER + sy * SAMPLES + sx
                xs[i] = ((1 - u) * (1 - v) * k[0][0] + u * (1 - v) * k[1][0] + u * v * k[2][0] + (1 - u) * v * k[3][0]).toInt()
                ys[i] = ((1 - u) * (1 - v) * k[0][1] + u * (1 - v) * k[1][1] + u * v * k[2][1] + (1 - u) * v * k[3][1]).toInt()
            }
            return xs to ys
        }

        /** Colours at the given screen points in `img` (top-left at screen (ox, oy)); -1 off the image. */
        fun sample(img: Img, xs: IntArray, ys: IntArray, ox: Int, oy: Int): IntArray = IntArray(xs.size) { i ->
            val lx = xs[i] - ox
            val ly = ys[i] - oy
            if (lx in 0 until img.w && ly in 0 until img.h) img[lx, ly] else -1
        }

        /**
         * Squares whose colours differ between two samples of the same
         * points (largest channel change above THRESHOLD on average, over at
         * least MIN_POINTS uncovered points).
         */
        fun compare(a: IntArray, b: IntArray, xs: IntArray, ys: IntArray, cover: Cover?): Comparison {
            var changed = 0
            var blind = 0
            for (sq in 0 until 64) {
                var n = 0
                var total = 0
                for (j in 0 until PER) {
                    val i = sq * PER + j
                    if (a[i] < 0 || b[i] < 0) continue
                    if (cover != null && cover.covered(xs[i], ys[i])) continue
                    total += colourDistance(a[i], b[i])
                    n++
                }
                if (n < MIN_POINTS) blind++ else if (total > THRESHOLD * n) changed++
            }
            return Comparison(changed, blind)
        }

        private fun colourDistance(a: Int, b: Int): Int = max(
            abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
            max(abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)), abs((a and 0xff) - (b and 0xff))),
        )
    }
}
