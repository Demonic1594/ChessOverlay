package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvCollector
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.CardControls
import io.github.chessoverlay.overlay.CardLayer
import io.github.chessoverlay.overlay.CardStyle
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.overlay.Shape
import io.github.chessoverlay.overlay.TextMeasure
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixes for lines that flickered and fast moves that were misread. */
class SteadinessTest {
    private fun pv(rank: Int, depth: Int, move: String) = PvLine(rank, depth, 10, null, listOf(move))

    @Test fun engineLinesArriveAsWholeIterations() {
        val c = PvCollector(3)
        assertNull(c.add(pv(1, 10, "e2e4")))
        assertNull(c.add(pv(2, 10, "d2d4")))
        val ten = c.add(pv(3, 10, "g1f3"))!!
        assertEquals(listOf(10, 10, 10), ten.map { it.depth })
        // Depth 11 rank 1 alone is never shown with depth-10 ranks 2 and 3.
        assertNull(c.add(pv(1, 11, "d2d4")))
        assertNull(c.add(pv(2, 11, "e2e4")))
        assertEquals(listOf("d2d4", "e2e4", "c2c4"), c.add(pv(3, 11, "c2c4"))!!.map { it.pv[0] })
        // Stopped mid-iteration: the last whole set.
        c.add(pv(1, 12, "g1f3"))
        assertEquals(listOf(11, 11, 11), c.finish().map { it.depth })
    }

    @Test fun fewerLegalMovesThanLines() {
        val c = PvCollector(3)
        assertNull(c.add(pv(1, 5, "e1d1")))
        assertNull(c.add(pv(2, 5, "e1f1")))
        // Only two moves exist: depth 5 is whole once depth 6 begins.
        assertEquals(2, c.add(pv(1, 6, "e1d1"))!!.size)
        assertEquals(1, PvCollector(1).add(pv(1, 1, "e2e4"))!!.size)
    }

    @Test fun movesFollowHowPiecesMove() {
        val b = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        assertTrue(b.canMove(Board.squareOf("e2"), Board.squareOf("e4")))
        assertFalse(b.canMove(Board.squareOf("e2"), Board.squareOf("e5")))
        assertTrue(b.canMove(Board.squareOf("g1"), Board.squareOf("f3")))
        assertFalse(b.canMove(Board.squareOf("f1"), Board.squareOf("c4")))  // blocked by the e2 pawn
        // A rook "moving" diagonally is a misread, not a move.
        val r = Board.fromPlacement("4k3/8/8/8/8/8/8/R3K3")
        val wrong = Board.fromPlacement("4k3/8/8/8/8/8/1R6/4K3")
        assertNull(Board.findMove(r, wrong))
    }

    @Test fun twoMovesAtOnceKeepTheSideToMove() {
        val start = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        val after = start.copy().also { it.play("e2e4"); it.play("e7e5") }
        assertEquals("e2e4" to "e7e5", Board.findTwoMoves(start, after, true))
        // Black first, then White, when Black was to move.
        val b2 = start.copy().also { it.play("e2e4") }
        val b3 = b2.copy().also { it.play("c7c5"); it.play("g1f3") }
        assertEquals("c7c5" to "g1f3", Board.findTwoMoves(b2, b3, false))
        // A capture retaken on the same square: that square looks unchanged.
        val x = Board.fromPlacement("4k3/8/3p4/4p3/8/5N2/8/4K3")
        val y = x.copy().also { it.play("f3e5"); it.play("d6e5") }
        assertNotNull(Board.findTwoMoves(x, y, true))
    }

