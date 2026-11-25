package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Fen
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Orientation
import io.github.chessoverlay.vision.PositionDecoder
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undos, takebacks and a side to move set wrong: the game the tracker keeps
 * must stay the game on the screen. (Cases found by an outside review; each
 * failed before the fix.) Readings are made up: every square read as it is,
 * `p` sure.
 */
class GameMemoryTest {
    private val start = Board.fromPlacement(Board.START_PLACEMENT)
    private fun after(b: Board, vararg m: String) = b.copy().also { x -> m.forEach { x.play(it) } }
    private val corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f))
    private val img = Img(340, 340)

    private fun rec(b: Board, p: Float = 0.97f): Recognition {
        val probs = List(64) { FloatArray(13) { (1f - p) / 12 } }
        val drawn = CharArray(64)
        for (sq in 0 until 64) {
            val d = PositionDecoder.drawnIndex(sq, false)
            probs[d][Fen.classOf(b[sq])] = p
            drawn[d] = b[sq]
        }
        val drawnFen = (0 until 8).joinToString("/") { r -> String(drawn, r * 8, 8) }.replace(Regex("\\.+")) { it.value.length.toString() }
        return Recognition(Orientation.whiteView(drawnFen, false), false, 0.9f, 0.9f, corners, 0, drawn = drawnFen, drawnProbs = probs,
            blackBottomP = 0.1f)
    }

    /** Reads `b` until the tracker takes it (a held reading is read again). */
    private fun see(t: BoardTracker, b: Board, p: Float = 0.97f): BoardTracker.Result {
        var r = t.accept(img, rec(b, p), null, strict = true)
        if (r is BoardTracker.Result.Invalid) r = t.accept(img, rec(b, p), null, strict = true)
        return r
    }

    private fun play(vararg moves: String): Pair<BoardTracker, Board> {
        val t = BoardTracker()
        var b = start.copy()
        t.accept(img, rec(b), null, strict = true)
        for (m in moves) { b = after(b, m); see(t, b) }
        return t to b
    }

    @Test fun anUndoneCaptureBringsThePieceBack() {
        for (p in listOf(0.97f, 0.9999f)) {
            val (t, _) = play("e2e4", "e7e5", "g1f3", "b8c6", "f3e5")
            val back = after(start, "e2e4", "e7e5", "g1f3", "b8c6")
            see(t, back, p)
            assertEquals("the pawn on e5 is back (p=$p)", back.placement(), t.board!!.placement())
            assertTrue("White to move again", t.whiteToMove)
            // And the game goes on from the real position.
            val next = after(back, "d2d4")
            val r = see(t, next, p)
            assertEquals("d2d4", (r as BoardTracker.Result.Position).move)
            assertEquals(next.placement(), t.board!!.placement())
        }
    }

    @Test fun aTakebackOfTwoPliesRestoresCastling() {
        val (t, _) = play("g1f3", "g8f6", "h1g1", "b8c6")
        val back = after(start, "g1f3", "g8f6")
        see(t, back)
        assertEquals(back.placement(), t.board!!.placement())
        assertEquals("KQkq", t.board!!.castling())
        assertTrue(t.whiteToMove)
    }

    @Test fun aRealRepetitionStaysPlayedMoves() {
        // Knights out and back: the same castling rights either way, so the moves count as played (repetitions).
        val (t, _) = play("g1f3", "g8f6", "f3g1", "f6g8")
        assertEquals(start.placement(), t.board!!.placement())
        assertEquals(2, t.repetitions)
    }

    @Test fun aSideSetWrongPutsItselfRight() {
        val t = BoardTracker()
        var b = start.copy()
        t.accept(img, rec(b), null, strict = true)
        b = after(b, "e2e4")
        t.accept(img, rec(b), true, strict = true)   // set to White by hand (wrong), then back to automatic
        val sides = ArrayList<Boolean>()
        for (m in listOf("e7e5", "g1f3", "b8c6", "f1c4")) {
            b = after(b, m)
            see(t, b)
            assertEquals(b.placement(), t.board!!.placement())
            sides += t.whiteToMove
        }
        // After the first move the side follows the game: White to move after Black's moves and so on.
        assertEquals("the side is right from the second move on: $sides", listOf(false, true, false), sides.drop(1))
    }
}
