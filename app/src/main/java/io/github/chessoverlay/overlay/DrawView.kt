package io.github.chessoverlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.View
import io.github.chessoverlay.vision.BoardTracker

/**
 * Full-screen, touch-through layer that draws on the board only (see
 * [BoardLayer]); everything else is in the floating card.
 *
 * It redraws only when the board drawing really changes (a new best move,
 * a new path), not on every engine update, and it can say which screen
 * pixels it covers: the current drawing and the one before it, since a
 * captured frame may still show that for a moment.
 */
class DrawView(ctx: Context, private val style: () -> BoardStyle) : View(ctx) {
    private val renderer = ShapeRenderer()
    private val loc = IntArray(2)
    @Volatile private var shapes: List<Shape> = emptyList()
    @Volatile private var covers: Pair<ShapeCover?, ShapeCover?> = null to null
    private var key: String? = null

    var state: OverlayState = OverlayState()
        set(v) {
            field = v
            val st = style()
            val k = signature(v, st)
            if (k == key) return
            key = k
            val next = BoardLayer.build(v, st, renderer)
            shapes = next
            val margin = 3f * st.dp
            covers = (if (next.isEmpty()) null else ShapeCover(next, margin)) to covers.first
            postInvalidate()
        }

    /** Screen pixels drawn now or a moment ago (null = none). Any thread. */
    fun cover(): BoardTracker.Cover? {
        if (visibility != VISIBLE) return null
        val (a, b) = covers
        return ShapeCover.union(a, b)
    }

    override fun onDraw(c: Canvas) {
        // Shapes are in captured-screen pixels; this view may not start at (0, 0).
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        renderer.draw(c, shapes)
        c.restore()
    }

    companion object {
        /** What the board drawing depends on: same signature, same picture. */
        fun signature(s: OverlayState, st: BoardStyle): String {
            val k = s.corners ?: return ""
            val sb = StringBuilder()
            for (p in k) sb.append(p[0].toInt()).append(',').append(p[1].toInt()).append(';')
            sb.append(s.blackAtBottom).append(s.whiteToMove).append(st)
            s.board?.let { sb.append(it.placement()) }
            s.lastMove?.let { sb.append(it.square).append(it.label) }
            for ((i, l) in s.lines.take(st.arrows.coerceAtLeast(1)).withIndex()) {
                val n = if (i == 0) st.pathPlies + 1 else 1
                sb.append('|').append(l.pv.take(n).joinToString(" "))
                if (st.arrowLabels) sb.append(l.scoreText(s.whiteToMove))
            }
            return sb.toString()
        }
    }
}
