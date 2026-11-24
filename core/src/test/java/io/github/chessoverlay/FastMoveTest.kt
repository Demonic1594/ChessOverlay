package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moves seen at once: from which squares changed (with a site's last-move
 * highlight, the overlay's arrows over some squares, a quick reply), the
 * tracker names the move before the recognizer runs, and the recognizer's
 * reading then confirms or corrects it.
 */
class FastMoveTest {
    private val sq = 40
    private val corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f))
    private val start = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")

    /** A board as a site draws it; `highlight` squares (the last move) are tinted. */
    private fun render(b: Board, highlight: Set<Int> = emptySet(), black: Boolean = false): Img {
        val img = Img(8 * sq + 20, 8 * sq + 20)
        for (row in 0 until 8) for (col in 0 until 8) for (y in 0 until sq) for (x in 0 until sq) {
            val square = if (black) row * 8 + (7 - col) else (7 - row) * 8 + col
            val p = b[square]
            val inPiece = p != '.' && x in sq / 4 until 3 * sq / 4 && y in sq / 4 until 3 * sq / 4
            val light = (row + col) % 2 == 0
            img.px[(10 + row * sq + y) * img.w + 10 + col * sq + x] = when {
                inPiece && p.isUpperCase() -> 0xFFFFFF - "PNBRQK".indexOf(p) * 0x0A0A0A
                inPiece -> 0x101010 + "pnbrqk".indexOf(p) * 0x0A0A0A
                square in highlight -> if (light) 0xF6F669 else 0xBACA2B
                light -> 0xEED8B5
                else -> 0xB58863
            }
        }
        return img
    }

    private fun squares(m: String) = setOf(Board.squareOf(m.substring(0, 2)), Board.squareOf(m.substring(2, 4)))
    private fun rec(b: Board, black: Boolean = false) = Recognition(b.placement(), black, 0.99f, 0.99f, corners, 0)
    private fun after(b: Board, vararg moves: String) = b.copy().also { c -> moves.forEach { c.play(it) } }

    /** The tracker following `b` as read cleanly from `img`. */
    private fun tracker(vararg steps: Pair<Board, Img>, black: Boolean = false): BoardTracker {
        val t = BoardTracker()
        for ((b, img) in steps) t.accept(img, rec(b, black), null, strict = true)
        return t
    }

    private fun seen(t: BoardTracker, img: Img, cover: BoardTracker.Cover? = null): List<String>? {
        val (xs, ys) = t.points()!!
        return t.explainChange(BoardTracker.sample(img, xs, ys, 0, 0), cover)
    }

    @Test fun firstMoveWithoutHighlights() {
        val t = tracker(start to render(start))
        assertEquals(listOf("e2e4"), seen(t, render(after(start, "e2e4"))))
    }

    @Test fun theLastMovesHighlightGoingAwayIsExplained() {
        val e4 = after(start, "e2e4")
        val t = tracker(start to render(start), e4 to render(e4, squares("e2e4")))
        assertEquals("e2e4", t.lastMove)
        val e5 = after(e4, "e7e5")
        assertEquals(listOf("e7e5"), seen(t, render(e5, squares("e7e5"))))
    }

    @Test fun aRecaptureOnTheLastMovesSquare() {
        // 1.e4 d5 2.exd5 Qxd5: the queen takes on the square the pawn just went to.
        val p = after(start, "e2e4", "d7d5")
        val q = after(p, "e4d5")
        val t = tracker(p to render(p, squares("d7d5")), q to render(q, squares("e4d5")))
        assertEquals(listOf("d8d5"), seen(t, render(after(q, "d8d5"), squares("d8d5"))))
    }

    @Test fun castlingAndBlackAtTheBottom() {
        val p = Board.fromPlacement("r3k2r/pppq1ppp/2npbn2/2b1p3/2B1P3/2NPBN2/PPPQ1PPP/R3K2R")
        val t = tracker(p to render(p, black = true), black = true)
        assertTrue(t.blackAtBottom)
        assertTrue("first read: the side at the bottom moves", !t.whiteToMove)
        assertEquals(listOf("e8g8"), seen(t, render(after(p, "e8g8"), squares("e8g8"), black = true)))
    }

    @Test fun aQuickReplyIsSeenWithTheMove() {
        // Black premoved: both moves land within one stillness wait.
        val t = tracker(start to render(start))
        val both = after(start, "e2e4", "c7c5")
        assertEquals(listOf("e2e4", "c7c5"), seen(t, render(both, squares("c7c5"))))
    }

    @Test fun squaresUnderTheArrowsCanHideOneEnd() {
        val e4 = after(start, "e2e4", "e7e5")
        val t = tracker(e4 to render(e4))
        // The overlay's arrow covers g1 completely; f3 is seen.
        val g1 = Board.squareOf("g1")
        val cover = BoardTracker.Cover { x, y -> (x - 10) / sq == g1 % 8 && (y - 10) / sq == 7 - g1 / 8 }
        assertEquals(listOf("g1f3"), seen(t, render(after(e4, "g1f3")), cover))
    }

    @Test fun twoMovesThatCouldExplainItAreLeftToTheRecognizer() {
        // Knights on c3 and g5 can both reach e4; both origins are hidden under the arrows.
        val p = Board.fromPlacement("rnbqkb1r/pppp1ppp/5n2/4p1N1/8/2N5/PPPPPPPP/R1BQKB1R")
        val t = tracker(p to render(p))
        val c3 = Board.squareOf("c3"); val g5 = Board.squareOf("g5")
        val cover = BoardTracker.Cover { x, y ->
            val s = (7 - (y - 10) / sq) * 8 + (x - 10) / sq
            s == c3 || s == g5
        }
        assertNull(seen(t, render(after(p, "c3e4")), cover))
    }

    @Test fun nothingAMoveExplainsIsLeftToTheRecognizer() {
        val t = tracker(start to render(start))
        // A piece vanished (a misread screen, a dialog): no legal move does that.
        assertNull(seen(t, render(Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPP1PPP/RNBQKBNR"))))
        // Every square changed colour (a new theme): not a move.
        val img = render(start)
        for (i in img.px.indices) img.px[i] = img.px[i] xor 0x202020
        assertNull(seen(t, img))
    }

    @Test fun theRecognizerConfirmsOrCorrects() {
        val e4 = after(start, "e2e4")
        val t = tracker(start to render(start))
        val r = t.applyFast(listOf("e2e4"))
        assertEquals(e4.placement(), r.board.placement())
        assertTrue(!t.whiteToMove)
        // Confirmed: the reading gives the same position, judged from the one before.
        val ok = t.accept(render(e4, squares("e2e4")), rec(e4), null, strict = true) as BoardTracker.Result.Position
        assertEquals("e2e4", ok.move)
        assertEquals(e4.placement(), t.board!!.placement())

        // Wrong guess: the reading says another move; the game follows the reading.
        val t2 = tracker(start to render(start))
        t2.applyFast(listOf("e2e4"))
        val d4 = after(start, "d2d4")
        val fixed = t2.accept(render(d4, squares("d2d4")), rec(d4), null, strict = true) as BoardTracker.Result.Position
        assertEquals("d2d4", fixed.move)
        assertEquals(start.placement(), fixed.previous!!.placement())
        assertTrue(!t2.whiteToMove)

        // A capture thrown away: back to the position before.
        val t3 = tracker(start to render(start))
        t3.applyFast(listOf("e2e4"))
        t3.abandonFast()
        assertEquals(start.placement(), t3.board!!.placement())
        assertTrue(t3.whiteToMove)
    }

    @Test fun theGameIsKeptWhileTheBoardIsAway() {
        val p = after(start, "e2e4")
        val t = tracker(start to render(start), p to render(p, squares("e2e4")))
        assertTrue(!t.whiteToMove)
        t.reset(keepGame = true)        // a notification covered the board
        assertNull(t.points())
        assertEquals(BoardTracker.Result.Same, t.accept(render(p, squares("e2e4")), rec(p), null, strict = true))
        assertTrue("still Black to move", !t.whiteToMove)
        // Meanwhile Black replied: one move from the kept position.
        t.reset(keepGame = true)
        val q = after(p, "c7c5")
        val r = t.accept(render(q, squares("c7c5")), rec(q), null, strict = true) as BoardTracker.Result.Position
        assertEquals("c7c5", r.move)
        assertTrue(t.whiteToMove)
    }
}
