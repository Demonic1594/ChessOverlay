package io.github.chessoverlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The floating card (see [CardLayer]): buttons, evaluation and lines in one
 * window. Drag it anywhere to move it; tap a button to use it; tap the rest
 * to fold the lines away or back.
 */
class CardView(
    ctx: Context,
    private val style: () -> CardStyle,
    private val listener: Listener,
) : View(ctx) {
    interface Listener {
        fun onButton(b: CardButton)
        fun onDrag(dx: Int, dy: Int, done: Boolean)
        fun onFold(collapsed: Boolean)
    }

    private val renderer = ShapeRenderer()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var layout: CardLayout? = null
    private var builtFor = -1

    var state: OverlayState = OverlayState()
        set(v) { field = v; refresh() }
    var controls = CardControls()
        set(v) { field = v; refresh() }
    var collapsed = false
        set(v) { field = v; refresh() }

    private fun refresh() {
        val old = layout?.height
        val l = if (width > 0) build(width) else null
        if (l == null || l.height != old) requestLayout()
        invalidate()
    }

    private fun build(w: Int): CardLayout =
        CardLayer.build(state, w.toFloat(), style(), controls, renderer, collapsed).also { layout = it; builtFor = w }

    private fun current(w: Int) = layout?.takeIf { builtFor == w } ?: build(w)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, ceil(current(w).height).toInt())
    }

    override fun onDraw(c: Canvas) = renderer.draw(c, current(width).shapes)

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                if (dragging) listener.onDrag(dx.toInt(), dy.toInt(), false)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    listener.onDrag((e.rawX - downX).toInt(), (e.rawY - downY).toInt(), true)
                } else {
                    val b = current(width).buttonAt(e.x, e.y)
                    if (b != null) listener.onButton(b) else { collapsed = !collapsed; listener.onFold(collapsed) }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener.onDrag((e.rawX - downX).toInt(), (e.rawY - downY).toInt(), true)
                dragging = false
            }
        }
        return true
    }
}
