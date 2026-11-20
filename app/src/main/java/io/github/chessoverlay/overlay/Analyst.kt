package io.github.chessoverlay.overlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.engine.UciEngine
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognition
import io.github.chessoverlay.vision.Recognizer

/** What the overlay shows. All coordinates are in captured-screen pixels. */
data class OverlayState(
    val status: String = "",
    val corners: List<FloatArray>? = null,
    val blackAtBottom: Boolean = false,
    val board: Board? = null,
    val whiteToMove: Boolean = true,
    val lines: List<PvLine> = emptyList(),
    val lastMove: MoveMark? = null,
)

/** A grade for the move just played, drawn on its destination square. */
data class MoveMark(val square: Int, val label: String, val color: Int)

/**
 * Screen image -> recognised board -> legal position -> engine lines. No
 * Android UI here: the overlay service calls it from a worker thread and
 * draws whatever `onState` reports.
 */
class Analyst(
    private val recognizer: Recognizer,
    private val engine: UciEngine,
    private val onState: (OverlayState) -> Unit,
) {
    var lines = 3
    var thinkMs = 3000L
    var minConfidence = 0.5f
    var annotate = true

    /** null = work it out (the side whose king is in check, the side that did
     *  not just move in auto mode, else the side at the bottom). */
    @Volatile var sideOverride: Boolean? = null

    private var state = OverlayState()
    private var lastPlacement: String? = null
    private var lastBest: PvLine? = null
    private var lastWhiteToMove = true
    private var lastBoard: Board? = null

    @Synchronized
    fun analyse(img: Img, auto: Boolean) {
        val rec: Recognition = try {
            recognizer.recognise(img)
        } catch (e: Exception) {
            publish(state.copy(status = "Recognition failed: ${e.message}"))
            return
        }
        if (rec.confidence < minConfidence) {
            if (!auto) publish(OverlayState(status = "No clear board found (${(rec.confidence * 100).toInt()}% sure)"))
            return
        }
        // In auto mode the same position again needs nothing new.
        if (auto && rec.placement == lastPlacement && state.board != null) return

        val board = try {
            Board.fromPlacement(rec.placement)
        } catch (e: IllegalArgumentException) {
            publish(OverlayState(status = "Bad board: ${e.message}"))
            return
        }
        val prevBoard = lastBoard
        val mover = if (prevBoard != null) Board.mover(prevBoard, board) else null
        val prefer = sideOverride ?: when {
            auto && mover != null -> !mover
            else -> !rec.blackAtBottom
        }
        val white = Board.sideToMove(board, prefer)
        if (white == null) {
            publish(OverlayState(status = "Not a legal position: ${board.problem(prefer)}", corners = rec.corners,
                blackAtBottom = rec.blackAtBottom))
            return
        }

        // Grade the move just played from the previous evaluation, once the
        // first evaluation of the new position arrives.
        val previousBest = lastBest
        val previousWhite = lastWhiteToMove
        val gradeMove = annotate && auto && prevBoard != null && mover != null && previousBest != null &&
            mover == previousWhite
        val destination = if (gradeMove) destinationOf(prevBoard!!, board, mover!!) else -1
        val playedBest = gradeMove && previousBest!!.pv.firstOrNull()?.let { m ->
            prevBoard!!.copy().also { it.play(m) }.placement() == board.placement()
        } == true

        lastPlacement = rec.placement
        lastBoard = board
        lastWhiteToMove = white
        lastBest = null
        val base = OverlayState(
            status = "Analysing…",
            corners = rec.corners,
            blackAtBottom = rec.blackAtBottom,
            board = board,
            whiteToMove = white,
        )
        publish(base)
        engine.analyse(board.fen(white), lines, thinkMs) { pvs, finished ->
            val best = pvs.firstOrNull()
            if (best != null) lastBest = best
            var mark: MoveMark? = state.lastMove.takeIf { state.board === board }
            if (gradeMove && destination >= 0 && best != null && previousBest != null) {
                mark = grade(previousBest, previousWhite, best, white, playedBest, destination)
            }
            val depth = best?.depth ?: 0
            publish(base.copy(
                status = if (finished) "Depth $depth" else "Depth $depth…",
                lines = pvs,
                lastMove = mark,
            ))
        }
    }

    fun stop() = engine.stop()

    private fun publish(s: OverlayState) {
        state = s
        onState(s)
    }

    companion object {
        /** The square a move went to: where the mover now has a piece it did not have. */
        fun destinationOf(before: Board, after: Board, white: Boolean): Int {
            var kingTo = -1
            for (i in 0..63) {
                val a = after[i]
                if (a != '.' && a.isUpperCase() == white && a != before[i]) {
                    if (a.lowercaseChar() != 'k' && a.lowercaseChar() != 'r') return i
                    if (a.lowercaseChar() == 'k') kingTo = i
                    else if (kingTo < 0) kingTo = i
                }
            }
            return kingTo
        }

        /** Grade by the evaluation the mover lost, in pawns from their side. */
        fun grade(before: PvLine, whiteBefore: Boolean, after: PvLine, whiteAfter: Boolean,
                  best: Boolean, square: Int): MoveMark {
            if (best) return MoveMark(square, "★", 0xFF2E9E44.toInt())
            val e0 = expected(before.whiteScore(whiteBefore))
            val e1 = expected(after.whiteScore(whiteAfter))
            // Expected points lost by the mover (White moved if White was to move before).
            val lost = if (whiteBefore) e0 - e1 else e1 - e0
            // Lichess thresholds: a drop in win chance of 5 / 10 / 15 points
            // is an inaccuracy / mistake / blunder.
            return when {
                lost < 0.05 -> MoveMark(square, "✓", 0xFF2E9E44.toInt())
                lost < 0.10 -> MoveMark(square, "?!", 0xFFE0B020.toInt())
                lost < 0.15 -> MoveMark(square, "?", 0xFFE07020.toInt())
                else -> MoveMark(square, "??", 0xFFD02020.toInt())
            }
        }

        /** Win expectation for White from a pawn score (logistic, as in Lichess). */
        fun expected(pawns: Double): Double = 1.0 / (1.0 + Math.exp(-0.368208 * pawns))
    }
}
