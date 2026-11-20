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

    @Test fun signatureSeesMovesOnly() {
        val a = Board.fromPlacement(start)
        val b = after(start, "e2e4")
        val sa = BoardTracker.signature(render(a), rec(a).corners)
        assertEquals(0, BoardTracker.changedSquares(sa, BoardTracker.signature(render(a), rec(a).corners)))
        assertEquals(2, BoardTracker.changedSquares(sa, BoardTracker.signature(render(b), rec(b).corners)))
    }

    @Test fun followsAGame() {
        val t = BoardTracker()
        val a = Board.fromPlacement(start)
        val first = t.accept(render(a), rec(a), null)
        assertTrue(first is BoardTracker.Result.Position)
        assertEquals(true, (first as BoardTracker.Result.Position).whiteToMove)

        // Same picture: nothing changed, no recognition needed.
        assertFalse(t.changed(render(a)))
        assertEquals(BoardTracker.Result.Same, t.accept(render(a), rec(a), null))

        // 1. e4: the tracker finds the move and that Black is to move.
        val b = after(start, "e2e4")
        assertTrue(t.changed(render(b)))
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
