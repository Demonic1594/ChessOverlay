package io.github.chessoverlay.vision

import io.github.chessoverlay.chess.Board
import kotlin.math.abs
import kotlin.math.max

/**
 * Follows one game on screen.
 *
 * - Change detection: a cheap per-square colour signature of the board area
 *   tells whether anything on the board changed, so the recognizer (the
 *   expensive part) only runs when it did.
 * - Game tracking: consecutive positions are linked by the exact move
 *   between them (Board.findMove), which gives the side to move and the move
 *   just played; a jump of several moves or a new game falls back to the
 *   colour that left squares, then to the side at the bottom.
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
    private var signature: FloatArray? = null

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
        corners = null; board = null; signature = null
    }

    /** True if the board area looks different from the last accepted position.
     *  Without a known board, always true. */
    fun changed(img: Img): Boolean {
        val k = corners ?: return true
        val old = signature ?: return true
        return changedSquares(old, signature(img, k)) > 0
    }

    /**
     * Take a recognition. `sideOverride` forces the side to move (null = work
     * it out). Returns what changed.
     */
    fun accept(img: Img, rec: Recognition, sideOverride: Boolean?): Result {
        val b = try {
            Board.fromPlacement(rec.placement)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid(e.message ?: "bad board")
        }
        val prev = board
        val prevWhite = whiteToMove
        val flipped = prev != null && rec.blackAtBottom != blackAtBottom
        val sameBoard = prev != null && !flipped && prev.placement() == b.placement()
        corners = rec.corners
        blackAtBottom = rec.blackAtBottom
        signature = signature(img, rec.corners)
        if (sameBoard && sideOverride == null) return Result.Same

        val move = if (prev != null && !flipped) Board.findMove(prev, b) else null
        val prefer: Boolean = sideOverride ?: when {
            move != null -> !b[Board.squareOf(move.substring(2, 4))].isUpperCase()
            prev != null && !flipped -> Board.mover(prev, b)?.let { !it } ?: !rec.blackAtBottom
            else -> !rec.blackAtBottom
        }
        val white = Board.sideToMove(b, prefer)
            ?: return Result.Invalid(b.problem(prefer) ?: "not a legal position")
        if (sameBoard && white == prevWhite) return Result.Same
        board = b
        whiteToMove = white
        return Result.Position(b, white, move, if (flipped) null else prev, prevWhite)
    }

    companion object {
        /** Squares sampled per side: 5 x 5 points over the inner 70% of each square. */
        private const val SAMPLES = 5

        /** Mean RGB of each of the 64 squares as drawn on screen (row-major from the top-left). */
        fun signature(img: Img, k: List<FloatArray>): FloatArray {
            val out = FloatArray(64 * 3)
            for (row in 0 until 8) for (col in 0 until 8) {
                var r = 0f; var g = 0f; var bl = 0f; var n = 0
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    val u = (col + 0.15f + 0.7f * (sx + 0.5f) / SAMPLES) / 8f
                    val v = (row + 0.15f + 0.7f * (sy + 0.5f) / SAMPLES) / 8f
                    val x = ((1 - u) * (1 - v) * k[0][0] + u * (1 - v) * k[1][0] + u * v * k[2][0] + (1 - u) * v * k[3][0]).toInt()
                    val y = ((1 - u) * (1 - v) * k[0][1] + u * (1 - v) * k[1][1] + u * v * k[2][1] + (1 - u) * v * k[3][1]).toInt()
                    if (x !in 0 until img.w || y !in 0 until img.h) continue
                    val p = img[x, y]
                    r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; bl += p and 0xff; n++
                }
                val i = (row * 8 + col) * 3
                if (n > 0) { out[i] = r / n; out[i + 1] = g / n; out[i + 2] = bl / n }
            }
            return out
        }

        /** Squares whose mean colour moved by more than `threshold` (0-255) in any channel. */
        fun changedSquares(a: FloatArray, b: FloatArray, threshold: Float = 14f): Int {
            var n = 0
            for (s in 0 until 64) {
                var d = 0f
                for (c in 0 until 3) d = max(d, abs(a[s * 3 + c] - b[s * 3 + c]))
                if (d > threshold) n++
            }
            return n
        }
    }
}
