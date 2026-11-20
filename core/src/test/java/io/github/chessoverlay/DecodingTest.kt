package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Fen
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Orientation
import io.github.chessoverlay.vision.PositionDecoder
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading boards in the light of the game: orientation, misread squares, turned boards. */
class DecodingTest {
    private val start = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    private fun after(b: Board, vararg m: String) = b.copy().also { x -> m.forEach { x.play(it) } }

    /**
     * What the model would say about `b` drawn with `black` at the bottom:
     * each square 97% sure, except `wrong`: square name -> (piece read, its probability).
     */
    private fun rec(b: Board, black: Boolean, pBlack: Float = if (black) 0.9f else 0.1f,
                    wrong: Map<String, Pair<Char, Float>> = emptyMap()): Recognition {
        val probs = List(64) { FloatArray(13) { 0.03f / 12 } }
        val drawn = CharArray(64)
        for (sq in 0 until 64) {
            val d = PositionDecoder.drawnIndex(sq, black)
            val truth = Fen.classOf(b[sq])
            probs[d][truth] = 0.97f
            drawn[d] = b[sq]
            wrong[Board.squareName(sq)]?.let { (piece, p) ->
                val c = Fen.classOf(piece)
                probs[d].fill((1f - p - 0.02f) / 11); probs[d][c] = p; probs[d][truth] = 0.02f
                drawn[d] = piece
            }
        }
        val drawnFen = (0 until 8).joinToString("/") { r -> String(drawn, r * 8, 8) }
            .replace(Regex("\\.+")) { it.value.length.toString() }
        return Recognition(Orientation.whiteView(drawnFen, pBlack > 0.5f), pBlack > 0.5f, 0.9f,
            maxOf(pBlack, 1 - pBlack), corners, 0, drawn = drawnFen, drawnProbs = probs, blackBottomP = pBlack)
    }

