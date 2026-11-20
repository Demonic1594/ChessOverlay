package io.github.chessoverlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import io.github.chessoverlay.Prefs
import io.github.chessoverlay.chess.Board
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen, touch-through drawing layer: arrows for the best moves, the
 * best line as a numbered path, an evaluation bar beside the board, a panel
 * with the engine lines, and the grade of the last move.
 */
class DrawView(ctx: Context, private val prefs: Prefs) : View(ctx) {
    var state: OverlayState = OverlayState()
        set(v) { field = v; postInvalidate() }

    private val loc = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }

    override fun onDraw(c: Canvas) {
        val s = state
        // Captured-screen pixels -> this view's coordinates.
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val corners = s.corners
        if (corners != null) {
            val sqSize = hypot(corners[1][0] - corners[0][0], corners[1][1] - corners[0][1]) / 8f
            val board = s.board
            if (board != null) {
                drawPath(c, s, board, corners, sqSize)
                drawArrows(c, s, board, corners, sqSize)
                s.lastMove?.let { drawMark(c, it, s, corners, sqSize) }
            }
            if (prefs.evalBar && s.lines.isNotEmpty()) drawEvalBar(c, s, corners, sqSize)
            if (prefs.linesPanel) drawPanel(c, s, corners, sqSize)
        } else if (s.status.isNotEmpty()) {
            drawBox(c, listOf(s.status), width / 2f - 300f + loc[0], 200f + loc[1], 600f, 34f)
        }
        c.restore()
    }

    /** Centre of a board square on the captured screen. */
    private fun centre(square: Int, s: OverlayState, k: List<FloatArray>): PointF {
        val file = square % 8
        val rank = square / 8
        val col = if (s.blackAtBottom) 7 - file else file
        val row = if (s.blackAtBottom) rank else 7 - rank
        val u = (col + 0.5f) / 8f
        val v = (row + 0.5f) / 8f
        // Bilinear interpolation inside the quad TL, TR, BR, BL.
        val x = (1 - u) * (1 - v) * k[0][0] + u * (1 - v) * k[1][0] + u * v * k[2][0] + (1 - u) * v * k[3][0]
        val y = (1 - u) * (1 - v) * k[0][1] + u * (1 - v) * k[1][1] + u * v * k[2][1] + (1 - u) * v * k[3][1]
        return PointF(x, y)
    }

    private fun drawArrows(c: Canvas, s: OverlayState, board: Board, k: List<FloatArray>, sq: Float) {
        val n = min(prefs.arrows, s.lines.size)
        // Draw the weaker lines first so the best arrow ends up on top.
        for (i in n - 1 downTo 0) {
            val line = s.lines[i]
            val move = line.pv.firstOrNull() ?: continue
            val from = Board.squareOf(move.substring(0, 2))
            val to = Board.squareOf(move.substring(2, 4))
            if (from < 0 || to < 0) continue
            val color = ARROW_COLORS[i % ARROW_COLORS.size]
            val a = centre(from, s, k); val b = centre(to, s, k)
            arrow(c, a, b, color, sq * if (i == 0) 0.22f else 0.15f)
            if (prefs.arrowLabels) label(c, b, line.scoreText(s.whiteToMove), color, sq)
        }
    }

    private fun drawPath(c: Canvas, s: OverlayState, board: Board, k: List<FloatArray>, sq: Float) {
        val pv = s.lines.firstOrNull()?.pv ?: return
        val plies = min(prefs.pathPlies, pv.size - 1)
        if (plies <= 0) return
        var white = !s.whiteToMove  // the reply to the first move
        for (i in 1..plies) {
            val m = pv[i]
            val from = Board.squareOf(m.substring(0, 2)); val to = Board.squareOf(m.substring(2, 4))
            if (from < 0 || to < 0) break
            val color = if (white) 0xC0F5F5F5.toInt() else 0xC0303030.toInt()
            val a = centre(from, s, k); val b = centre(to, s, k)
            arrow(c, a, b, color, sq * 0.09f)
            // Move number in a small disc near the arrow head.
            val p = PointF(a.x + (b.x - a.x) * 0.75f, a.y + (b.y - a.y) * 0.75f)
            fill.color = color
            c.drawCircle(p.x, p.y, sq * 0.17f, fill)
            text.color = if (white) Color.BLACK else Color.WHITE
            text.textSize = sq * 0.22f
            text.textAlign = Paint.Align.CENTER
            c.drawText("${i + 1}", p.x, p.y + text.textSize * 0.36f, text)
            white = !white
        }
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
    }

    private fun arrow(c: Canvas, a: PointF, b: PointF, color: Int, width: Float) {
        val ang = atan2(b.y - a.y, b.x - a.x)
        val head = width * 2.6f
        val tip = PointF(b.x, b.y)
        val base = PointF(b.x - cos(ang) * head, b.y - sin(ang) * head)
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(a.x, a.y, base.x, base.y, stroke)
        val p = Path()
        p.moveTo(tip.x, tip.y)
        p.lineTo(base.x + sin(ang) * head * 0.6f, base.y - cos(ang) * head * 0.6f)
        p.lineTo(base.x - sin(ang) * head * 0.6f, base.y + cos(ang) * head * 0.6f)
        p.close()
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun label(c: Canvas, at: PointF, s: String, color: Int, sq: Float) {
        text.textSize = sq * 0.24f
        val w = text.measureText(s) + sq * 0.12f
        val h = text.textSize * 1.25f
        val r = RectF(at.x - w / 2, at.y - sq * 0.48f, at.x + w / 2, at.y - sq * 0.48f + h)
        fill.color = color
        c.drawRoundRect(r, h / 3, h / 3, fill)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        c.drawText(s, r.centerX(), r.bottom - h * 0.25f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawMark(c: Canvas, m: MoveMark, s: OverlayState, k: List<FloatArray>, sq: Float) {
        val p = centre(m.square, s, k)
        val x = p.x + sq * 0.32f; val y = p.y - sq * 0.32f
        fill.color = m.color
        c.drawCircle(x, y, sq * 0.2f, fill)
        text.color = Color.WHITE
        text.textSize = sq * 0.24f
        text.textAlign = Paint.Align.CENTER
        c.drawText(m.label, x, y + text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawEvalBar(c: Canvas, s: OverlayState, k: List<FloatArray>, sq: Float) {
        val left = min(k[0][0], k[3][0]); val right = max(k[1][0], k[2][0])
        val top = min(k[0][1], k[1][1]); val bottom = max(k[2][1], k[3][1])
        val w = max(10f, sq * 0.22f)
        // Left of the board if there is room on screen, else just inside it.
        val x0 = if (left - w - 4f >= loc[0]) left - w - 4f else left
        val score = s.lines[0].whiteScore(s.whiteToMove)
        val white = Analyst.expected(score).toFloat()
        val h = bottom - top
        // White's share is at White's side of the board.
        val whiteAtBottom = !s.blackAtBottom
        fill.color = 0xFF404040.toInt()
        c.drawRect(x0, top, x0 + w, bottom, fill)
        fill.color = 0xFFF0F0F0.toInt()
        if (whiteAtBottom) c.drawRect(x0, bottom - h * white, x0 + w, bottom, fill)
        else c.drawRect(x0, top, x0 + w, top + h * white, fill)
        // The score in a small box at the end of the bar of the side ahead.
        val txt = s.lines[0].scoreText(s.whiteToMove)
        text.textSize = max(18f, sq * 0.2f)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        val tw = text.measureText(txt) + 8f
        val atBottom = whiteAtBottom == (score >= 0)
        val boxTop = if (atBottom) bottom - text.textSize - 8f else top
        fill.color = 0xD0000000.toInt()
        c.drawRect(x0 + w / 2 - tw / 2, boxTop, x0 + w / 2 + tw / 2, boxTop + text.textSize + 8f, fill)
        c.drawText(txt, x0 + w / 2, boxTop + text.textSize + 2f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawPanel(c: Canvas, s: OverlayState, k: List<FloatArray>, sq: Float) {
        val board = s.board
        val rows = mutableListOf<String>()
        if (board != null) {
            for (line in s.lines.take(prefs.lines)) {
                rows.add("${line.scoreText(s.whiteToMove)}  ${board.lineText(line.pv, s.whiteToMove)}")
            }
        }
        rows.add(s.status + (if (board != null) "  ·  ${if (s.whiteToMove) "White" else "Black"} to move" else ""))
        val left = min(k[0][0], k[3][0]); val right = max(k[1][0], k[2][0])
        val bottom = max(k[2][1], k[3][1]); val top = min(k[0][1], k[1][1])
        val size = max(26f, sq * 0.26f)
        val boxH = rows.size * size * 1.35f + size * 0.6f
        // Below the board if it fits on screen, else above it.
        val y = if (bottom + 8f + boxH <= loc[1] + height) bottom + 8f else max(loc[1].toFloat(), top - 8f - boxH)
        drawBox(c, rows, left, y, right - left, size)
    }

    private fun drawBox(c: Canvas, rows: List<String>, x: Float, y: Float, w: Float, size: Float) {
        text.textSize = size
        text.color = Color.WHITE
        val h = rows.size * size * 1.35f + size * 0.6f
        fill.color = 0xD0202020.toInt()
        c.drawRoundRect(RectF(x, y, x + w, y + h), size * 0.4f, size * 0.4f, fill)
        for ((i, r) in rows.withIndex()) {
            val clipped = ellipsize(r, w - size)
            c.drawText(clipped, x + size * 0.5f, y + size * 1.2f + i * size * 1.35f, text)
        }
    }

    private fun ellipsize(s: String, w: Float): String {
        if (text.measureText(s) <= w) return s
        var e = s
        while (e.isNotEmpty() && text.measureText("$e…") > w) e = e.dropLast(1)
        return "$e…"
    }

    companion object {
        /** Best line green, then blue, orange, purple, grey. */
        val ARROW_COLORS = intArrayOf(0xD02E9E44.toInt(), 0xC02F7FD0.toInt(), 0xB0E08A20.toInt(),
            0xA09A4FD0.toInt(), 0x90909090.toInt())
    }
}
