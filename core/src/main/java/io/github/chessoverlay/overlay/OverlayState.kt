package io.github.chessoverlay.overlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine

/** What the overlay shows. All coordinates are in captured-screen pixels. */
data class OverlayState(
    /** A message for the card ("" while analysis runs normally). */
    val status: String = "",
    /** The engine is still searching this position. */
    val searching: Boolean = false,
    val corners: List<FloatArray>? = null,
    val blackAtBottom: Boolean = false,
    val board: Board? = null,
    val whiteToMove: Boolean = true,
    val lines: List<PvLine> = emptyList(),
    val lastMove: MoveMark? = null,
    /** The move just played (UCI), when known: its squares are highlighted. */
    val playedMove: String? = null,
)

/** A grade for the move just played, drawn on its destination square. */
data class MoveMark(val square: Int, val label: String, val color: Int)

/** What one read of the screen found. */
enum class Look {
    /** A new position; analysis started. */
    NEW,
    /** The same position as before. */
    SAME,
    /** A board, but not a position a game can reach (usually a misread mid-move). */
    INVALID,
    /** No board clear enough. */
    NO_BOARD,
    /** Recognition threw. */
    FAILED,
}

/** Win expectation for White from a pawn score (logistic, as in Lichess). */
fun winChance(pawns: Double): Double = 1.0 / (1.0 + Math.exp(-0.368208 * pawns))
