package io.github.chessoverlay.overlay

import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.AnalysisUpdate
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.FirstLinesGate
import io.github.chessoverlay.engine.LineStabilizer
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import io.github.chessoverlay.vision.plausibleBoard
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    /** What the arrows show (for LineStabilizer): arrows drawn, moves of the best line drawn after the first. */
    var arrows = 2
    var pathPlies = 2

    /**
     * Engine results arrive on the engine thread while new positions start
     * on the worker: both go through this lock, and a result counts only if
     * its request is still the current one, so the lines of one position
     * never appear on the next.
     */
    private val lock = Any()
    private var stabilizer = LineStabilizer(arrows, pathPlies)

    /** null = work it out; true/false = White/Black to move. */
    @Volatile var sideOverride: Boolean? = null

    private val tracker = BoardTracker()
    @Volatile private var state = OverlayState()
    @Volatile private var requestId = -1L
    @Volatile private var best: PvLine? = null          // best line of the current position
    private var pendingGrade: Pending? = null
    private var lostReads = 0
    private val gate = FirstLinesGate<AnalysisUpdate>(MIN_DEPTH, FIRST_LINES_MS)
    private var releaseFor = -1L

    private data class Pending(val move: String, val before: Board, val bestBefore: PvLine, val whiteBefore: Boolean)

    /** Engine results, from the engine thread. */
    fun onEngine(u: AnalysisUpdate): Unit = synchronized(lock) {
        if (u.id != requestId) return
        val top = u.lines.firstOrNull()
        // The first, shallow iterations change their minds every few
        // milliseconds: lines from depth MIN_DEPTH show at once, shallower
        // ones after FIRST_LINES_MS if nothing deeper came (an engine whose
        // depth is capped, e.g. by a strength limit, may stay at depth 1).
        val now = System.currentTimeMillis()
        if (top != null && !gate.offer(u, top.depth, u.finished, now)) {
            if (releaseFor != u.id) {
                releaseFor = u.id
                val id = u.id
                timer.schedule({
                    synchronized(lock) { if (id == requestId) gate.release()?.let { show(it) } }
                }, (gate.releaseAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            }
            return
        }
        show(u)
    }

    private fun show(u: AnalysisUpdate) {
        val s = state
        val top = u.lines.firstOrNull()
        if (top != null) best = top
        var mark = s.lastMove
        val p = pendingGrade
        if (p != null && top != null && (top.depth >= 10 || u.finished)) {
            mark = grade(p, top, s.whiteToMove)
            pendingGrade = null
        }
        // Lines arriving end "Analysing…" and "restarting…"; other messages stay until the next position.
        val passing = s.status == ANALYSING || s.status.startsWith("Engine ")
        val status = if (passing && u.lines.isNotEmpty()) "" else s.status
        val shown = stabilizer.offer(u.lines, u.finished)
        publish(s.copy(status = status, searching = !u.finished, lines = shown, lastMove = mark))
    }

    /** The game on screen; read it only from the thread that calls look(). */
    val board: BoardTracker get() = tracker

    /**
     * Read `img` and analyse if the position is new. `auto` = called by auto
     * mode: failures are logged, not shown, and the last analysis stays.
     */
    @Synchronized
    fun look(img: Img, auto: Boolean, forceLocate: Boolean = false): Look {
        // Known board position on screen: read the squares only, unless most
        // squares look different from the last read (a move changes a few):
        // then the board itself moved, zoomed or turned, and is found again.
        val known = tracker.corners
        val changed = if (known != null) tracker.changedSquares(img) else 64
        var located = forceLocate || known == null || changed >= RELOCATE_SQUARES
        var rec = try {
            recognizer.recognise(img, knownCorners = if (located) null else known)
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognition failed", e)
            if (!auto) publish(state.copy(status = "Recognition failed: ${e.message}"))
            return Look.FAILED
        }
        DebugLog.i(TAG, "read ${if (located) "after locating" else "at known corners"} ($changed squares changed): " +
            "${rec.placement} ${if (rec.blackAtBottom) "black" else "white"} at bottom, sure ${(rec.confidence * 100).toInt()}%, " +
            "corners ${rec.corners.joinToString(" ") { "${it[0].toInt()},${it[1].toInt()}" }}")
        if (rec.confidence < minConfidence && !located) {
            rec = try { recognizer.recognise(img) } catch (e: Exception) { rec }
            located = true
        }
        if (located && !plausibleBoard(rec.corners, img.w, img.h)) {
            // Not square, or tiny: something else was taken for a board.
            DebugLog.i(TAG, "located shape is no board")
            rec = rec.copy(confidence = 0f)
        }
        if (rec.confidence < minConfidence) {
            DebugLog.i(TAG, "no clear board (${(rec.confidence * 100).toInt()}%)")
            // Three times in a row in auto mode: the board has gone (another
            // screen); drop it, so no arrows hang over something else.
            if (auto && tracker.corners != null && ++lostReads >= 3) {
                lostReads = 0
                tracker.reset()
                synchronized(lock) {
                    requestId = -1
                    engine.stop()
                    publish(OverlayState(status = "Board gone; looking for it…"))
                }
            }
            if (!auto) publish(state.copy(status = "No clear board found (${(rec.confidence * 100).toInt()}% sure)"))
            return Look.NO_BOARD
        }
        lostReads = 0
        var r = tracker.accept(img, rec, sideOverride, strict = auto)
        if (r is BoardTracker.Result.Invalid && !located) {
            // Impossible at the old corners: maybe the board is somewhere else
            // now (a site's opening animation, a scroll). Look for it before
            // calling this a misread.
            val again = try { recognizer.recognise(img) } catch (e: Exception) { null }
            if (again != null && again.confidence >= minConfidence && plausibleBoard(again.corners, img.w, img.h)) {
                DebugLog.i(TAG, "found again at ${again.corners.joinToString(" ") { "${it[0].toInt()},${it[1].toInt()}" }}: ${again.placement}")
                r = tracker.accept(img, again, sideOverride, strict = auto)
                rec = again
            }
        }
        return when (r) {
            is BoardTracker.Result.Same -> {
                // Same position, but the board may have been turned round or moved a little.
                synchronized(lock) {
                    val s = state
                    if (s.board != null && (s.blackAtBottom != tracker.blackAtBottom || moved(s.corners, tracker.corners))) {
                        publish(s.copy(blackAtBottom = tracker.blackAtBottom, corners = tracker.corners))
                    }
                }
                if (!auto && state.board == null) start(tracker.board!!, tracker.whiteToMove, null, null, true)
                else if (!auto && state.status.isNotEmpty() && state.status != ANALYSING) publish(state.copy(status = ""))
                Look.SAME
            }
            is BoardTracker.Result.Invalid -> {
                // Almost always a misread (a piece caught mid-animation, a
                // dragged piece): in auto mode keep the last analysis and
                // let the next check try again.
                DebugLog.i(TAG, "not analysable: ${r.reason} (${rec.placement})")
                if (!auto) publish(state.copy(status = "Board misread (${r.reason}); try ▶ again"))
                Look.INVALID
            }
            is BoardTracker.Result.Position -> {
                start(r.board, r.whiteToMove, r.move, r.previous, r.previousWhiteToMove)
                Look.NEW
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

    private fun start(board: Board, white: Boolean, move: String?, previous: Board?, previousWhite: Boolean): Unit = synchronized(lock) {
        stabilizer = LineStabilizer(arrows, pathPlies)
        val bestBefore = best
        pendingGrade = if (annotate && move != null && previous != null && bestBefore != null &&
            previousWhite != white) Pending(move, previous, bestBefore, previousWhite) else null
        best = null
        gate.start(System.currentTimeMillis())
        val fen = board.fen(white)
        val over = board.outcome(white)
        if (over != null) {
            // No move to search for: say so instead of asking the engine.
            DebugLog.i(TAG, "$over: $fen" + (move?.let { " after $it" } ?: ""))
            requestId = -1
            pendingGrade = null
            engine.stop()
            publish(OverlayState(status = over, corners = tracker.corners, blackAtBottom = tracker.blackAtBottom,
                board = board, whiteToMove = white, playedMove = move))
            return
        }
        DebugLog.i(TAG, "analyse $fen" + (move?.let { " after $it" } ?: ""))
        publish(OverlayState(
            status = ANALYSING,
            searching = true,
            corners = tracker.corners,
            blackAtBottom = tracker.blackAtBottom,
            board = board,
            whiteToMove = white,
            playedMove = move,
        ))
        requestId = engine.analyse(fen, lines, thinkMs)
    }

    fun stop() = engine.stop()

    /** Drop the board (it moved: the screen turned); show `why` until it is found again. */
    fun forget(why: String) {
        tracker.reset()
        synchronized(lock) {
            requestId = -1
            engine.stop()
            publish(OverlayState(status = why))
        }
    }

    fun status(s: String) = synchronized(lock) { publish(state.copy(status = s)) }

    private fun publish(s: OverlayState) {
        state = s
        onState(s)
    }

    private fun moved(a: List<FloatArray>?, b: List<FloatArray>?): Boolean =
        a == null || b == null || a.indices.any { kotlin.math.abs(a[it][0] - b[it][0]) > 2 || kotlin.math.abs(a[it][1] - b[it][1]) > 2 }

    private fun grade(p: Pending, after: PvLine, whiteNow: Boolean): MoveMark {
        val sq = Board.squareOf(p.move.substring(2, 4))
        val playedBest = p.bestBefore.pv.firstOrNull() == p.move
        return grade(p.bestBefore, p.whiteBefore, after, whiteNow, playedBest, sq)
    }

    companion object {
        private const val TAG = "Analyst"
        const val ANALYSING = OverlayState.ANALYSING
        private const val MIN_DEPTH = 8
        /** Squares that differ from the last read beyond which the board itself moved (a move changes 2 to 8). */
        private const val RELOCATE_SQUARES = 12
        private const val FIRST_LINES_MS = 300L

        /** Shows held first lines when their wait is over (one daemon thread for all). */
        private val timer by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "first-lines").apply { isDaemon = true } }
        }

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

        /** Win expectation for White from a pawn score (see [winChance]). */
        fun expected(pawns: Double): Double = winChance(pawns)
    }
}
