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

    @Test fun aFollowedGameRemembersItsMoves() {
        // 1. e4 a6 2. Nf3 a5 3. Bd3 a4 4. Ke2 a3 5. Ke1 axb2: castling is over.
        val t = BoardTracker()
        var b = Board.fromPlacement(start)
        t.accept(render(b), rec(b), null, strict = true)
        for (m in listOf("e2e4", "a7a6", "g1f3", "a6a5", "f1d3", "a5a4", "e1e2", "a4a3", "e2e1", "a3b2")) {
            b = after(b.placement(), m)
            t.accept(render(b), rec(b), null, strict = true)
        }
        assertEquals("rnbqkbnr/1ppppppp/8/8/4P3/3B1N2/PpPP1PPP/RNBQK2R w kq - 0 1", t.board!!.fen(true))
        // 1. e4 a6 2. e5 d5: the engine is told that d6 can be taken en passant.
        val u = BoardTracker()
        var c = Board.fromPlacement(start)
        u.accept(render(c), rec(c), null, strict = true)
        for (m in listOf("e2e4", "a7a6", "e4e5", "d7d5")) {
            c = after(c.placement(), m)
            u.accept(render(c), rec(c), null, strict = true)
        }
        assertEquals("rnbqkbnr/1pp1pppp/p7/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 1", u.board!!.fen(true))
    }

    /** The game follows `moves` from `from`, one read each, as in auto mode. */
    private fun follow(t: BoardTracker, from: Board, vararg moves: String): Board {
        var b = from
        for (m in moves) {
            b = after(b.placement(), m)
            t.accept(render(b), rec(b), null, strict = true)
        }
        return b
    }

    @Test fun steppingBackTakesThePositionAsItWas() {
        val t = BoardTracker()
        val s = Board.fromPlacement(start)
        t.accept(render(s), rec(s), null, strict = true)
        // 1. e4 a6 2. Ke2: the king moved, castling is over.
        follow(t, s, "e2e4", "a7a6", "e1e2")
        assertEquals("kq", t.board!!.castling())
        assertFalse(t.whiteToMove)
        // Back one ply, to before 2.Ke2. Two squares differ, so it is read twice;
        // then it is the position it was: White to move, castling still possible.
        val back = after(start, "e2e4", "a7a6")
        assertTrue(t.accept(render(back), rec(back), null, strict = true) is BoardTracker.Result.Invalid)
        val r = t.accept(render(back), rec(back), null, strict = true) as BoardTracker.Result.Position
        assertTrue(r.back)
        assertTrue("White to move, as before 2.Ke2", t.whiteToMove)
        assertEquals("KQkq", t.board!!.castling())
        assertEquals("a7a6", t.lastMove)
        assertEquals(2, t.moveNumber)
        // On again (2. Ke2 a5), then back several moves at once: six squares
        // differ, so it is read once, and it is the start of the game.
        follow(t, back, "e1e2", "a6a5")
        assertTrue(t.whiteToMove)
        val r0 = t.accept(render(s), rec(s), null, strict = true) as BoardTracker.Result.Position
        assertTrue(r0.back)
        assertTrue(t.whiteToMove)
        assertEquals(1, t.moveNumber)
        assertEquals("$start w KQkq - 0 1", t.board!!.fen(true))
        assertNull(t.lastMove)
        // From there the game goes on, and the king's move counts again.
        follow(t, s, "e2e4", "e7e5", "e1e2")
        assertEquals("kq", t.board!!.castling())
        assertEquals(2, t.moveNumber)
    }

    @Test fun repetitionsAndTheMoveNumber() {
        val t = BoardTracker()
        val s = Board.fromPlacement(start)
        t.accept(render(s), rec(s), null, strict = true)
        assertEquals(1, t.moveNumber)
        assertEquals(1, t.repetitions)
        // 1. Nf3 Nf6 2. Ng1 Ng8: the knights are home again, the start position
        // for the second time. The last move is known, so it is a move, not a
        // step back.
        follow(t, s, "g1f3", "g8f6", "f3g1")
        val again = t.accept(render(s), rec(s), null, strict = true) as BoardTracker.Result.Position
        assertEquals("f6g8", again.move)
        assertFalse(again.back)
        assertTrue(t.whiteToMove)
        assertEquals(3, t.moveNumber)
        assertEquals(2, t.repetitions)
        // 2. Nf3 Nf6 3. Ng1 Ng8: the third time.
        follow(t, s, "g1f3", "g8f6", "f3g1")
        t.accept(render(s), rec(s), null, strict = true)
        assertEquals(3, t.repetitions)
        assertEquals(5, t.moveNumber)
        // The same pieces with a rook having moved are not the same position:
        // that side cannot castle any more. 1. Nf3 Nf6 2. Rg1 Ng8 3. Rh1 Nf6.
        val u = BoardTracker()
        val k = Board.fromPlacement(start)
        u.accept(render(k), rec(k), null, strict = true)
        val both = follow(u, k, "g1f3", "g8f6")
        follow(u, both, "h1g1", "f6g8", "g1h1")
        assertEquals(after(start, "g1f3").placement(), u.board!!.placement())
        assertEquals("Qkq", u.board!!.castling())
        assertEquals("the pieces after 1. Nf3, but no kingside castling", 1, u.repetitions)
        u.accept(render(both), rec(both), null, strict = true)   // 3... Nf6
        assertEquals(both.placement(), u.board!!.placement())
        assertEquals("Qkq", u.board!!.castling())
        assertEquals("the pieces after 1... Nf6, but no kingside castling", 1, u.repetitions)
        assertEquals(4, u.moveNumber)
    }

    @Test fun aGameJoinedLateHasNoMoveNumberUntilANewOneStarts() {
        val t = BoardTracker()
        val mid = after(start, "e2e4", "e7e5")
        t.accept(render(mid), rec(mid), null, strict = true)
        assertNull(t.moveNumber)
        follow(t, mid, "g1f3")
        assertNull(t.moveNumber)
        // Back to where the game was joined (two squares differ: read twice): still no move number.
        assertTrue(t.accept(render(mid), rec(mid), null, strict = true) is BoardTracker.Result.Invalid)
        assertTrue(t.accept(render(mid), rec(mid), null, strict = true) is BoardTracker.Result.Position)
        assertNull(t.moveNumber)
        assertTrue(t.whiteToMove)
        // A jump back to the start position (four squares differ: read twice) is another game, followed from move 1.
        val s = Board.fromPlacement(start)
        assertTrue(t.accept(render(s), rec(s), null, strict = true) is BoardTracker.Result.Invalid)
        val r = t.accept(render(s), rec(s), null, strict = true) as BoardTracker.Result.Position
        assertFalse(r.back)
        assertEquals(1, t.moveNumber)
        assertEquals(1, t.repetitions)
        assertTrue(t.whiteToMove)
    }

    @Test fun steppingBackToAPositionTheGameNeverWentThroughHere() {
        // The game joined after 1. e4 e5 2. Nf3, with the side to move set by hand.
        val t = BoardTracker()
        val f3 = after(start, "e2e4", "e7e5", "g1f3")
        t.accept(render(f3), rec(f3), false, strict = true)
        assertFalse(t.whiteToMove)
        // Back one ply, to before 2. Nf3, a position this game never went through
        // here: the knight went back, so White is to move.
        val e5 = after(start, "e2e4", "e7e5")
        assertTrue(t.accept(render(e5), rec(e5), null, strict = true) is BoardTracker.Result.Invalid)
        val r = t.accept(render(e5), rec(e5), null, strict = true) as BoardTracker.Result.Position
        assertFalse(r.back)
        assertNull(r.move)
        assertTrue(t.whiteToMove)
        assertEquals("KQkq", t.board!!.castling())
    }

    @Test fun aWrongGuessOfTheSideIsSetRightByTheNextMove() {
        // Joined after 1. e4 e5 2. Nf3: the side to move is guessed (the side at the bottom), wrongly.
        val t = BoardTracker()
        val f3 = after(start, "e2e4", "e7e5", "g1f3")
        t.accept(render(f3), rec(f3), null, strict = true)
        assertTrue(t.whiteToMove)
        // Black moves: a move all the same, and from now on the side to move is right.
        val c6 = after(start, "e2e4", "e7e5", "g1f3", "b8c6")
        val r = t.accept(render(c6), rec(c6), null, strict = true) as BoardTracker.Result.Position
        assertEquals("b8c6", r.move)
        assertEquals("b8c6", t.lastMove)
        assertTrue(t.whiteToMove)
        // And back one ply from there: the position was taken with its side guessed
        // wrong, but the knight went back, so Black is to move.
        assertTrue(t.accept(render(f3), rec(f3), null, strict = true) is BoardTracker.Result.Invalid)
        val back = t.accept(render(f3), rec(f3), null, strict = true) as BoardTracker.Result.Position
        assertTrue(back.back)
        assertFalse(t.whiteToMove)
    }

    @Test fun inCheckSideMustMove() {
        val t = BoardTracker()
        val b = Board.fromPlacement("4k3/8/8/8/8/8/8/K3R3")  // black in check
        val r = t.accept(render(b), rec(b), true) as BoardTracker.Result.Position
        assertEquals(false, r.whiteToMove)
    }
}