    private val corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f))
    private val img = Img(340, 340)

    @Test fun orientationFromThePosition() {
        val p = after(start, "e2e4", "e7e5", "g1f3", "b8c6")
        val drawnBlack = Orientation.whiteView(p.placement(), true)   // drawn with Black at the bottom
        // A model leaning the wrong way (60%) is overruled by the position.
        assertTrue(Orientation.decide(drawnBlack, pBlackBottom = 0.4f))
        assertFalse(Orientation.decide(p.placement(), pBlackBottom = 0.6f))
        // A sure model is kept when the position says little (bare kings).
        assertTrue(Orientation.decide("8/8/8/3K4/8/3k4/8/8", 0.99f))
        // The pawn-and-king strain is lower the right way round.
        assertTrue(Orientation.strain(p.placement()) < Orientation.strain(Fen.flip(p.placement())))
    }

    @Test fun aMisreadSquareCannotMakeAnImpossiblePosition() {
        val prev = after(start, "e2e4", "e7e5")
        val now = after(prev, "g1f3")
        // The model reads the f3 knight as a bishop (60%), a move no piece can make.
        val r = PositionDecoder.decode(prev, true, false, rec(now, false, wrong = mapOf("f3" to ('B' to 0.6f))))
        assertNotNull(r)
        assertEquals(now.placement(), r!!.board.placement())
        assertEquals(listOf("g1f3"), r.moves)
        // And a piece "appearing" on an untouched square is dropped too.
        val r2 = PositionDecoder.decode(prev, true, false, rec(now, false, wrong = mapOf("a6" to ('p' to 0.55f))))!!
        assertEquals(now.placement(), r2.board.placement())
    }

    @Test fun boardTurnedRoundIsTheSameGame() {
        val prev = after(start, "e2e4", "c7c5", "g1f3")
        // The user presses "flip": same position, drawn the other way; the model unsure.
        val r = PositionDecoder.decode(prev, false, false, rec(prev, true, pBlack = 0.55f))!!
        assertTrue(r.blackAtBottom)
        assertEquals(prev.placement(), r.board.placement())
        assertEquals(emptyList<String>(), r.moves)
        // A site that turns the board after every move: turned and one move on.
        val next = after(prev, "d7d6")
        val r2 = PositionDecoder.decode(prev, false, true, rec(next, false, pBlack = 0.5f))!!
        assertFalse(r2.blackAtBottom)
        assertEquals(listOf("d7d6"), r2.moves)
    }

    @Test fun somethingTheGameCannotReachIsLeftToThePlainReading() {
        val prev = after(start, "e2e4", "e7e5")
        val other = Board.fromPlacement("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R")  // three moves later
        assertNull(PositionDecoder.decode(prev, true, false, rec(other, false)))
    }

    @Test fun undoIsNotMistakenForNoMove() {
        val prev = after(start, "e2e4", "e7e5", "g1f3")
        // Undo of Nf3 looks exactly like the knight going back to g1: the
        // board is right (who is to move cannot be seen; ⇅ sets it).
        val back = PositionDecoder.decode(prev, false, false, rec(after(start, "e2e4", "e7e5"), false))!!
        assertEquals(after(start, "e2e4", "e7e5").placement(), back.board.placement())
        // Undo of a pawn move (pawns never go back): no explanation, the picture is used.
        assertNull(PositionDecoder.decode(after(start, "e2e4", "e7e5"), true, false, rec(after(start, "e2e4"), false)))
        // Back two moves: no forward move either.
        assertNull(PositionDecoder.decode(prev, false, false, rec(after(start, "e2e4"), false)))
    }

    @Test fun aPieceHiddenUnderASiteArrowDoesNotVanish() {
        val prev = after(start, "e2e4", "e7e5", "g1f3")
        // No move; the site draws an arrow over the f3 knight, read (99% sure) as empty.
        val r = PositionDecoder.decode(prev, false, false, rec(prev, false, wrong = mapOf("f3" to ('.' to 0.99f))))!!
        assertEquals(prev.placement(), r.board.placement())
        assertEquals(emptyList<String>(), r.moves)
    }

    @Test fun trackerFollowsATurnedBoardAndKeepsTheSideToMove() {
        val t = BoardTracker()
        t.accept(img, rec(start, false), null, strict = true)
        val p0 = after(start, "e2e4")
        t.accept(img, rec(p0, false), null, strict = true)
        assertFalse(t.whiteToMove)
        // Black replies and the site turns the board for Black.
        val p1 = after(p0, "c7c5")
        val r = t.accept(img, rec(p1, true, pBlack = 0.5f), null, strict = true) as BoardTracker.Result.Position
        assertTrue(t.blackAtBottom)
        assertEquals("c7c5", r.move)
        assertTrue(r.whiteToMove)
        // The user turns it back by hand: nothing else changes.
        assertEquals(BoardTracker.Result.Same, t.accept(img, rec(p1, false, pBlack = 0.45f), null, strict = true))
        assertFalse(t.blackAtBottom)
        assertTrue(t.whiteToMove)
    }

    @Test fun legalMovesKnowTheRules() {
        assertEquals(20, start.legalMoves(true).size)
        assertEquals(20, start.legalMoves(false).size)
        // Pinned knight; castling only through empty squares.
        val b = Board.fromPlacement("4r1k1/8/8/8/8/8/4N3/R3K2R")
        val m = b.legalMoves(true)
        assertTrue(m.none { it.startsWith("e2") })
        assertTrue("e1g1" in m && "e1c1" in m)
        assertFalse("e1c1" in Board.fromPlacement("4k3/8/8/8/8/8/8/RN2K3").legalMoves(true))
        // En passant only past an enemy pawn.
        assertTrue("e5d6" in Board.fromPlacement("4k3/8/8/3pP3/8/8/8/4K3").legalMoves(true))
        assertFalse("e5d6" in Board.fromPlacement("4k3/8/8/4P3/8/8/8/4K3").legalMoves(true))
    }
}

class PlausibleBoardTest {
    private fun box(x0: Float, y0: Float, x1: Float, y1: Float) =
        listOf(floatArrayOf(x0, y0), floatArrayOf(x1, y0), floatArrayOf(x1, y1), floatArrayOf(x0, y1))

    @Test fun onlyNearSquaresOfSomeSize() {
        assertTrue(io.github.chessoverlay.vision.plausibleBoard(box(0f, 854f, 1440f, 2294f), 1440, 3120))
        assertTrue(io.github.chessoverlay.vision.plausibleBoard(box(100f, 100f, 400f, 410f), 1080, 2400))   // 3% off
        assertFalse(io.github.chessoverlay.vision.plausibleBoard(box(0f, 0f, 1440f, 700f), 1440, 3120))    // a wide strip
        assertFalse(io.github.chessoverlay.vision.plausibleBoard(box(10f, 10f, 90f, 90f), 1440, 3120))     // a tiny square
    }
}
