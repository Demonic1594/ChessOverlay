package io.github.chessoverlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.chessoverlay.Prefs

/**
 * The overlay's two windows: the board layer (DrawView, touch-through,
 * arrows only) and the floating card (CardView: buttons, evaluation,
 * lines). Places the card by the board until the user moves it, follows a
 * drag, and keeps the card's screen rectangle for covers and clean
 * captures. Main thread, except [cardRect], [draw] and [card] reads.
 */
class OverlayWindows(
    private val ctx: Context,
    private val prefs: Prefs,
    private val boardStyle: () -> BoardStyle,
    private val cardStyle: () -> CardStyle,
    private val onButton: (CardButton) -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density

    @Volatile var draw: DrawView? = null
        private set
    @Volatile var card: CardView? = null
        private set
    private var cardLp: WindowManager.LayoutParams? = null
    /** The card's screen rectangle (x0, y0, x1, y1), for covers and clean captures. */
    @Volatile var cardRect: IntArray? = null
        private set
    /** Board box and card height the card was last placed for. */
    private var placedFor: IntArray? = null
    private var placedHeight = 0
    var screenW = 0
        private set
    var screenH = 0
        private set

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun cardWidth() = minOf(screenW - (16 * density).toInt(), (400 * density).toInt())

    fun show(w: Int, h: Int) {
        screenW = w; screenH = h
        val dv = DrawView(ctx) { boardStyle() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(dv, lp)
        draw = dv

        val cv = CardView(ctx, { cardStyle() }, listener).apply { collapsed = prefs.cardCollapsed }
        val cw = cardWidth()
        val clp = WindowManager.LayoutParams(
            cw, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.cardY >= 0) prefs.cardX.coerceIn(-cw / 2, screenW - cw / 2) else (screenW - cw) / 2
            y = if (prefs.cardY >= 0) prefs.cardY.coerceIn(0, maxOf(0, screenH - (60 * density).toInt()))
                else (80 * density).toInt()
        }
        cv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCardRect()
            draw?.state?.corners?.let { placeCard(it) }
        }
        wm.addView(cv, clp)
        card = cv
        cardLp = clp
    }

    /** The screen changed size (the phone turned): the card stays on it. */
    fun resized(w: Int, h: Int) {
        screenW = w; screenH = h
        placedFor = null
        val v = card ?: return
        val lp = cardLp ?: return
        lp.width = cardWidth()
        lp.x = lp.x.coerceIn(0, maxOf(0, screenW - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, screenH - (60 * density).toInt()))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /**
     * Until the user moves the card, keep it just above the board, or below
     * it when there is no room above (the top of the screen otherwise).
     */
    fun placeCard(k: List<FloatArray>) {
        val v = card ?: return
        val lp = cardLp ?: return
        if (prefs.cardY >= 0) return
        val box = intArrayOf(k.minOf { it[0] }.toInt(), k.minOf { it[1] }.toInt(),
            k.maxOf { it[0] }.toInt(), k.maxOf { it[1] }.toInt())
        val h = if (v.height > 0) v.height else (130 * density).toInt()
        val p = placedFor
        if (p != null && p.indices.all { kotlin.math.abs(p[it] - box[it]) < 8 } && placedHeight == h) return
        placedFor = box
        placedHeight = h
        val gap = (6 * density).toInt()
        val minY = (24 * density).toInt()
        lp.y = when {
            box[1] - gap - h >= minY -> box[1] - gap - h
            box[3] + gap + h <= screenH -> box[3] + gap
            else -> minY
        }
        lp.x = ((box[0] + box[2]) / 2 - lp.width / 2).coerceIn(0, maxOf(0, screenW - lp.width))
        runCatching { wm.updateViewLayout(v, lp) }
        updateCardRect()
    }

    private fun updateCardRect() {
        val v = card ?: return
        if (v.width == 0) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        cardRect = intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** The card hidden or back (for a capture of the board under it). */
    fun cardVisible(visible: Boolean) { card?.visibility = if (visible) View.VISIBLE else View.INVISIBLE }

    /** The board layer shown or not (the card's eye button). */
    fun boardShown(shown: Boolean) { draw?.visibility = if (shown) View.VISIBLE else View.INVISIBLE }

    fun remove() {
        draw?.let { runCatching { wm.removeView(it) } }
        card?.let { runCatching { wm.removeView(it) } }
        draw = null; card = null
    }

    private val listener = object : CardView.Listener {
        private var startX = 0
        private var startY = 0
        private var moving = false

        override fun onButton(b: CardButton) = this@OverlayWindows.onButton(b)

        override fun onDrag(dx: Int, dy: Int, done: Boolean) {
            val v = card ?: return
            val lp = cardLp ?: return
            if (!moving) { startX = lp.x; startY = lp.y; moving = true }
            lp.x = (startX + dx).coerceIn(-lp.width / 2, screenW - lp.width / 2)
            lp.y = (startY + dy).coerceIn(0, maxOf(0, screenH - v.height / 2))
            runCatching { wm.updateViewLayout(v, lp) }
            // Where the card is now, while it moves: what it covers is not read as a change of the board, and a
            // capture hides it where it is. (A window moved does not lay out again, so this is set here.)
            if (v.width > 0) cardRect = intArrayOf(lp.x, lp.y, lp.x + v.width, lp.y + v.height)
            if (done) {
                moving = false
                prefs.cardX = lp.x
                prefs.cardY = lp.y
                main.post { updateCardRect() }
            }
        }

        override fun onFold(collapsed: Boolean) { prefs.cardCollapsed = collapsed }
        override fun onLongPress() = this@OverlayWindows.onLongPress()
    }
}
