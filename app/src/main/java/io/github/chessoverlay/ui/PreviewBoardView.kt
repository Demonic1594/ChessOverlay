package io.github.chessoverlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.chessoverlay.R
import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.BoardLayer
import io.github.chessoverlay.overlay.BoardStyle
import io.github.chessoverlay.overlay.MoveMark
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.overlay.ShapeRenderer

/**
 * A small board drawn the way the overlay would draw on it with the current
 * settings (the same BoardLayer): the position the overlay saw last with the
 * engine's arrows, or, before there is one, a sample position, so that a
 * setting's effect is seen at once.
 */
class PreviewBoardView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var style = BoardStyle()
        set(v) { field = v; invalidate() }

    /** What to show: the position, who is to move, and the engine's best line (UCI); null shows the sample. */
    class Shown(val board: Board, val whiteToMove: Boolean, val line: PvLine?)

    var shown: Shown? = null
        set(v) { field = v; invalidate() }

    /** Whether the sample position gets its grade mark. */
    var grade = true
        set(v) { field = v; invalidate() }

    private val renderer = ShapeRenderer()
    private val square = Paint()
    private val piece = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER }
    private val light = ContextCompat.getColor(ctx, R.color.board_light)
    private val dark = ContextCompat.getColor(ctx, R.color.board_dark)

    // 1. e4 e5 2. Nf3 Nc6, White to move.
    private val sample = Board.fromPlacement("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
    private val sampleLines = listOf(
        PvLine(1, 24, 38, null, "f1b5 a7a6 b5a4 g8f6 e1g1 f8e7".split(" ")),
        PvLine(2, 24, 31, null, "d2d4 e5d4 f3d4 g8f6".split(" ")),
        PvLine(3, 24, 22, null, "f1c4 f8c5 c2c3 g8f6".split(" ")),
        PvLine(4, 24, 15, null, "b1c3 g8f6 d2d4".split(" ")),
        PvLine(5, 24, 9, null, "d2d3 g8f6 c2c3".split(" ")),
    )

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, minOf(w, context.dp(300f)))
    }

    override fun onDraw(c: Canvas) {
        val side = minOf(width, height).toFloat()
        val x0 = (width - side) / 2
        val sq = side / 8
        val now = shown
        val board = now?.board ?: sample
        for (r in 0 until 8) for (f in 0 until 8) {
            square.color = if ((r + f) % 2 == 0) light else dark
            c.drawRect(x0 + f * sq, r * sq, x0 + (f + 1) * sq, (r + 1) * sq, square)
            val p = board[(7 - r) * 8 + f]
            if (p != '.') drawPiece(c, p, x0 + (f + 0.5f) * sq, (r + 0.5f) * sq, sq)
        }
        val corners = listOf(floatArrayOf(x0, 0f), floatArrayOf(x0 + side, 0f), floatArrayOf(x0 + side, side), floatArrayOf(x0, side))
        val state = OverlayState(corners = corners, board = board, whiteToMove = now?.whiteToMove ?: true,
            lines = (if (now == null) sampleLines else listOfNotNull(now.line)),
            lastMove = if (now == null && grade) MoveMark(Board.squareOf("c6"), "✓", 0xFF2E9E44.toInt()) else null)
        renderer.draw(c, BoardLayer.build(state, style.copy(dp = resources.displayMetrics.density), renderer))
    }

    /** Unicode chess pieces (the solid set, coloured), with a text-style selector so none turns into an emoji. */
    private fun drawPiece(c: Canvas, p: Char, cx: Float, cy: Float, sq: Float) {
        val glyph = "♚♛♜♝♞♟"["kqrbnp".indexOf(p.lowercaseChar())] + "︎"
        piece.textSize = sq * 0.78f
        val y = cy - (piece.descent() + piece.ascent()) / 2
        val white = p.isUpperCase()
        piece.style = Paint.Style.STROKE
        piece.strokeWidth = sq * 0.06f
        piece.color = if (white) 0xFF3A3A3A.toInt() else 0xFF111111.toInt()
        c.drawText(glyph, cx, y, piece)
        piece.style = Paint.Style.FILL
        piece.color = if (white) 0xFFFAFAFA.toInt() else 0xFF3A3836.toInt()
        c.drawText(glyph, cx, y, piece)
    }
}
