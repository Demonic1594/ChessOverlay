package io.github.chessoverlay.overlay

import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.AnalysisUpdate
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
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
    /** The move just played (UCI), when known: its squares are highlighted. */
    val playedMove: String? = null,
)

/** A grade for the move just played, drawn on its destination square. */
data class MoveMark(val square: Int, val label: String, val color: Int)

/**
 * Screen image -> board (BoardTracker) -> engine (EngineHost) -> OverlayState.
 * Called from one worker thread; engine updates arrive on the engine thread
 * and are matched to the position they belong to by request id.
 */
class Analyst(
    private val recognizer: Recognizer,
    private val engine: EngineHost,
    private val onState: (OverlayState) -> Unit,
) {
    var lines = 3
    var thinkMs = 0L
    var minConfidence = 0.5f
    var annotate = true

    /** null = work it out; true/false = White/Black to move. */
    @Volatile var sideOverride: Boolean? = null

    private val tracker = BoardTracker()
    @Volatile private var state = OverlayState()
    @Volatile private var requestId = -1L
    @Volatile private var best: PvLine? = null          // best line of the current position
    private var pendingGrade: Pending? = null

    private data class Pending(val move: String, val before: Board, val bestBefore: PvLine, val whiteBefore: Boolean)

    /** Engine results, from the engine thread. */
    fun onEngine(u: AnalysisUpdate) {
        if (u.id != requestId) return
        val s = state
        val top = u.lines.firstOrNull()
        if (top != null) best = top
        var mark = s.lastMove
        val p = pendingGrade
        if (p != null && top != null && (top.depth >= 10 || u.finished)) {
            mark = grade(p, top, s.whiteToMove)
            pendingGrade = null
        }
        val depth = top?.depth ?: 0
        publish(s.copy(status = "Depth $depth${if (u.finished) "" else "…"}", lines = u.lines, lastMove = mark))
    }

    /** True if the board area changed since the last accepted position. */
    fun boardChanged(img: Img): Boolean = tracker.changed(img)

    /**
     * Read `img` and analyse if the position is new. `auto` = called by auto
     * mode (quiet about failures). Returns true if a new analysis started.
     */
    @Synchronized
    fun look(img: Img, auto: Boolean, forceLocate: Boolean = false): Boolean {
        // Known board position on screen: read the squares only; if that is
        // not clearly a board any more, locate it again.
        var rec = try {
            recognizer.recognise(img, knownCorners = if (forceLocate) null else tracker.corners)
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognition failed", e)
            if (!auto) publish(state.copy(status = "Recognition failed: ${e.message}"))
            return false
        }
        if (rec.confidence < minConfidence && tracker.corners != null && !forceLocate) {
            rec = try { recognizer.recognise(img) } catch (e: Exception) { rec }
        }
        if (rec.confidence < minConfidence) {
            DebugLog.i(TAG, "no clear board (${(rec.confidence * 100).toInt()}%)")
            if (!auto) publish(OverlayState(status = "No clear board found (${(rec.confidence * 100).toInt()}% sure)"))
            return false
        }
        return when (val r = tracker.accept(img, rec, sideOverride)) {
            is BoardTracker.Result.Same -> {
                if (!auto && state.board == null) start(tracker.board!!, tracker.whiteToMove, null, null, true)
                false
            }
            is BoardTracker.Result.Invalid -> {
                DebugLog.i(TAG, "not analysable: ${r.reason} (${rec.placement})")
                publish(OverlayState(status = "Not a legal position: ${r.reason}", corners = rec.corners,
                    blackAtBottom = rec.blackAtBottom))
                false
            }
            is BoardTracker.Result.Position -> {
                start(r.board, r.whiteToMove, r.move, r.previous, r.previousWhiteToMove)
                true
            }
        }
    }

    /** Analyse the current position again (side to move changed by hand). */
    @Synchronized
    fun reanalyse() {
        val b = tracker.board ?: return
        val white = sideOverride?.takeIf { b.problem(it) == null } ?: tracker.whiteToMove
        start(b, white, null, null, white)
    }

    private fun start(board: Board, white: Boolean, move: String?, previous: Board?, previousWhite: Boolean) {
        val bestBefore = best
        pendingGrade = if (annotate && move != null && previous != null && bestBefore != null &&
            previousWhite != white) Pending(move, previous, bestBefore, previousWhite) else null
        best = null
        val fen = board.fen(white)
        DebugLog.i(TAG, "analyse $fen" + (move?.let { " after $it" } ?: ""))
        publish(OverlayState(
            status = "Analysing…",
            corners = tracker.corners,
            blackAtBottom = tracker.blackAtBottom,
            board = board,
            whiteToMove = white,
            playedMove = move,
        ))
        requestId = engine.analyse(fen, lines, thinkMs)
    }

    fun stop() = engine.stop()

    fun status(s: String) = publish(state.copy(status = s))

    private fun publish(s: OverlayState) {
        state = s
        onState(s)
    }

    private fun grade(p: Pending, after: PvLine, whiteNow: Boolean): MoveMark {
        val sq = Board.squareOf(p.move.substring(2, 4))
        val playedBest = p.bestBefore.pv.firstOrNull() == p.move
        return grade(p.bestBefore, p.whiteBefore, after, whiteNow, playedBest, sq)
    }

    companion object {
        private const val TAG = "Analyst"

        /** Grade by the win chance the mover lost (Lichess thresholds). */
        fun grade(before: PvLine, whiteBefore: Boolean, after: PvLine, whiteAfter: Boolean,
                  best: Boolean, square: Int): MoveMark {
            if (best) return MoveMark(square, "★", 0xFF2E9E44.toInt())
            val e0 = expected(before.whiteScore(whiteBefore))
            val e1 = expected(after.whiteScore(whiteAfter))
            val lost = if (whiteBefore) e0 - e1 else e1 - e0
            // A drop in win chance of 5 / 10 / 15 points is an inaccuracy /
            // mistake / blunder.
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
