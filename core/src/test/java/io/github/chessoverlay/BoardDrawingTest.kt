package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.BoardDrawing
import io.github.chessoverlay.overlay.BoardStyle
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.overlay.Shape
import io.github.chessoverlay.overlay.TextMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The board layer's drawing: built again only when it changes, and always after it was cleared for a capture. */
class BoardDrawingTest {
    private val m = TextMeasure { t, size, _ -> t.length * size * 0.6f }
    private val board = Board.fromPlacement("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
    private val lines = listOf(
        PvLine(1, 22, 35, null, "f1b5 a7a6 b5a4 g8f6 e1g1 f8e7".split(" ")),
        PvLine(2, 22, 28, null, "d2d4 e5d4 f3d4".split(" ")),
    )
    private val corners = listOf(floatArrayOf(0f, 0f), floatArrayOf(800f, 0f), floatArrayOf(800f, 800f), floatArrayOf(0f, 800f))
    private val state = OverlayState(corners = corners, board = board, lines = lines)

    private fun arrows(d: BoardDrawing) = d.shapes.filterIsInstance<Shape.Poly>().size

    @Test fun theSameDrawingIsNotBuiltAgain() {
        val d = BoardDrawing({ BoardStyle(arrows = 2, pathPlies = 2) }, m)
        assertTrue(d.show(state))
        val first = d.shapes
        assertTrue("the lines are drawn as arrows", arrows(d) > 0)
        // The same state, and one that differs in what the board does not draw: no new drawing.
        assertFalse(d.show(state))
        assertSame(first, d.shapes)
        assertFalse(d.show(state.copy(status = "Analysing…", searching = true, moveNumber = 14, repetitions = 2)))
        // New lines are a new drawing.
        assertTrue(d.show(state.copy(lines = lines.reversed())))
        assertTrue(d.show(state.copy(lines = emptyList())))
        assertEquals("no lines, no arrows", 0, arrows(d))
    }

    @Test fun afterACaptureTheSameDrawingComesBack() {
        val d = BoardDrawing({ BoardStyle(arrows = 2, pathPlies = 2) }, m)
        d.show(state)
        val before = d.shapes.size
        // The screen is captured without the drawing, and the read ends with the same position: a misread
        // that was read aright the next time, a highlight that went, a capture taken too early.
        d.clear()
        assertTrue(d.shapes.isEmpty())
        assertTrue("the same state is drawn again after a clear", d.show(state))
        assertEquals(before, d.shapes.size)
        assertTrue(arrows(d) > 0)
        // From there on, the same drawing is not built again.
        assertFalse(d.show(state))
    }
}
