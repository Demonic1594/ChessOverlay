package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    @Test fun placementRoundTrip() {
        assertEquals(start, Board.fromPlacement(start).placement())
        val p = "r1bq1rk1/pp3ppp/2n2n2/3p4/1b1P4/2NB1N2/PP3PPP/R1BQ1RK1"
        assertEquals(p, Board.fromPlacement(p).placement())
    }

    @Test fun fullFenAndCastling() {
        assertEquals("$start w KQkq - 0 1", Board.fromPlacement(start).fen(true))
        val b = Board.fromPlacement("r3k3/8/8/8/8/8/8/4K2R")
        assertEquals("Kq", b.castling())
    }

    @Test fun attacksAndCheck() {
        // Black king on e8 checked by a rook on e1 (white king elsewhere).
        val b = Board.fromPlacement("4k3/8/8/8/8/8/8/K3R3")
        assertTrue(b.inCheck(false))
        assertFalse(b.inCheck(true))
        // White to move with black in check: invalid. Black to move: fine.
        assertEquals("the side not to move is in check", b.problem(true))
        assertNull(b.problem(false))
        assertEquals(false, Board.sideToMove(b, preferWhite = true))
        // Pawn and knight attacks.
        val c = Board.fromPlacement("4k3/8/8/3p4/8/5N2/8/4K3")
        assertTrue(c.attacked(Board.squareOf("e4"), byWhite = false))  // pawn d5 -> e4
        assertTrue(c.attacked(Board.squareOf("e5"), byWhite = true))   // knight f3 -> e5
        assertFalse(c.attacked(Board.squareOf("d4"), byWhite = false))
    }

    @Test fun problems() {
        assertEquals("need exactly one king per side", Board.fromPlacement("8/8/8/8/8/8/8/4K3").problem(true))
        assertEquals("pawn on a back rank", Board.fromPlacement("P3k3/8/8/8/8/8/8/4K3").problem(true))
    }

    @Test fun moveTextAndLines() {
        val b = Board.fromPlacement(start)
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5", b.lineText(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5"), true))
        val c = Board.fromPlacement("r3k2r/8/8/3pP3/8/8/8/R3K2R")
        assertEquals("O-O", c.moveText("e1g1"))
        assertEquals("O-O-O", c.moveText("e1c1"))
        assertEquals("exd6", c.moveText("e5d6"))  // en passant
        val p = Board.fromPlacement("4k3/1P6/8/8/8/8/8/4K3")
        assertEquals("b8=Q", p.moveText("b7b8q"))
        val after = p.copy().also { it.play("b7b8q") }
        assertEquals("1Q2k3/8/8/8/8/8/8/4K3", after.placement())
        assertEquals("1... Kd7", Board.fromPlacement("4k3/8/8/8/8/8/8/4K3").lineText(listOf("e8d7"), false))
    }

    @Test fun castlingAndEnPassantPlay() {
        val c = Board.fromPlacement("r3k2r/8/8/3pP3/8/8/8/R3K2R")
        c.copy().also { it.play("e1g1") }.let { assertEquals("r3k2r/8/8/3pP3/8/8/8/R4RK1", it.placement()) }
        c.copy().also { it.play("e5d6") }.let { assertEquals("r3k2r/8/3P4/8/8/8/8/R3K2R", it.placement()) }
    }

    @Test fun moverDetection() {
        val a = Board.fromPlacement(start)
        val b = a.copy().also { it.play("e2e4") }
        assertEquals(true, Board.mover(a, b))
        val c = b.copy().also { it.play("d7d5") }
        assertEquals(false, Board.mover(b, c))
        val d = c.copy().also { it.play("e4d5") }  // capture
        assertEquals(true, Board.mover(c, d))
    }
}
