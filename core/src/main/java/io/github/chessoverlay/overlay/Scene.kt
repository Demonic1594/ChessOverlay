package io.github.chessoverlay.overlay

import io.github.chessoverlay.vision.BoardTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What the overlay draws, as plain shapes in pixels.
 *
 * BoardLayer and PanelLayer build shapes without touching Android, so the
 * layout is unit-tested and can be rendered off the device for review;
 * DrawView and PanelView only paint them. Change detection asks the same
 * shapes which screen pixels the overlay covers (ShapeCover).
 */
sealed class Shape {
    /** A closed polygon: x0, y0, x1, y1, … */
    class Poly(val pts: FloatArray, val fill: Int, val stroke: Int = 0, val strokeWidth: Float = 0f) : Shape()

    class Circle(val cx: Float, val cy: Float, val r: Float, val fill: Int,
                 val stroke: Int = 0, val strokeWidth: Float = 0f) : Shape()

    /** A rectangle with rounded corners of `radius`. */
    class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val radius: Float, val fill: Int,
              val stroke: Int = 0, val strokeWidth: Float = 0f) : Shape()

    /** Text on baseline `y`; `x` is its left edge, centre or right edge by `align`; `width` as measured. */
    class Text(val x: Float, val y: Float, val text: String, val size: Float, val color: Int,
               val bold: Boolean, val align: Align, val width: Float) : Shape() {
        val left: Float get() = when (align) { Align.LEFT -> x; Align.CENTER -> x - width / 2; Align.RIGHT -> x - width }
    }
}

enum class Align { LEFT, CENTER, RIGHT }

/** Text width in pixels for a font size in pixels (each platform measures its own font). */
fun interface TextMeasure {
    fun width(text: String, size: Float, bold: Boolean): Float
}

class P(val x: Float, val y: Float)

/**
 * The board's squares on screen, from its corners as drawn (top-left,
 * top-right, bottom-right, bottom-left). Squares are 0..63 with a1 = 0;
 * columns and rows count from the top-left square as drawn.
 */
class BoardGeom(private val k: List<FloatArray>, val blackAtBottom: Boolean) {
    /** Side of one square in pixels (mean of the four edges). */
    val sq: Float = (hypot(k[1][0] - k[0][0], k[1][1] - k[0][1]) + hypot(k[2][0] - k[1][0], k[2][1] - k[1][1]) +
        hypot(k[3][0] - k[2][0], k[3][1] - k[2][1]) + hypot(k[0][0] - k[3][0], k[0][1] - k[3][1])) / 32f

    val left = k.minOf { it[0] }
    val right = k.maxOf { it[0] }
    val top = k.minOf { it[1] }
    val bottom = k.maxOf { it[1] }

    fun col(square: Int) = if (blackAtBottom) 7 - square % 8 else square % 8
    fun row(square: Int) = if (blackAtBottom) square / 8 else 7 - square / 8

    /** Screen point at (column, row) in squares from the board's top-left corner as drawn. */
    fun at(c: Float, r: Float): P {
        val u = c / 8f
        val v = r / 8f
        val x = (1 - u) * (1 - v) * k[0][0] + u * (1 - v) * k[1][0] + u * v * k[2][0] + (1 - u) * v * k[3][0]
        val y = (1 - u) * (1 - v) * k[0][1] + u * (1 - v) * k[1][1] + u * v * k[2][1] + (1 - u) * v * k[3][1]
        return P(x, y)
    }

    /** Centre of `square`, moved by (dc, dr) squares as drawn. */
    fun centre(square: Int, dc: Float = 0f, dr: Float = 0f): P = at(col(square) + 0.5f + dc, row(square) + 0.5f + dr)
}

/**
 * Arrow outline along a polyline (two points, or three for a knight's L):
 * a shaft of `width` with mitred bends, then a head of `headLength` and
 * `headWidth` whose tip is the last point.
 */
fun arrowOutline(path: List<P>, width: Float, headLength: Float, headWidth: Float): FloatArray {
    val n = path.size
    val last = path[n - 1]
    val prev = path[n - 2]
    val segLen = hypot(last.x - prev.x, last.y - prev.y)
    val hl = min(headLength, segLen * 0.6f)
    val dx = (last.x - prev.x) / segLen
    val dy = (last.y - prev.y) / segLen
    val base = P(last.x - dx * hl, last.y - dy * hl)
    // Left normals of each segment, the shaft ending at the head's base.
    val pts = path.toMutableList().also { it[n - 1] = base }
    val normals = (0 until n - 1).map { i ->
        val ex = pts[i + 1].x - pts[i].x
        val ey = pts[i + 1].y - pts[i].y
        val l = max(1e-3f, hypot(ex, ey))
        P(-ey / l, ex / l)
    }
    val h = width / 2
    fun side(sign: Float): List<P> {
        val out = ArrayList<P>()
        out.add(P(pts[0].x + sign * h * normals[0].x, pts[0].y + sign * h * normals[0].y))
        for (i in 1 until n - 1) {
            // Mitre: along the bisector of the two normals, long enough to
            // stay `h` away from both edges.
            var mx = normals[i - 1].x + normals[i].x
            var my = normals[i - 1].y + normals[i].y
            val ml = max(1e-3f, hypot(mx, my))
            mx /= ml; my /= ml
            val scale = h / max(0.2f, mx * normals[i].x + my * normals[i].y)
            out.add(P(pts[i].x + sign * mx * scale, pts[i].y + sign * my * scale))
        }
        val nl = normals[n - 2]
        out.add(P(base.x + sign * h * nl.x, base.y + sign * h * nl.y))
        out.add(P(base.x + sign * headWidth * nl.x, base.y + sign * headWidth * nl.y))
        return out
    }
    val left = side(1f)
    val right = side(-1f).asReversed()
    val all = left + listOf(last) + right
    val arr = FloatArray(all.size * 2)
    for ((i, p) in all.withIndex()) { arr[2 * i] = p.x; arr[2 * i + 1] = p.y }
    return arr
}

