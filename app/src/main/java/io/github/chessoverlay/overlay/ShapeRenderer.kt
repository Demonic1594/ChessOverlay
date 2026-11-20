package io.github.chessoverlay.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Paints [Shape]s on an Android canvas, and measures text the same way. */
class ShapeRenderer : TextMeasure {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()

    override fun width(text: String, size: Float, bold: Boolean): Float {
        val p = if (bold) this.bold else regular
        p.textSize = size
        return p.measureText(text)
    }

    fun draw(c: Canvas, shapes: List<Shape>) {
        for (s in shapes) when (s) {
            is Shape.Poly -> {
                path.reset()
                path.moveTo(s.pts[0], s.pts[1])
                for (i in 2 until s.pts.size step 2) path.lineTo(s.pts[i], s.pts[i + 1])
                path.close()
                fill.color = s.fill
                c.drawPath(path, fill)
                if (s.strokeWidth > 0 && s.stroke != 0) {
                    stroke.color = s.stroke; stroke.strokeWidth = s.strokeWidth
                    c.drawPath(path, stroke)
                }
            }
            is Shape.Circle -> {
                fill.color = s.fill
                c.drawCircle(s.cx, s.cy, s.r, fill)
                if (s.strokeWidth > 0 && s.stroke != 0) {
                    stroke.color = s.stroke; stroke.strokeWidth = s.strokeWidth
                    c.drawCircle(s.cx, s.cy, s.r, stroke)
                }
            }
            is Shape.Box -> {
                fill.color = s.fill
                c.drawRoundRect(s.x0, s.y0, s.x1, s.y1, s.radius, s.radius, fill)
                if (s.strokeWidth > 0 && s.stroke != 0) {
                    stroke.color = s.stroke; stroke.strokeWidth = s.strokeWidth
                    c.drawRoundRect(s.x0, s.y0, s.x1, s.y1, s.radius, s.radius, stroke)
                }
            }
            is Shape.Text -> {
                val p = if (s.bold) bold else regular
                p.textSize = s.size
                p.color = s.color
                p.textAlign = when (s.align) {
                    Align.LEFT -> Paint.Align.LEFT
                    Align.CENTER -> Paint.Align.CENTER
                    Align.RIGHT -> Paint.Align.RIGHT
                }
                c.drawText(s.text, s.x, s.y, p)
            }
        }
    }
}
