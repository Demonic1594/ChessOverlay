package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.LastMoveHighlight
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who moved last, from the site's last-move highlight: whichever colour
 * the tint, whichever side at the bottom, wherever the move, and nothing
 * assumed without a clear highlight.
 */
class HighlightTest {
    private val sq = 40
    private val corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f))
    private val start = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    private val yellow = 0xF6F669 to 0xBACA2B
    private val blue = 0x9BC7E8 to 0x5A8FB8

    /**
     * A site's board: pieces cover the middle of their square (queens and
     * kings nearly all of it); `tint` (light, dark) on
     * the `highlight` squares, `marks` in red.
     */
    private fun render(b: Board, highlight: Set<Int> = emptySet(), black: Boolean = false,
                       tint: Pair<Int, Int> = yellow, marks: Set<Int> = emptySet()): Img {
        val img = Img(8 * sq + 20, 8 * sq + 20)
        for (row in 0 until 8) for (col in 0 until 8) for (y in 0 until sq) for (x in 0 until sq) {
            val square = if (black) row * 8 + (7 - col) else (7 - row) * 8 + col
            val p = b[square]
            val body = x in sq / 4 until 3 * sq / 4 && y in sq / 5 until 4 * sq / 5
            // A wide piece (a queen or king as chess.com draws it) comes within 9% of the edges.
            val wide = p.lowercaseChar() in "qk" && x in sq * 9 / 100 until sq * 91 / 100 && y in sq * 9 / 100 until sq * 91 / 100
            val base = wide || (x in sq / 6 until 5 * sq / 6 && y in 3 * sq / 4 until 6 * sq / 7)
            val light = (row + col) % 2 == 0
            img.px[(10 + row * sq + y) * img.w + 10 + col * sq + x] = when {
                p != '.' && (body || base) -> if (p.isUpperCase()) 0xF8F8F8 else 0x202020
                square in marks -> if (light) 0xF06060 else 0xC04040
                square in highlight -> if (light) tint.first else tint.second
                light -> 0xEEEED2
                else -> 0x769656
            }
        }
        return img
    }

    private fun squares(m: String) = setOf(Board.squareOf(m.substring(0, 2)), Board.squareOf(m.substring(2, 4)))
    private fun after(b: Board, vararg moves: String) = b.copy().also { c -> moves.forEach { c.play(it) } }
    private fun rec(b: Board, black: Boolean = false) = Recognition(b.placement(), black, 0.99f, 0.99f, corners, 0)
    private fun seen(img: Img, b: Board, black: Boolean = false) = LastMoveHighlight.find(img, corners, b, black)

    @Test fun eitherSideAndAnyTint() {
        val e4 = after(start, "e2e4")
        assertEquals(LastMoveHighlight.Seen("e2e4", true), seen(render(e4, squares("e2e4")), e4))
        val e5 = after(e4, "e7e5")
        assertEquals(LastMoveHighlight.Seen("e7e5", false), seen(render(e5, squares("e7e5")), e5))
        assertEquals("a blue highlight", LastMoveHighlight.Seen("e7e5", false), seen(render(e5, squares("e7e5"), tint = blue), e5))
        assertEquals("Black at the bottom", LastMoveHighlight.Seen("e7e5", false), seen(render(e5, squares("e7e5"), black = true), e5, black = true))
        // A capture by a wide piece (its base reaches the ring), and castling.
        val p = Board.fromPlacement("r3k2r/pppq1ppp/2npbn2/2b1p3/2B1P3/2NPBN2/PPPQ1PPP/R3K2R")
        val oo = after(p, "e1g1")
        assertEquals(LastMoveHighlight.Seen("e1g1", true), seen(render(oo, squares("e1g1")), oo))
        val x = after(p, "f3e5")
        assertEquals(LastMoveHighlight.Seen("f3e5", true), seen(render(x, squares("f3e5")), x))
    }

    @Test fun nothingClearMeansNothingAssumed() {
        val e5 = after(start, "e2e4", "e7e5")
        assertNull("no highlight", seen(render(e5), e5))
        assertNull("a premove or the user's marks as well", seen(render(e5, squares("e7e5"), marks = setOf(Board.squareOf("d4"))), e5))
        // Two tinted squares that no piece could have moved between (not a last move).
        assertNull(seen(render(e5, setOf(Board.squareOf("e5"), Board.squareOf("a3"))), e5))
        assertNull("both empty", seen(render(e5, setOf(Board.squareOf("a3"), Board.squareOf("h6"))), e5))
    }

    @Test fun theFirstReadTakesTheSideFromTheHighlight() {
        // Opened mid-game with Black at the bottom, and Black has just moved: White to move.
        val p = after(start, "e2e4", "c7c5", "g1f3", "d7d6")
        val t = BoardTracker()
        t.accept(render(p, squares("d7d6"), black = true), rec(p, true), null, strict = true)
        assertTrue(t.blackAtBottom)
        assertTrue("the side at the bottom would have been guessed; the highlight says White", t.whiteToMove)
        assertEquals("d7d6", t.lastMove)
    }

    @Test fun steppingBackThroughTheMoveList() {
        // 1.e4 e5 2.Nf3 followed live; then the user steps back one move.
        val e5 = after(start, "e2e4", "e7e5")
        val nf3 = after(e5, "g1f3")
        val t = BoardTracker()
        t.accept(render(start), rec(start), null, strict = true)
        t.accept(render(after(start, "e2e4"), squares("e2e4")), rec(after(start, "e2e4")), null, strict = true)
        t.accept(render(e5, squares("e7e5")), rec(e5), null, strict = true)
        t.accept(render(nf3, squares("g1f3")), rec(nf3), null, strict = true)
        assertFalse(t.whiteToMove)
        // Back one move: the knight goes home, and the site highlights 1...e5 again.
        // (A change no move explains is read twice before it counts, as any step back.)
        assertTrue(t.accept(render(e5, squares("e7e5")), rec(e5), null, strict = true) is BoardTracker.Result.Invalid)
        val r = t.accept(render(e5, squares("e7e5")), rec(e5), null, strict = true) as BoardTracker.Result.Position
        assertTrue("White to move in the position after 1...e5", r.whiteToMove)
        // Forward again: Nf3, Black to move.
        val f = t.accept(render(nf3, squares("g1f3")), rec(nf3), null, strict = true) as BoardTracker.Result.Position
        assertFalse(f.whiteToMove)
    }

    @Test fun aWrongGuessIsPutRightOnTheSamePosition() {
        // First read without a highlight: White at the bottom, White guessed to move;
        // then the highlight shows Black's last move: the guess was right, nothing changes.
        val p = after(start, "d2d4", "g8f6")
        val t = BoardTracker()
        t.accept(render(p), rec(p), null, strict = true)
        assertTrue(t.whiteToMove)
        assertEquals(BoardTracker.Result.Same, t.accept(render(p, squares("g8f6")), rec(p), null, strict = true))
        // Black at the bottom: Black guessed to move, but the highlight shows ...Nf6: White to move.
        val t2 = BoardTracker()
        t2.accept(render(p, black = true), rec(p, true), null, strict = true)
        assertFalse("guessed: the side at the bottom", t2.whiteToMove)
        val r = t2.accept(render(p, squares("g8f6"), black = true), rec(p, true), null, strict = true)
        assertTrue("put right: $r", r is BoardTracker.Result.Position && r.whiteToMove)
    }

    @Test fun theQueenCheckFromThePhone() {
        // 23...Qh2-g3+ on chess.com: the queen nearly fills g3, the king fills e1.
        val p = Board.fromPlacement("r4rk1/p4p2/2p2p1p/Q7/2P1p1b1/8/PB1PB1Pq/1R2KR2")
        val q = after(p, "h2g3")
        assertEquals(LastMoveHighlight.Seen("h2g3", false), seen(render(q, squares("h2g3")), q))
    }

    @Test fun aHighlightedTwoSquarePawnMoveGivesTheEnPassantSquare() {
        // Joined late after 1.e4 Nf6 2.e5 d5: exd6 en passant is possible, which no picture alone shows.
        val p = after(start, "e2e4", "g8f6", "e4e5", "d7d5")
        val t = BoardTracker()
        t.accept(render(p, squares("d7d5")), rec(p), null, strict = true)
        assertTrue(t.whiteToMove)
        assertTrue(t.board!!.fen(true), t.board!!.fen(true).contains(" d6 "))
        // Without the highlight: unknown, so no en passant square.
        val t2 = BoardTracker()
        t2.accept(render(p), rec(p), null, strict = true)
        assertFalse(t2.board!!.fen(true).contains(" d6 "))
    }
}