/**
 * The pixels a list of shapes covers, widened by `margin` (anti-aliasing,
 * and a frame that still shows the previous drawing for a moment).
 */
class ShapeCover(shapes: List<Shape>, private val margin: Float) : BoardTracker.Cover {
    private class Item(val shape: Shape, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private val items: List<Item> = shapes.mapNotNull { s ->
        when (s) {
            is Shape.Poly -> {
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (i in s.pts.indices step 2) {
                    x0 = min(x0, s.pts[i]); x1 = max(x1, s.pts[i]); y0 = min(y0, s.pts[i + 1]); y1 = max(y1, s.pts[i + 1])
                }
                val m = margin + s.strokeWidth / 2
                Item(s, x0 - m, y0 - m, x1 + m, y1 + m)
            }
            is Shape.Circle -> {
                val r = s.r + s.strokeWidth / 2 + margin
                Item(s, s.cx - r, s.cy - r, s.cx + r, s.cy + r)
            }
            is Shape.Box -> Item(s, s.x0 - margin, s.y0 - margin, s.x1 + margin, s.y1 + margin)
            is Shape.Text -> Item(s, s.left - margin, s.y - s.size - margin, s.left + s.width + margin, s.y + s.size * 0.3f + margin)
        }
    }

    val isEmpty get() = items.isEmpty()

    override fun covered(x: Int, y: Int): Boolean {
        val px = x + 0.5f
        val py = y + 0.5f
        for (it in items) {
            if (px < it.x0 || px > it.x1 || py < it.y0 || py > it.y1) continue
            when (val s = it.shape) {
                is Shape.Poly -> if (insideOrNear(s.pts, px, py, margin + s.strokeWidth / 2)) return true
                is Shape.Circle -> if (hypot(px - s.cx, py - s.cy) <= s.r + s.strokeWidth / 2 + margin) return true
                else -> return true  // boxes and text: the bounding box
            }
        }
        return false
    }

    companion object {
        /** Union of covers (null entries ignored). */
        fun union(vararg covers: BoardTracker.Cover?): BoardTracker.Cover? {
            val list = covers.filterNotNull()
            if (list.isEmpty()) return null
            if (list.size == 1) return list[0]
            return BoardTracker.Cover { x, y -> list.any { it.covered(x, y) } }
        }

        private fun insideOrNear(p: FloatArray, x: Float, y: Float, d: Float): Boolean {
            var inside = false
            val n = p.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = p[2 * i]; val yi = p[2 * i + 1]; val xj = p[2 * j]; val yj = p[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                if (segmentDistance(x, y, xi, yi, xj, yj) <= d) return true
                j = i
            }
            return inside
        }

        private fun segmentDistance(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0; val dy = y1 - y0
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6f) 0f else (((x - x0) * dx + (y - y0) * dy) / l2).coerceIn(0f, 1f)
            return hypot(x - (x0 + t * dx), y - (y0 + t * dy))
        }
    }
}

/** Rectangle-only cover, e.g. the floating card when it lies over the board. */
class RectCover(private val x0: Int, private val y0: Int, private val x1: Int, private val y1: Int) : BoardTracker.Cover {
    override fun covered(x: Int, y: Int) = x in x0 until x1 && y in y0 until y1
}

internal fun Int.withAlpha(a: Int): Int = (this and 0xFFFFFF) or (a shl 24)

/** The shape this much less opaque: a drawing that is only what the engine expected. */
fun Shape.alphaScaled(f: Float): Shape = when (this) {
    is Shape.Poly -> Shape.Poly(pts, fill.scaled(f), stroke.scaled(f), strokeWidth)
    is Shape.Circle -> Shape.Circle(cx, cy, r, fill.scaled(f), stroke.scaled(f), strokeWidth)
    is Shape.Box -> Shape.Box(x0, y0, x1, y1, radius, fill.scaled(f), stroke.scaled(f), strokeWidth)
    is Shape.Text -> Shape.Text(x, y, text, size, color.scaled(f), bold, align, width)
}

private fun Int.scaled(f: Float): Int = (this and 0xFFFFFF) or ((((this ushr 24) * f).toInt()).coerceIn(0, 255) shl 24)

internal fun near(a: Float, b: Float, eps: Float) = abs(a - b) <= eps
