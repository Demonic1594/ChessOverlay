package io.github.chessoverlay.vision

import io.github.chessoverlay.chess.Board
import kotlin.math.ln

/**
 * Reads a recognition in the light of the game so far.
 *
 * The model rates every piece on every square. In a game, the next
 * position can only be the last one, or the last one after a move by
 * either side, or after one move each (a quick reply). So under both
 * orientations (the board may have been turned round, by hand or by a
 * site that flips after each move) the most likely of those is compared
 * with the model's plain reading. If it is almost as likely, it is the
 * answer: a square the model got wrong, or a board turned round, cannot
 * produce a position the game cannot reach. If nothing the game allows
 * comes close (a new game, a jump in the move list, an undo), there is no
 * answer here and the plain reading is used.
 */
object PositionDecoder {
    /**
     * How much more likely (log) the plain reading may be than the game's best
     * explanation. Following a game (auto mode), one square read wrong with
     * full confidence (a piece hidden under a site's own arrow or highlight
     * reads as empty) is allowed for: a piece cannot vanish without a move.
     * A read asked for by hand trusts the picture more (an edited analysis
     * board may really have lost a piece).
     */
    const val TAU_FOLLOWING = 12.0
    const val TAU_BY_HAND = 6.0

    /**
     * A square the model reads, sure of itself (by this much, log), as
     * something else than the explanation has. At most one is allowed:
     * an undo, which really changes two squares, must not pass for "no move".
     */
    private const val SURE_DISAGREEMENT = 4.0
    /** Turning the board round must explain clearly more than keeping it. */
    private const val FLIP_COST = 2.0

    class Reading(val board: Board, val blackAtBottom: Boolean, val moves: List<String>)

    fun decode(prev: Board, whiteToMove: Boolean, blackAtBottom: Boolean, rec: Recognition,
               tau: Double = TAU_FOLLOWING): Reading? {
        val probs = rec.drawnProbs ?: return null
        if (probs.size != 64) return null
        val logp = Array(64) { d -> DoubleArray(Fen.CLASSES.length) { c -> ln(maxOf(probs[d][c].toDouble(), 1e-6)) } }
        val plain = (0 until 64).sumOf { d -> logp[d].max() }

        var best: Reading? = null
        var bestL = Double.NEGATIVE_INFINITY
        for (black in listOf(blackAtBottom, !blackAtBottom)) {
            val cost = if (black == blackAtBottom) 0.0 else FLIP_COST
            fun score(b: Board): Double {
                var l = -cost
                for (sq in 0 until 64) l += logp[drawnIndex(sq, black)][Fen.classOf(b[sq])]
                return l
            }
            fun offer(b: Board, moves: List<String>) {
                val l = score(b)
                if (l > bestL) { bestL = l; best = Reading(b, black, moves) }
            }
            offer(prev, emptyList())
            for (white in listOf(whiteToMove, !whiteToMove)) {
                for (m in prev.legalMoves(white)) {
                    val one = prev.copy().also { it.play(m) }
                    offer(one, listOf(m))
                    if (white == whiteToMove) {
                        for (r in one.legalMoves(!white)) offer(one.copy().also { it.play(r) }, listOf(m, r))
                    }
                }
            }
        }
        val r = best ?: return null
        if (bestL < plain - tau) return null
        var sure = 0
        for (sq in 0 until 64) {
            val d = drawnIndex(sq, r.blackAtBottom)
            if (logp[d].max() - logp[d][Fen.classOf(r.board[sq])] > SURE_DISAGREEMENT) sure++
        }
        return r.takeIf { sure <= 1 }
    }

    /** Index (top-left first) of `square` (a1 = 0) on a board drawn with `black` at the bottom. */
    fun drawnIndex(square: Int, black: Boolean): Int {
        val file = square % 8
        val rank = square / 8
        return if (black) rank * 8 + (7 - file) else (7 - rank) * 8 + file
    }
}
