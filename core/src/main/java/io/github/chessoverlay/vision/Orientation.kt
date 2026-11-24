package io.github.chessoverlay.vision

import kotlin.math.max

/**
 * Which way round a board is drawn.
 *
 * The model's own guess is often unsure (on 300 benchmark boards it was
 * wrong 6 times, always below 66% sure). The position is a much better
 * clue: pawns and kings stay near their own side, so a board read the
 * wrong way round has White's pawns and king far up the board and Black's
 * far down. On 5000 positions from random games this "strain" picked the
 * right way round 4996 times.
 */
object Orientation {
    /** Clear position evidence: overrides the model. */
    private const val STRONG = 6
    /** Some evidence: decides when the model is unsure. */
    private const val WEAK = 2
    /** The model is trusted from here. */
    private const val SURE = 0.97f

    /** The placement from White's side, for a placement as drawn. */
    fun whiteView(drawn: String, blackAtBottom: Boolean): String = if (blackAtBottom) Fen.flip(drawn) else drawn

    /** How far pawns and kings stand from their own side (White's ranks from 1, Black's from 8). */
    fun strain(placement: String): Int {
        var s = 0
        for ((i, row) in placement.split('/').withIndex()) {
            val rank = 7 - i
            for (c in row) when (c) {
                'P', 'K' -> s += rank
                'p', 'k' -> s += 7 - rank
            }
        }
        return s
    }

    /** How much more natural the board is with `black` at the bottom than the other way round. */
    fun margin(drawn: String, black: Boolean): Int =
        strain(whiteView(drawn, !black)) - strain(whiteView(drawn, black))

    /**
     * Black at the bottom? Clear position evidence wins; otherwise a sure
     * model; otherwise `prior` (the game so far) if there is one; otherwise
     * the position's lean, or the model.
     */
    fun decide(drawn: String, pBlackBottom: Float, prior: Boolean? = null): Boolean {
        val model = pBlackBottom > 0.5f
        val d = margin(drawn, model)
        if (d <= -STRONG) return !model
        if (d >= STRONG) return model
        if (max(pBlackBottom, 1f - pBlackBottom) >= SURE) return model
        if (prior != null) return prior
        return if (d <= -WEAK) !model else model
    }
}
