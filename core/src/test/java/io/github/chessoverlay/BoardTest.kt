package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /** A game from `p`: the board after `moves` were played. */
    private fun game(p: String, vararg moves: String) = Board.fromPlacement(p).also { b -> moves.forEach { b.play(it) } }

    @Test fun castlingRightsFollowTheMoves() {
        // 1. e4 a6 2. Nf3 a5 3. Bd3 a4 4. Ke2 a3 5. Ke1 axb2: the king is back
        // on e1, and may no longer castle.
        val walked = game(start, "e2e4", "a7a6", "g1f3", "a6a5", "f1d3", "a5a4", "e1e2", "a4a3", "e2e1", "a3b2")
        assertEquals("rnbqkbnr/1ppppppp/8/8/4P3/3B1N2/PpPP1PPP/RNBQK2R w kq - 0 1", walked.fen(true))
        assertFalse("e1g1" in walked.legalMoves(true))
        // 1. h4 a6 2. Rh3 a5 3. Rh1 a4: the rook is back too; its side is gone.
        assertEquals("rnbqkbnr/1ppppppp/8/8/p6P/8/PPPPPPP1/RNBQKBNR w Qkq - 0 1",
            game(start, "h2h4", "a7a6", "h1h3", "a6a5", "h3h1", "a5a4").fen(true))
        // 1. a4 Ke7 2. h4 Ke8 3. Ra3 Bxh1 4. Rh3 Be4 5. Rh1 Bc6: the rook on h1
        // now is the one from a1. It does not bring castling back.
        val taken = game("4k3/8/2b5/8/8/8/P6P/R3K2R",
            "a2a4", "e8e7", "h2h4", "e7e8", "a1a3", "c6h1", "a3h3", "h1e4", "h3h1", "e4c6")
        assertEquals("4k3/8/2b5/8/P6P/8/8/4K2R w - - 4 1", taken.fen(true))
        assertFalse("e1g1" in taken.legalMoves(true))
    }

    @Test fun halfmoveClock() {
        // A pawn move or a capture starts the count again; any other move adds one.
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", game(start, "e2e4").fen(false))
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1", game(start, "e2e4", "e7e5").fen(true))
        assertEquals("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 1",
            game(start, "e2e4", "e7e5", "g1f3", "b8c6").fen(true))
        // 3. Bb5 a6 4. Bxc6 dxc6
        assertEquals("r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 1",
            game(start, "e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6").fen(false))
        // A capture en passant is a capture.
        assertEquals("rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            game(start, "e2e4", "a7a6", "e4e5", "d7d5", "e5d6").fen(false))
    }

    @Test fun whatIsNotAMoveIsRefused() {
        for (bad in listOf("0000", "(none)", "e2", "e2e", "e2e4+", "e2e4x", "z9a1", "")) {
            assertFalse(bad, Board.isMove(bad))
            assertThrows(IllegalArgumentException::class.java) { Board.fromPlacement(start).play(bad) }
        }
        assertTrue(Board.isMove("e2e4"))
        assertTrue(Board.isMove("e7e8q"))
        assertTrue(Board.isMove("e1g1"))
        // An engine line with such a move in it ends there; the overlay does not crash.
        assertEquals("1. e4", Board.fromPlacement(start).lineText(listOf("e2e4", "0000", "e7e5"), true))
        val board = Board.fromPlacement(start)
        assertEquals(listOf("Nf3"), io.github.chessoverlay.overlay.CardLayer.san(board,
            io.github.chessoverlay.engine.PvLine(1, 10, 0, null, listOf("g1f3", "(none)"))))
        assertEquals("0000", board.moveText("0000"))
    }

    @Test fun castlingNeedsTheRightAndASafeKing() {
        // Not out of check, and not across an attacked square.
        assertFalse("e1g1" in Board.fromPlacement("4k3/8/8/8/8/8/4r3/4K2R").legalMoves(true))
        assertFalse("e1g1" in Board.fromPlacement("5r1k/8/8/8/8/8/8/4K2R").legalMoves(true))
        assertFalse("e1c1" in Board.fromPlacement("3r3k/8/8/8/8/8/8/R3K2R").legalMoves(true))
        // A square only the rook crosses may be attacked.
        assertTrue("e1c1" in Board.fromPlacement("1r4k1/8/8/8/8/8/8/R3K2R").legalMoves(true))
        // With the right gone there is no castling, however safe the king.
        val walked = game(start, "e2e4", "a7a6", "g1f3", "a6a5", "f1d3", "a5a4", "e1e2", "a4a3", "e2e1", "a3b2")
        assertEquals("kq", walked.castling())
        assertFalse("e1g1" in walked.legalMoves(true))
    }

    @Test fun enPassantSquare() {
        // 1. e4 a6 2. e5 d5: White may take on d6, and the engine is told so.
        val now = game(start, "e2e4", "a7a6", "e4e5", "d7d5")
        assertEquals("rnbqkbnr/1pp1pppp/p7/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 1", now.fen(true))
        assertTrue("e5d6" in now.legalMoves(true))
        assertEquals("rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            game(start, "e2e4", "a7a6", "e4e5", "d7d5", "e5d6").fen(false))
        // A double step nobody can take is not reported, as engines do not report it.
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", game(start, "e2e4").fen(false))
        // 1. e4 d5 2. e5 a6 3. a5: the pawn on d5 stopped moving long ago.
        val late = game(start, "e2e4", "d7d5", "e4e5", "a7a6", "a6a5")
        assertFalse("e5d6" in late.legalMoves(true))
        assertEquals("rnbqkbnr/1pp1pppp/8/p2pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1", late.fen(true))
        // A position read from the screen alone has no history: the capture is assumed possible.
        assertTrue("e5d6" in Board.fromPlacement(late.placement()).legalMoves(true))
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
        assertEquals("1 white and 0 black kings", Board.fromPlacement("8/8/8/8/8/8/8/4K3").problem(true))
        assertEquals("a pawn on the first or last rank", Board.fromPlacement("P3k3/8/8/8/8/8/8/4K3").problem(true))
        // 17 white pieces can only be a misread: the message says so with the counts.
        assertEquals("17 white and 1 black pieces; a side has at most 16",
            Board.fromPlacement("4k3/8/QQQQQQQQ/QQQQQQQQ/8/8/8/4K3").problem(true))
    }

    @Test fun moveTextAndLines() {
        val b = Board.fromPlacement(start)
        assertEquals("1. e4 e5 2. Nf3 Nc6 3. Bb5", b.lineText(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5"), true))
        val c = Board.fromPlacement("r3k2r/8/8/3pP3/8/8/8/R3K2R")
        assertEquals("O-O", c.moveText("e1g1"))
        assertEquals("O-O-O", c.moveText("e1c1"))
        assertEquals("exd6", c.moveText("e5d6"))  // en passant
        val p = Board.fromPlacement("4k3/1P6/8/8/8/8/8/4K3")
        assertEquals("b8=Q+", p.moveText("b7b8q"))  // the new queen checks along the 8th rank
        val after = p.copy().also { it.play("b7b8q") }
        assertEquals("1Q2k3/8/8/8/8/8/8/4K3", after.placement())
        assertEquals("1... Kd7", Board.fromPlacement("4k3/8/8/8/8/8/8/4K3").lineText(listOf("e8d7"), false))
    }

    @Test fun sanChecksAndDisambiguation() {
        // Two knights can reach d2: by file. Two rooks on one file: by rank.
        val n = Board.fromPlacement("4k3/8/8/8/8/8/8/1N2KN2")
        assertEquals("Nbd2", n.moveText("b1d2"))
        assertEquals("Nfd2", n.moveText("f1d2"))
        val r = Board.fromPlacement("4k3/R7/8/8/8/8/R7/4K3")
        assertEquals("R7a5", r.moveText("a7a5"))
        assertEquals("R2a5", r.moveText("a2a5"))
        // A pinned twin does not count.
        assertEquals("Ncd4", Board.fromPlacement("6k1/8/8/8/8/8/2N1N3/4K3").moveText("c2d4"))
        // ... but with the e2 knight pinned by the e8 rook, only one can go.
        assertEquals("Nd4", Board.fromPlacement("4r1k1/8/8/8/8/8/2N1N3/4K3").moveText("c2d4"))
        // Checks, including castling into check and discovered check.
        assertEquals("Qh5+", Board.fromPlacement("4k3/8/8/8/8/8/8/3QK3").moveText("d1h5"))
        assertEquals("O-O+", Board.fromPlacement("5k2/8/8/8/8/8/8/4K2R").moveText("e1g1"))
        assertEquals("Nc3+", Board.fromPlacement("4k3/8/8/8/8/8/4N3/4RK2").moveText("e2c3"))
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

class PathLabelTest {
    @Test fun eachSideCountsItsOwnMoves() {
        val w = io.github.chessoverlay.overlay.BoardLayer.pathLabels(whiteToMove = true, plies = 5)
        assertEquals(listOf(true to 1, false to 1, true to 2, false to 2, true to 3), w)
        val b = io.github.chessoverlay.overlay.BoardLayer.pathLabels(whiteToMove = false, plies = 4)
        assertEquals(listOf(false to 1, true to 1, false to 2, true to 2), b)
    }
}

class CardLayoutTest {
    private val m = io.github.chessoverlay.overlay.TextMeasure { t, size, _ -> t.length * size * 0.6f }
    private val board = Board.fromPlacement("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
    private val lines = listOf(
        io.github.chessoverlay.engine.PvLine(1, 22, 35, null, "f1b5 a7a6 b5a4 g8f6 e1g1 f8e7".split(" ")),
        io.github.chessoverlay.engine.PvLine(2, 22, 28, null, "d2d4 e5d4 f3d4".split(" ")),
        io.github.chessoverlay.engine.PvLine(3, 22, 20, null, "f1c4 f8c5".split(" ")),
    )
    private val corners = listOf(floatArrayOf(0f, 0f), floatArrayOf(800f, 0f), floatArrayOf(800f, 800f), floatArrayOf(0f, 800f))
    private val state = io.github.chessoverlay.overlay.OverlayState(corners = corners, board = board, lines = lines)

    private fun card(collapsed: Boolean, width: Float = 380f) = io.github.chessoverlay.overlay.CardLayer.build(
        state, width, io.github.chessoverlay.overlay.CardStyle(), io.github.chessoverlay.overlay.CardControls(), m, collapsed)

    @Test fun foldingHidesTheLines() {
        assertTrue(card(true).height < card(false).height)
        assertEquals(5, card(true).hits.size)
    }

    @Test fun everythingFitsANarrowCard() {
        for (w in listOf(260f, 320f, 380f)) {
            val c = card(false, w)
            for (s in c.shapes) if (s is io.github.chessoverlay.overlay.Shape.Text) {
                assertTrue("'${s.text}' leaves a $w card", s.left >= 0f && s.left + s.width <= w + 0.5f)
            }
            // Buttons never overlap one another.
            val hits = c.hits.sortedBy { it.x0 }
            for (i in 1 until hits.size) assertTrue(hits[i].x0 >= hits[i - 1].x1 - 0.01f)
        }
    }

    @Test fun buttonsAreFoundByPosition() {
        val c = card(false)
        val auto = c.hits.first { it.button == io.github.chessoverlay.overlay.CardButton.AUTO }
        assertEquals(io.github.chessoverlay.overlay.CardButton.AUTO, c.buttonAt((auto.x0 + auto.x1) / 2, 10f))
        assertEquals(null, c.buttonAt(370f, c.height - 5f))
    }

    @Test fun scoresAndNotation() {
        assertEquals("+0.4", io.github.chessoverlay.overlay.CardLayer.shortScore(lines[0], true))
        assertEquals("-0.4", io.github.chessoverlay.overlay.CardLayer.shortScore(lines[0], false))
        assertEquals(listOf("Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7"), io.github.chessoverlay.overlay.CardLayer.san(board, lines[0]))
        val mate = io.github.chessoverlay.engine.PvLine(1, 20, null, 2, "c6a8 d7c8 a8c8".split(" "))
        assertEquals(listOf("Qxa8+", "Bc8", "Qxc8#"), io.github.chessoverlay.overlay.CardLayer.san(
            Board.fromPlacement("r3kb2/p1pb1p1r/1pQp1B2/4P2p/8/2N5/PPP2PPP/R3KBNR"), mate))
    }

    @Test
    fun theGamesNumbersFit() {
        fun card(w: Float) = io.github.chessoverlay.overlay.CardLayer.build(
            state.copy(moveNumber = 14, repetitions = 3), w, io.github.chessoverlay.overlay.CardStyle(),
            io.github.chessoverlay.overlay.CardControls(), m, false)
        fun info(c: io.github.chessoverlay.overlay.CardLayout) =
            c.shapes.filterIsInstance<io.github.chessoverlay.overlay.Shape.Text>().first { it.text.startsWith("d22") }
        // A wide card says it all ...
        assertEquals("d22 ×3 14.", info(card(380f)).text)
        // ... a narrow one the depth, and as much more as fits before the buttons.
        assertEquals("d22", info(card(260f)).text)
        for (w in listOf(260f, 320f, 380f)) {
            val c = card(w)
            // The depth never runs into the buttons, which keep their width.
            assertTrue("the text runs into the buttons at $w", info(c).left >= c.hits.maxOf { it.x1 } - 0.01f)
            assertTrue("a button is too narrow at $w", c.hits.all { it.x1 - it.x0 >= 28f - 0.01f })
            for (s in c.shapes) if (s is io.github.chessoverlay.overlay.Shape.Text) {
                assertTrue("'${s.text}' leaves a $w card", s.left >= 0f && s.left + s.width <= w + 0.5f)
            }
        }
    }

    @Test
    fun whatTheEngineExpectedIsDrawnDimmed() {
        val plain = state.copy(lines = listOf(io.github.chessoverlay.engine.PvLine(1, 20, 30, null, listOf("f1b5", "a7a6", "b5a4"))))
        val expected = plain.copy(provisional = true)
        fun fills(s: io.github.chessoverlay.overlay.OverlayState) =
            io.github.chessoverlay.overlay.BoardLayer.build(s, io.github.chessoverlay.overlay.BoardStyle(arrows = 2, pathPlies = 2), m)
                .filterIsInstance<io.github.chessoverlay.overlay.Shape.Poly>().map { it.fill ushr 24 }
        // The same drawing, each shape at 55 %.
        assertEquals(fills(plain).map { (it * 0.55f).toInt() }, fills(expected))
        assertEquals(0xE6, fills(plain).last())
        assertTrue(fills(expected).last() < 0x90)
        // The card fades it, as it fades the last position's lines.
        fun text(s: io.github.chessoverlay.overlay.OverlayState, t: String) =
            io.github.chessoverlay.overlay.CardLayer.build(s, 380f, io.github.chessoverlay.overlay.CardStyle(),
                io.github.chessoverlay.overlay.CardControls(), m, false).shapes
                .filterIsInstance<io.github.chessoverlay.overlay.Shape.Text>().first { it.text == t }
        assertEquals(0xFF, text(plain, "Bb5").color ushr 24)
        assertTrue(text(expected, "Bb5").color ushr 24 < 0xFF)
    }

    @Test
    fun outcomes() {
        // From a phone log: 1...Kb4 2.Bd2 is mate (a5 is seen by the bishop through the king's square).
        assertEquals("Checkmate: White wins", Board.fromPlacement("8/8/6R1/3P4/1kP1N3/pP5P/P2B2P1/5K2").outcome(false))
        assertEquals("Stalemate: a draw", Board.fromPlacement("7k/5Q2/6K1/8/8/8/8/8").outcome(false))
        assertEquals("Checkmate: Black wins", Board.fromPlacement("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR").outcome(true))
        // Qe2#, with the king and rook on their squares: castling out of check is no way out.
        assertEquals("Checkmate: Black wins", Board.fromPlacement("k7/8/8/8/2b5/8/PPPPqPPP/1N2K2R").outcome(true))
        assertNull(Board.fromPlacement("8/8/6R1/3P4/1kP1N3/pP5P/P2B2P1/5K2").outcome(true))
        assertNull(Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR").outcome(true))
    }
}
