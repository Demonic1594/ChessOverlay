package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    private fun after(p: String, vararg moves: String) =
        Board.fromPlacement(p).also { b -> moves.forEach { b.play(it) } }

    @Test fun findMoveCoversSpecialMoves() {
        val a = Board.fromPlacement(start)
        assertEquals("e2e4", Board.findMove(a, after(start, "e2e4")))
        assertEquals("g1f3", Board.findMove(a, after(start, "g1f3")))
        val c = "r3k2r/8/8/3pP3/8/8/8/R3K2R"
        assertEquals("e1g1", Board.findMove(Board.fromPlacement(c), after(c, "e1g1")))
        assertEquals("e8c8", Board.findMove(Board.fromPlacement(c), after(c, "e8c8")))
        assertEquals("e5d6", Board.findMove(Board.fromPlacement(c), after(c, "e5d6")))  // en passant
        val p = "4k3/1P6/8/8/8/8/8/4K3"
        assertEquals("b7b8n", Board.findMove(Board.fromPlacement(p), after(p, "b7b8n")))
        assertEquals("d1d5", Board.findMove(Board.fromPlacement("4k3/8/8/3p4/8/8/8/3QK3"), after("4k3/8/8/3p4/8/8/8/3QK3", "d1d5")))
        // Two moves apart: no single move.
        assertNull(Board.findMove(a, after(start, "e2e4", "e7e5")))
        assertNull(Board.findMove(a, a.copy()))
    }

    /** A flat 8x8 board image: each square one colour, pieces as a darker block. */
    private fun render(b: Board, sq: Int = 40): Img {
        val img = Img(8 * sq + 20, 8 * sq + 20)
        for (row in 0 until 8) for (col in 0 until 8) {
            val square = (7 - row) * 8 + col
            val light = (row + col) % 2 == 0
            val piece = b[square]
            for (y in 0 until sq) for (x in 0 until sq) {
                val inPiece = piece != '.' && x in sq / 4 until 3 * sq / 4 && y in sq / 4 until 3 * sq / 4
                val c = when {
                    inPiece && piece.isUpperCase() -> 0xFFFFFF
                    inPiece -> 0x101010 + ("pnbrqk".indexOf(piece) * 0x0A0A0A)
                    light -> 0xEED8B5
                    else -> 0xB58863
                }
                img.px[(10 + row * sq + y) * img.w + 10 + col * sq + x] = c
            }
        }
        return img
    }

    private fun rec(b: Board, sq: Int = 40) = Recognition(
        placement = b.placement(), blackAtBottom = false, confidence = 0.99f, orientationConfidence = 0.99f,
        corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(10f + 8 * sq, 10f),
            floatArrayOf(10f + 8 * sq, 10f + 8 * sq), floatArrayOf(10f, 10f + 8 * sq)),
        legalityChanges = 0,
    )

    @Test fun changeDetectionSeesMovesOnly() {
        val t = BoardTracker()
        val a = Board.fromPlacement(start)
        assertEquals(64, t.changedSquares(render(a)))  // no board yet
        t.accept(render(a), rec(a), null)
        assertEquals(0, t.changedSquares(render(a)))
        assertEquals(2, t.changedSquares(render(after(start, "e2e4"))))
        // A crop of the board area with its screen offset gives the same answer.
        val img = render(after(start, "e2e4"))
        assertEquals(2, t.changedSquares(img.crop(5, 5, img.w, img.h), 5, 5))
    }

    /** Overlay drawing on the board must not look like a move; a real move under it still must. */
    @Test fun overlayDrawingIsMasked() {
        val t = BoardTracker()
        val a = Board.fromPlacement(start)
        t.accept(render(a), rec(a), null)
        val k = rec(a).corners
        val g = io.github.chessoverlay.overlay.BoardGeom(k, false)
        val arrow = io.github.chessoverlay.overlay.Shape.Poly(io.github.chessoverlay.overlay.arrowOutline(
            listOf(g.centre(Board.squareOf("e2")), g.centre(Board.squareOf("e4"))), g.sq * 0.17f, g.sq * 0.42f, g.sq * 0.27f),
            0xE61F7AE6.toInt())
        val cover = io.github.chessoverlay.overlay.ShapeCover(listOf(arrow), 2f)
        // Paint the arrow's pixels blue, as the screen shows them with the overlay on.
        fun withArrow(img: Img) = img.also {
            for (y in 0 until it.h) for (x in 0 until it.w) if (cover.covered(x, y)) it.px[y * it.w + x] = 0x1F7AE6
        }
        assertTrue("the arrow alone changes squares", t.changedSquares(withArrow(render(a))) >= 2)
        assertEquals(0, t.changedSquares(withArrow(render(a)), cover = cover))
        // e2-e4 played under the arrow: still seen.
        assertEquals(2, t.changedSquares(withArrow(render(after(start, "e2e4"))), cover = cover))
        // Squares mostly under a big drawing count as blind, not changed.
        val blob = io.github.chessoverlay.overlay.Shape.Circle(g.centre(Board.squareOf("d4")).x,
            g.centre(Board.squareOf("d4")).y, g.sq * 0.6f, 0)
        val blind = io.github.chessoverlay.overlay.ShapeCover(listOf(blob), 0f)
        val (xs, ys) = t.points()!!
        assertEquals(1, t.compare(BoardTracker.sample(render(a), xs, ys, 0, 0), blind).blind)
    }

    /** A misread keeps the last good read, so the change is seen again and re-read. */
    @Test fun misreadKeepsReference() {
        val t = BoardTracker()
        val a = Board.fromPlacement(start)
        t.accept(render(a), rec(a), null)
        val b = after(start, "e2e4")
        val misread = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPPPPPP/RNBQKBNR"  // 17 white pieces
        val bad = rec(b).copy(placement = misread, drawn = misread)
        assertTrue(t.accept(render(b), bad, null) is BoardTracker.Result.Invalid)
        assertEquals(start, t.board!!.placement())
        assertEquals(2, t.changedSquares(render(b)))
        assertEquals("e2e4", (t.accept(render(b), rec(b), null) as BoardTracker.Result.Position).move)
        assertEquals(0, t.changedSquares(render(b)))
    }

    @Test fun followsAGame() {
        val t = BoardTracker()
        val a = Board.fromPlacement(start)
        val first = t.accept(render(a), rec(a), null)
        assertTrue(first is BoardTracker.Result.Position)
        assertEquals(true, (first as BoardTracker.Result.Position).whiteToMove)

        // Same picture: nothing changed, no recognition needed.
        assertEquals(0, t.changedSquares(render(a)))
        assertEquals(BoardTracker.Result.Same, t.accept(render(a), rec(a), null))

        // 1. e4: the tracker finds the move and that Black is to move.
        val b = after(start, "e2e4")
        assertEquals(2, t.changedSquares(render(b)))
        val second = t.accept(render(b), rec(b), null) as BoardTracker.Result.Position
        assertEquals("e2e4", second.move)
        assertEquals(false, second.whiteToMove)
        assertEquals(true, second.previousWhiteToMove)

        // 1... c5: White to move again.
        val c = after(start, "e2e4", "c7c5")
        val third = t.accept(render(c), rec(c), null) as BoardTracker.Result.Position
        assertEquals("c7c5", third.move)
        assertEquals(true, third.whiteToMove)

        // Two moves at once (a missed screen): side from the colour that moved last is unknown,
        // the heuristic picks the side that left squares; still a valid position.
        val d = after(start, "e2e4", "c7c5", "g1f3", "d7d6")
        val fourth = t.accept(render(d), rec(d), null) as BoardTracker.Result.Position
        assertNull(fourth.move)

        // The side override wins.
        val forced = t.accept(render(d), rec(d), false) as BoardTracker.Result.Position
        assertEquals(false, forced.whiteToMove)
    }

    @Test fun inCheckSideMustMove() {
        val t = BoardTracker()
        val b = Board.fromPlacement("4k3/8/8/8/8/8/8/K3R3")  // black in check
        val r = t.accept(render(b), rec(b), true) as BoardTracker.Result.Position
        assertEquals(false, r.whiteToMove)
    }
}
