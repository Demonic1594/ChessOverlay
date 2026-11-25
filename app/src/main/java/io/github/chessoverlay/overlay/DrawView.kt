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
 * a new path), not on every engine update (see [BoardDrawing]), and it can
 * say which screen pixels it covers: the current drawing and the one before
 * it, since a captured frame may still show that for a moment.
 */
class DrawView(ctx: Context, private val style: () -> BoardStyle) : View(ctx) {
    private val renderer = ShapeRenderer()
    private val drawing = BoardDrawing(style, renderer)
    private val loc = IntArray(2)
    @Volatile private var shapes: List<Shape> = emptyList()
    @Volatile private var current: ShapeCover? = null
    @Volatile private var previous: ShapeCover? = null
    @Volatile private var replacedAt = 0L

    var state: OverlayState = OverlayState()
        set(v) {
            field = v
            if (drawing.show(v)) replace(drawing.shapes)
        }

    /**
     * Shows nothing from the frame after this (main thread): the board is
     * about to be captured, and what was drawn belongs to the position
     * before. Returns whether a frame captured in the last moment may still
     * show the drawing, in which case the capture has to wait for a newer one.
     * The service keeps new drawings off until the capture is taken
     * (OverlayService.holdDrawing), so nothing flashes back in between.
     */
    fun clear(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        // Hidden with the eye button, it was never on screen: no frame has to come before the capture.
        val recent = visibility == VISIBLE && (shapes.isNotEmpty() || now - replacedAt < RECENT_MS)
        drawing.clear()
        replace(emptyList())
        return recent
    }

    private fun replace(next: List<Shape>) {
        shapes = next
        previous = current
        replacedAt = android.os.SystemClock.uptimeMillis()
        current = if (next.isEmpty()) null else ShapeCover(next, 2f * style().dp)
        postInvalidate()
    }

    /**
     * Screen pixels drawn now, and for PREVIOUS_MS after a change also those
     * of the drawing before (a captured frame may still show it). Any thread.
     */
    fun cover(): BoardTracker.Cover? {
        if (visibility != VISIBLE) return null
        val old = previous.takeIf { android.os.SystemClock.uptimeMillis() - replacedAt < PREVIOUS_MS }
        return ShapeCover.union(current, old)
    }

    override fun onDraw(c: Canvas) {
        // Shapes are in captured-screen pixels; this view may not start at (0, 0).
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        renderer.draw(c, shapes)
        probe?.let { r -> c.drawRect(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat(), probePaint) }
        c.restore()
    }

    /** A small square of PROBE_COLOR at (x0, y0, x1, y1) in screen pixels, or none: see OverlayService.probeCapture. */
    var probe: IntArray? = null
        set(v) { field = v; invalidate() }
    private val probePaint = android.graphics.Paint().apply { color = PROBE_COLOR or (0xFF shl 24); isAntiAlias = false }

    companion object {
        private const val PREVIOUS_MS = 500L
        private const val RECENT_MS = 100L
        /** Pure magenta: no board or app chrome is likely to show it exactly there. */
        const val PROBE_COLOR = 0xFF00FF
    }
}
