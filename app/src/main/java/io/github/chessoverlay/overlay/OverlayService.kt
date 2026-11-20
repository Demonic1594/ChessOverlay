package io.github.chessoverlay.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.MainActivity
import io.github.chessoverlay.Prefs
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.ModelStore
import io.github.chessoverlay.vision.Recognizer

/**
 * The overlay: reads the screen, follows the board, runs the engine and
 * draws over whatever app is showing. Two windows: the board layer
 * (DrawView, touch-through, arrows only) and the floating card (CardView:
 * buttons, evaluation, lines).
 *
 * Threads: main (views), "overlay-worker" (screen polling, recognition),
 * the engine host's own thread. Every handler is guarded: an error becomes
 * a status message and a DebugLog line, not a crash.
 *
 * Auto mode polls the board's change-detection points every POLL_MS with
 * the overlay showing (points under our own drawing are skipped, see
 * BoardTracker.Cover). Only when at least two squares differ from the last
 * read and the picture has stopped moving is the board read again, on a
 * clean capture: the board layer is hidden for one frame, once per move.
 * Misreads are retried with a growing pause and never replace the analysis.
 */
class OverlayService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("overlay-worker").apply { start() }
    private val work = Handler(worker.looper)

    private var projection: MediaProjection? = null
    private var screen: ScreenSource? = null
    private var draw: DrawView? = null
    private var card: CardView? = null
    private var cardLp: WindowManager.LayoutParams? = null
    private var engine: EngineHost? = null
    private var recognizer: Recognizer? = null
    private var analyst: Analyst? = null
    private var density = 1f
    private var screenW = 0
    private var screenH = 0

    @Volatile private var auto = false
    @Volatile private var shown = true
    /** The card's screen rectangle (x0, y0, x1, y1), for covers and clean captures. */
    @Volatile private var cardRect: IntArray? = null

    // Auto mode (worker thread only).
    private var busy = false
    private var lastRead = 0L
    private var previous: IntArray? = null
    private var retryAt = 0L
    private var misses = 0

    // Redraws are throttled: engine updates arrive many times a second.
    private var pendingState: OverlayState? = null
    private var lastPush = 0L
    /** Board box and card height the card was last placed for (main thread). */
    private var placedFor: IntArray? = null
    private var placedHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(filesDir)
        installCrashLogger()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundNotification()
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (projection != null) return START_NOT_STICKY  // already running
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startCapture(code, data)
            showViews()
            work.post { guarded("setup") { setUp() } }
        } catch (e: Exception) {
            DebugLog.e(TAG, "could not start the overlay", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(previous))
    }

    private class CrashLogger(val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            DebugLog.e("Crash", "uncaught in ${t.name}: ${e.stackTraceToString().take(3000)}")
            next?.uncaughtException(t, e)
        }
    }

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            DebugLog.e(TAG, "$what failed", e)
            postState(OverlayState(status = "Error ($what): ${e.message}"))
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("ChessOverlay is reading the screen")
            .setContentText("Tap to open settings")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCapture(code: Int, data: Intent) {
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DebugLog.i(TAG, "screen capture stopped by the system")
                main.post { stopSelf() }
            }
        }, main)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        screen = ScreenSource(mp, m.widthPixels, m.heightPixels, m.densityDpi, work)
        DebugLog.i(TAG, "capturing ${m.widthPixels}x${m.heightPixels}")
    }

    /** Recognizer and engine start on the worker thread (they take a moment). */
    private fun setUp() {
        val models = ModelStore(filesDir)
        if (!models.ready) { postState(OverlayState(status = "Download the recognition models in the app first")); return }
        postState(OverlayState(status = "Loading the recognizer…"))
        val rec = Recognizer(models.board, models.locator, threads = 2)
        recognizer = rec
        val reg = EngineRegistry(this)
        val all = reg.all()
        val info = all.firstOrNull { it.id == prefs.engineId } ?: all.firstOrNull()
        if (info == null) { postState(OverlayState(status = "No engine installed")); return }
        lateinit var a: Analyst
        val host = EngineHost(info.file, reg.workDir, prefs.engineOptions(info.id),
            onUpdate = { u -> a.onEngine(u) },
            onStatus = { s -> a.status(s) })
        engine = host
        a = Analyst(rec, host) { st -> postState(st) }.apply {
            lines = prefs.lines
            thinkMs = prefs.thinkMs.toLong()
            minConfidence = prefs.minConfidence
            annotate = prefs.annotate
        }
        analyst = a
        host.start()
        DebugLog.i(TAG, "ready: ${info.title}, ${prefs.lines} lines, think ${prefs.thinkMs} ms")
        postState(OverlayState(status = if (prefs.autoStart) "Looking for a board…" else "Tap ▶ to read the screen, A for auto mode"))
        main.post { if (prefs.autoStart && !auto) toggleAuto() }
    }

    private fun boardStyle() = BoardStyle(prefs.arrows, prefs.pathPlies, prefs.arrowLabels, density)

    private fun cardStyle() = CardStyle(density, prefs.evalBar, prefs.linesPanel, prefs.lines, prefs.arrows)

    // ---- state -> windows, throttled ------------------------------------

    private fun postState(s: OverlayState) {
        main.post {
            pendingState = s
            val now = SystemClock.uptimeMillis()
            val wait = DRAW_INTERVAL_MS - (now - lastPush)
            main.removeCallbacks(pushState)
            if (wait <= 0) pushState.run() else main.postDelayed(pushState, wait)
        }
    }

    private val pushState = Runnable {
        val s = pendingState ?: return@Runnable
        pendingState = null
        lastPush = SystemClock.uptimeMillis()
        draw?.state = s
        card?.state = s
        s.corners?.let { placeCard(it) }
    }

    // ---- the card ---------------------------------------------------------

    private fun cardWidth() = minOf(screenW - (16 * density).toInt(), (400 * density).toInt())

    /**
     * Until the user moves the card, keep it just above the board, or below
     * it when there is no room above (the top of the screen otherwise).
     */
    private fun placeCard(k: List<FloatArray>) {
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

    private val cardListener = object : CardView.Listener {
        private var startX = 0
        private var startY = 0
        private var moving = false

        override fun onButton(b: CardButton) {
            when (b) {
                CardButton.READ -> work.post { if (!busy) guarded("read") { read(auto = false) } }
                CardButton.AUTO -> toggleAuto()
                CardButton.SIDE -> cycleSide()
                CardButton.SHOW -> toggleShown()
                CardButton.CLOSE -> stopSelf()
            }
        }

        override fun onDrag(dx: Int, dy: Int, done: Boolean) {
            val v = card ?: return
            val lp = cardLp ?: return
            if (!moving) { startX = lp.x; startY = lp.y; moving = true }
            lp.x = (startX + dx).coerceIn(-lp.width / 2, screenW - lp.width / 2)
            lp.y = (startY + dy).coerceIn(0, maxOf(0, screenH - v.height / 2))
            runCatching { wm.updateViewLayout(v, lp) }
            if (done) {
                moving = false
                prefs.cardX = lp.x
                prefs.cardY = lp.y
                main.post { updateCardRect() }
            }
        }

        override fun onFold(collapsed: Boolean) { prefs.cardCollapsed = collapsed }
    }

    // ---- auto mode -------------------------------------------------------

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            guarded("auto") { autoStep() }
            work.postDelayed(this, POLL_MS)
        }
    }

    private fun autoStep() {
        val a = analyst ?: return
        if (busy) return
        val now = SystemClock.uptimeMillis()
        if (now < retryAt) return
        val tracker = a.board
        val pts = tracker.points()
        if (pts == null) {
            // No board yet: look for one now and then (our layer is empty, so nothing flickers).
            if (now - lastRead >= FIND_MS) read(auto = true)
            return
        }
        val px = screen?.pixels(pts.first, pts.second) ?: return
        val cover = cover()
        val vsRead = tracker.compare(px, cover)
        val before = previous
        previous = px
        val still = before != null && BoardTracker.compare(before, px, pts.first, pts.second, cover).changed == 0
        if (vsRead.changed >= MIN_CHANGED && still) {
            DebugLog.i(TAG, "${vsRead.changed} squares changed")
            read(auto = true)
        } else if (vsRead.blind > 0 && now - lastRead >= BLIND_MS) {
            // Some squares are hidden under our own drawing: look at them now and then.
            read(auto = true)
        }
    }

    /** What our windows cover on screen now (their pixels are not compared). */
    private fun cover(): BoardTracker.Cover? {
        val c = cardRect?.let { RectCover(it[0], it[1], it[2], it[3]) }
        return ShapeCover.union(draw?.cover(), c)
    }

    /** Read the screen without our layers on the board, then look at it. */
    private fun read(auto: Boolean) {
        val a = analyst ?: return
        busy = true
        cleanCapture(hideCard = !auto) { img ->
            guarded("analyse") {
                val r = when {
                    img == null -> { if (!auto) a.status("No screen image yet"); Look.FAILED }
                    else -> a.look(img, auto = auto, forceLocate = !auto)
                }
                val now = SystemClock.uptimeMillis()
                lastRead = now
                previous = null
                if (r == Look.NEW || r == Look.SAME) {
                    misses = 0; retryAt = 0L
                } else if (auto) {
                    // A misread (mid-move) or no board: try again soon, then less often.
                    misses = minOf(misses + 1, 5)
                    retryAt = now + (RETRY_MS shl (misses - 1))
                }
            }
            busy = false
        }
    }

    /**
     * Hide what covers the board (the board layer; the card too if it lies
     * over the board, or always with `hideCard`), wait for a frame without
     * it, grab, show again.
     */
    private fun cleanCapture(hideCard: Boolean, then: (Img?) -> Unit) {
        val region = analyst?.board?.region()
        val hideDraw = shown && draw?.state?.corners != null
        val c = cardRect
        val overBoard = c != null && region != null &&
            c[0] < region[2] && c[2] > region[0] && c[1] < region[3] && c[3] > region[1]
        val hideCard = hideCard || overBoard
        if (!hideDraw && !hideCard) { then(screen?.grab()); return }
        main.post {
            if (hideDraw) draw?.visibility = View.INVISIBLE
            if (hideCard) card?.visibility = View.INVISIBLE
            val hiddenAt = SystemClock.uptimeMillis()
            work.post(object : Runnable {
                var waited = 0L
                override fun run() {
                    val s = screen
                    if (s == null) { then(null); return }
                    if (s.latestTime > hiddenAt + 16 || waited >= HIDE_WAIT_MS) {
                        val img = s.grab()
                        main.post {
                            if (shown) draw?.visibility = View.VISIBLE
                            card?.visibility = View.VISIBLE
                        }
                        then(img)
                    } else {
                        waited += 16
                        work.postDelayed(this, 16)
                    }
                }
            })
        }
    }

    // ---- views -----------------------------------------------------------

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showViews() {
        val dv = DrawView(this) { boardStyle() }
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

        val cv = CardView(this, { cardStyle() }, cardListener).apply { collapsed = prefs.cardCollapsed }
        val w = cardWidth()
        val clp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.cardY >= 0) prefs.cardX.coerceIn(-w / 2, screenW - w / 2) else (screenW - w) / 2
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
        updateButtons()
    }

    private fun toggleAuto() {
        auto = !auto
        work.removeCallbacks(autoTick)
        if (auto) {
            work.post { lastRead = 0L; retryAt = 0L; misses = 0; previous = null }
            work.post { if (!busy) guarded("read") { read(auto = true) } }  // look at once
            work.postDelayed(autoTick, POLL_MS)
        }
        DebugLog.i(TAG, "auto mode ${if (auto) "on" else "off"}")
        updateButtons()
    }

    /** Side to move: automatic -> White -> Black -> automatic. */
    private fun cycleSide() {
        val a = analyst ?: return
        a.sideOverride = when (a.sideOverride) { null -> true; true -> false; false -> null }
        updateButtons()
        work.post { guarded("side") { a.reanalyse() } }
    }

    private fun toggleShown() {
        shown = !shown
        draw?.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        updateButtons()
    }

    private fun updateButtons() {
        card?.controls = CardControls(auto = auto, side = analyst?.sideOverride, shown = shown)
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "overlay closing")
        auto = false
        work.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        draw?.let { runCatching { wm.removeView(it) } }
        card?.let { runCatching { wm.removeView(it) } }
        draw = null; card = null
        val e = engine; val r = recognizer; val s = screen
        engine = null; recognizer = null; analyst = null; screen = null
        work.post {
            runCatching { e?.close() }
            runCatching { r?.close() }
            runCatching { s?.close() }
            worker.quitSafely()
        }
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "io.github.chessoverlay.STOP"
        private const val CHANNEL = "overlay"
        private const val TAG = "Overlay"

        private const val DRAW_INTERVAL_MS = 200L   // at most 5 redraws a second
        private const val POLL_MS = 250L            // board check, overlay showing
        private const val MIN_CHANGED = 2           // a move changes at least two squares
        private const val FIND_MS = 2000L           // no board yet: look this often
        private const val BLIND_MS = 12_000L        // squares under our drawing: look this often
        private const val RETRY_MS = 500L           // after a misread: 0.5, 1, 2, 4, 8 s
        private const val HIDE_WAIT_MS = 250L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }
    }
}
