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
import io.github.chessoverlay.DebugCapture
import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.MainActivity
import io.github.chessoverlay.Prefs
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.ChangeWatcher
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
 * BoardTracker.Cover). When at least two squares differ from the last read
 * and the board has not moved for STILL_MS, it is read again on a clean
 * capture (the board layer hidden for one frame, once per move). A capture
 * that does not match the still board it was taken for (a piece started
 * moving) is thrown away, so a piece in flight is never read. Misreads are
 * retried with a growing pause and never replace the analysis.
 *
 * The windows get a new position at once; further engine results for the
 * same position at most every SAME_POSITION_MS, so the lines stay readable.
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
    private val watcher = ChangeWatcher(STILL_MS, MIN_CHANGED, BLIND_MS)
    private var retryAt = 0L
    private var misses = 0

    // Redraws: a new position at once, the same position's updates throttled.
    private var pendingState: OverlayState? = null
    private var lastPush = 0L
    private var pushedKey: String? = null
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
            running = true
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
            .setSmallIcon(io.github.chessoverlay.R.drawable.ic_stat_overlay)
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
        val models = ModelStore(this)
        if (!models.ready) { postState(OverlayState(status = "Download the recognition models in the app first")); return }
        postState(OverlayState(status = "Loading the recognizer…"))
        models.prepare()
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
            arrows = prefs.arrows
            pathPlies = prefs.pathPlies
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
            // A new position, its first lines, or a message: show at once.
            val urgent = positionKey(s) != pushedKey || s.lines.isEmpty() || card?.state?.lines.isNullOrEmpty() ||
                s.status != card?.state?.status
            val wait = if (urgent) 0L else SAME_POSITION_MS - (SystemClock.uptimeMillis() - lastPush)
            main.removeCallbacks(pushState)
            if (wait <= 0) pushState.run() else main.postDelayed(pushState, wait)
        }
    }

    private fun positionKey(s: OverlayState): String =
        "${s.board?.placement()} ${s.whiteToMove} ${s.blackAtBottom} ${s.corners?.joinToString { "${it[0].toInt()},${it[1].toInt()}" }}"

    private val pushState = Runnable {
        val s = pendingState ?: return@Runnable
        pendingState = null
        lastPush = SystemClock.uptimeMillis()
        pushedKey = positionKey(s)
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
            // No board yet: look for one now and then (our layer is empty, so
            // nothing flickers), once the screen is still: a board caught in
            // a site's opening animation would be found in the wrong place.
            if (now - lastRead >= FIND_MS && screenStill(now)) read(auto = true)
            return
        }
        val px = screen?.pixels(pts.first, pts.second) ?: return
        if (watcher.offer(now, px, tracker, cover(), lastRead) == ChangeWatcher.Decision.READ) {
            DebugLog.i(TAG, "board changed")
            read(auto = true, checkCapture = true)
        }
    }

    // A coarse grid over the whole screen, for "is anything moving?" before a board is known.
    private var gridX: IntArray? = null
    private var gridY: IntArray? = null
    private var lastGrid: IntArray? = null
    private var gridStillSince = 0L

    private fun screenStill(now: Long): Boolean {
        val s = screen ?: return false
        if (gridX == null || gridX!!.size != GRID_W * GRID_H) {
            gridX = IntArray(GRID_W * GRID_H) { (it % GRID_W) * s.width / GRID_W + s.width / (2 * GRID_W) }
            gridY = IntArray(GRID_W * GRID_H) { (it / GRID_W) * s.height / GRID_H + s.height / (2 * GRID_H) }
        }
        val px = s.pixels(gridX!!, gridY!!) ?: return false
        val before = lastGrid
        lastGrid = px
        var moving = before == null
        if (before != null) {
            var diff = 0
            for (i in px.indices) {
                val a = px[i]; val b = before[i]
                val d = maxOf(kotlin.math.abs((a shr 16 and 0xff) - (b shr 16 and 0xff)),
                    kotlin.math.abs((a shr 8 and 0xff) - (b shr 8 and 0xff)), kotlin.math.abs((a and 0xff) - (b and 0xff)))
                if (d > 24) diff++
            }
            moving = diff > px.size / 100   // more than 1% of the grid: something is moving
        }
        if (moving) gridStillSince = now
        return now - gridStillSince >= FIND_STILL_MS
    }

    /** What our windows cover on screen now (their pixels are not compared). */
    private fun cover(): BoardTracker.Cover? {
        val c = cardRect?.let { RectCover(it[0], it[1], it[2], it[3]) }
        return ShapeCover.union(draw?.cover(), c)
    }

    /**
     * Read the screen without our layers on the board, then look at it.
     * `checkCapture`: throw the capture away if it does not show the board
     * as the watcher last saw it still (a piece started moving).
     */
    private fun read(auto: Boolean, checkCapture: Boolean = false) {
        val a = analyst ?: return
        busy = true
        cleanCapture(hideCard = !auto) { img ->
            guarded("analyse") {
                val moved = img != null && checkCapture && !watcher.captureMatches(img, a.board)
                val r = when {
                    img == null -> { if (!auto) a.status("No screen image yet"); Look.FAILED }
                    moved -> null
                    else -> a.look(img, auto = auto, forceLocate = !auto)
                }
                val now = SystemClock.uptimeMillis()
                watcher.reset()
                if (r == null) {
                    // A piece started moving as we captured: look again once it stops.
                    DebugLog.i(TAG, "board moved during the capture; reading again")
                } else {
                    lastRead = now
                    if (r == Look.NEW || r == Look.SAME) {
                        misses = 0; retryAt = 0L
                    } else if (auto) {
                        // Keep what the recognizer saw when it keeps failing, to share from the app.
                        if (misses >= 1 && img != null) DebugCapture.save(this@OverlayService, img, "$r")
                        // A misread or no board: try again soon, then less often.
                        misses = minOf(misses + 1, 5)
                        retryAt = now + (RETRY_MS shl (misses - 1))
                    }
                }
            }
            // The new state (if any) was posted first; now the arrows may show again.
            main.post { draw?.blank = false }
            busy = false
        }
    }

    /**
     * Clear what covers the board (the board layer is blanked; the card is
     * hidden if it lies over the board, or always with `hideCard`), wait for
     * a frame without it, grab. The card comes back at once; the board layer
     * only once the result is in (see read), so a move's old arrows do not
     * flash back before the new ones.
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
            if (hideDraw) draw?.blank = true
            if (hideCard) card?.visibility = View.INVISIBLE
            val hiddenAt = SystemClock.uptimeMillis()
            work.post(object : Runnable {
                var waited = 0L
                override fun run() {
                    val s = screen
                    if (s == null) { then(null); return }
                    if (s.latestTime > hiddenAt + 16 || waited >= HIDE_WAIT_MS) {
                        val img = s.grab()
                        main.post { card?.visibility = View.VISIBLE }
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

    /**
     * The phone was turned (or the screen otherwise changed size): capture at
     * the new size, and find the board again (it is somewhere else now).
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        if (m.widthPixels == screenW && m.heightPixels == screenH) return
        screenW = m.widthPixels
        screenH = m.heightPixels
        DebugLog.i(TAG, "screen now ${screenW}x$screenH")
        work.post {
            guarded("resize") {
                screen?.resize(m.widthPixels, m.heightPixels, m.densityDpi)
                analyst?.forget("Screen turned; looking for the board…")
                watcher.reset()
                lastRead = 0L; retryAt = 0L; misses = 0
            }
        }
        placedFor = null
        val v = card ?: return
        val lp = cardLp ?: return
        lp.width = cardWidth()
        lp.x = lp.x.coerceIn(0, maxOf(0, screenW - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, screenH - (60 * density).toInt()))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun toggleAuto() {
        auto = !auto
        work.removeCallbacks(autoTick)
        if (auto) {
            work.post { lastRead = 0L; retryAt = 0L; misses = 0; watcher.reset() }
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
        running = false
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

        /** The overlay is on (for the app's Start / Stop button). */
        @Volatile var running = false
            private set
        private const val CHANNEL = "overlay"
        private const val TAG = "Overlay"

        private const val SAME_POSITION_MS = 450L   // engine updates for one position: about 2 a second
        private const val POLL_MS = 60L             // board check, overlay showing
        private const val STILL_MS = 120L           // the board must not move for this long
        private const val MIN_CHANGED = 2           // a move changes at least two squares
        private const val FIND_MS = 1000L           // no board yet: look this often...
        private const val FIND_STILL_MS = 300L      // ...once the screen has been still this long
        private const val GRID_W = 18
        private const val GRID_H = 36
        private const val BLIND_MS = 20_000L        // squares under our drawing: look this often
        private const val RETRY_MS = 500L           // after a misread: 0.5, 1, 2, 4, 8 s
        private const val HIDE_WAIT_MS = 250L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }
    }
}