    /** Flat board images, as in TrackerTest. */
    private fun render(b: Board, sq: Int = 40): Img {
        val img = Img(8 * sq + 20, 8 * sq + 20)
        for (row in 0 until 8) for (col in 0 until 8) {
            val square = (7 - row) * 8 + col
            val piece = b[square]
            for (y in 0 until sq) for (x in 0 until sq) {
                val inPiece = piece != '.' && x in sq / 4 until 3 * sq / 4 && y in sq / 4 until 3 * sq / 4
                img.px[(10 + row * sq + y) * img.w + 10 + col * sq + x] = when {
                    inPiece && piece.isUpperCase() -> 0xFFFFFF
                    inPiece -> 0x101010 + ("pnbrqk".indexOf(piece) * 0x0A0A0A)
                    (row + col) % 2 == 0 -> 0xEED8B5
                    else -> 0xB58863
                }
            }
        }
        return img
    }

    private fun rec(b: Board) = Recognition(b.placement(), false, 0.99f, 0.99f,
        listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f)), 0)

    @Test fun quickReplyKeepsSideToMove() {
        val t = BoardTracker()
        val a = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        t.accept(render(a), rec(a), null, strict = true)
        val b = a.copy().also { it.play("e2e4"); it.play("c7c5") }
        val r = t.accept(render(b), rec(b), null, strict = true) as BoardTracker.Result.Position
        assertEquals(true, r.whiteToMove)
    }

    @Test fun unexplainedSmallChangeMustBeReadTwice() {
        val t = BoardTracker()
        val a = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        t.accept(render(a), rec(a), null, strict = true)
        // The g1 knight read as a bishop: no move explains it.
        val odd = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBBR")
        assertTrue(t.accept(render(odd), rec(odd), null, strict = true) is BoardTracker.Result.Invalid)
        assertEquals(a.placement(), t.board!!.placement())
        // Read the same way again: it is real (a set-up position, an undo …).
        assertTrue(t.accept(render(odd), rec(odd), null, strict = true) is BoardTracker.Result.Position)
        // A manual read (not strict) is trusted at once.
        val t2 = BoardTracker()
        t2.accept(render(a), rec(a), null)
        assertTrue(t2.accept(render(odd), rec(odd), null) is BoardTracker.Result.Position)
    }

    private val m = TextMeasure { t, size, _ -> t.length * size * 0.6f }
    private val board = Board.fromPlacement("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
    private val corners = listOf(floatArrayOf(0f, 0f), floatArrayOf(800f, 0f), floatArrayOf(800f, 800f), floatArrayOf(0f, 800f))
    private val withLines = OverlayState(corners = corners, board = board, lines = listOf(
        PvLine(1, 22, 35, null, "f1b5 a7a6 b5a4".split(" ")), PvLine(2, 22, 28, null, "d2d4 e5d4".split(" "))))

    @Test fun cardKeepsItsSizeBetweenPositions() {
        val st = CardStyle(lines = 3)
        val analysing = OverlayState(status = OverlayState.ANALYSING, searching = true, corners = corners, board = board)
        val looking = OverlayState(status = "Looking for a board…")
        val h = CardLayer.build(withLines, 380f, st, CardControls(), m, false).height
        assertEquals(h, CardLayer.build(analysing, 380f, st, CardControls(), m, false).height, 0.01f)
        assertEquals(h, CardLayer.build(analysing, 380f, st, CardControls(), m, false, ghost = withLines).height, 0.01f)
        assertEquals(h, CardLayer.build(looking, 380f, st, CardControls(), m, false).height, 0.01f)
    }

    @Test fun lastLinesShowFadedUntilNewOnesCome() {
        val analysing = OverlayState(status = OverlayState.ANALYSING, searching = true, corners = corners, board = board)
        val card = CardLayer.build(analysing, 380f, CardStyle(), CardControls(), m, false, ghost = withLines)
        val bb5 = card.shapes.filterIsInstance<Shape.Text>().first { it.text == "Bb5" }
        assertTrue("faded", (bb5.color ushr 24) < 0xFF)
        val now = CardLayer.build(withLines, 380f, CardStyle(), CardControls(), m, false)
        assertEquals(0xFF, now.shapes.filterIsInstance<Shape.Text>().first { it.text == "Bb5" }.color ushr 24)
        // Taps on the button row never fold the card.
        assertTrue(now.foldTop > 0f && now.hits.all { it.y1 <= now.foldTop })
    }
}
