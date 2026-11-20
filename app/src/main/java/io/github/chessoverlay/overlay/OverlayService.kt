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
import android.graphics.Color
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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.MainActivity
import io.github.chessoverlay.Prefs
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.ModelStore
import io.github.chessoverlay.vision.Recognizer

/**
 * The overlay: reads the screen, follows the board, runs the engine and
 * draws over whatever app is showing.
 *
 * Threads: main (views), "overlay-worker" (frames, recognition, auto mode),
 * the engine host's own thread. Every handler is guarded: an error becomes
 * a status message and a DebugLog line, not a crash.
 *
 * Auto mode is driven by the screen: a frame that we did not cause by
 * redrawing marks the screen dirty; once frames stop for SETTLE_MS (moves
 * and animations done) the board is checked on a clean capture (our layer
 * hidden for about one frame), and only if a square changed is it read again.
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
    private var bar: LinearLayout? = null
    private var engine: EngineHost? = null
    private var recognizer: Recognizer? = null
    private var analyst: Analyst? = null

    // Auto mode bookkeeping (worker thread, except where @Volatile).
    @Volatile private var auto = false
    @Volatile private var shown = true
    @Volatile private var lastOwnDraw = 0L      // last time our layer changed on screen
    private var dirtySince = 0L                 // first external frame since the last check
    private var lastExternal = 0L               // newest external frame
    private var maskedFrames = 0                // frames we could not attribute (during our redraws)
    private var lastCheck = 0L
    private var busy = false

    // Overlay redraws are throttled: engine updates arrive many times a second.
    private var pendingState: OverlayState? = null
    private var lastPush = 0L

    private lateinit var autoBtn: TextView
    private lateinit var sideBtn: TextView
    private lateinit var showBtn: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(filesDir)
        installCrashLogger()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
        val s = ScreenSource(mp, m.widthPixels, m.heightPixels, m.densityDpi, work)
        s.onFrame = { t -> onFrame(t) }
        screen = s
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
        postState(OverlayState(status = "Ready. Auto mode ${if (prefs.autoStart) "on" else "off (tap A)"}; ▶ reads the screen now"))
        main.post { if (prefs.autoStart && !auto) toggleAuto() }
    }

    // ---- state -> overlay, throttled ------------------------------------

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
        lastOwnDraw = lastPush
        draw?.state = s
    }

    // ---- auto mode -------------------------------------------------------

    /** Worker thread: a new frame arrived. */
    private fun onFrame(t: Long) {
        if (t - lastOwnDraw < OWN_FRAME_MS) { maskedFrames++; return }
        if (dirtySince == 0L) dirtySince = t
        lastExternal = t
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            guarded("auto") { autoStep() }
            work.postDelayed(this, TICK_MS)
        }
    }

    private fun autoStep() {
        if (busy || analyst == null) return
        val now = SystemClock.uptimeMillis()
        val settled = dirtySince != 0L && now - lastExternal >= SETTLE_MS
        // Our redraws may have hidden a change: check once things are quiet.
        val maskedCheck = maskedFrames > 0 && now - lastOwnDraw > QUIET_MS && now - lastCheck > MASKED_CHECK_MS
        val periodic = now - lastCheck > PERIODIC_MS
        if (settled || maskedCheck || periodic) check(auto = true)
    }

    /** Read the screen without our layer on it, then look at the board. */
    private fun check(auto: Boolean) {
        val a = analyst ?: return
        busy = true
        dirtySince = 0L
        maskedFrames = 0
        lastCheck = SystemClock.uptimeMillis()
        cleanCapture { img ->
            guarded("analyse") {
                if (img == null) {
                    if (!auto) a.status("No screen image yet")
                } else if (!auto) {
                    a.look(img, auto = false, forceLocate = true)
                } else if (a.boardChanged(img)) {
                    a.look(img, auto = true)
                }
            }
            busy = false
        }
    }

    /** Hide our layer, wait for a frame without it, grab, show the layer. */
    private fun cleanCapture(then: (Img?) -> Unit) {
        val needHide = shown && draw?.state?.corners != null
        if (!needHide) { then(screen?.grab()); return }
        main.post {
            lastOwnDraw = SystemClock.uptimeMillis()
            draw?.visibility = View.INVISIBLE
            val hiddenAt = SystemClock.uptimeMillis()
            work.post(object : Runnable {
                var waited = 0L
                override fun run() {
                    val s = screen
                    if (s == null) { then(null); return }
                    if (s.latestTime > hiddenAt + 16 || waited >= HIDE_WAIT_MS) {
                        val img = s.grab()
                        main.post {
                            lastOwnDraw = SystemClock.uptimeMillis()
                            if (shown) draw?.visibility = View.VISIBLE
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showViews() {
        val dv = DrawView(this, prefs)
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

        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setOnClickListener { try { onClick() } catch (e: Exception) { DebugLog.e(TAG, "button $label", e) } }
        }
        val handle = button("⠿") {}
        val bl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE0202020.toInt())
            addView(handle)
            addView(button("▶") { work.post { if (!busy) guarded("read") { check(auto = false) } } })
            autoBtn = button("A") { toggleAuto() }.also { addView(it) }
            sideBtn = button("⇅") { cycleSide() }.also { addView(it) }
            showBtn = button("◐") { toggleShown() }.also { addView(it) }
            addView(button("✕") { stopSelf() })
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12f); y = dp(80f) }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = blp.x; sy = blp.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    blp.x = sx + (e.rawX - tx).toInt(); blp.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(bl, blp); true
                }
                else -> false
            }
        }
        wm.addView(bl, blp)
        bar = bl
        updateButtons()
    }

    private fun toggleAuto() {
        auto = !auto
        work.removeCallbacks(autoTick)
        if (auto) {
            work.post { lastCheck = 0L }  // check at once
            work.post(autoTick)
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
        if (!::autoBtn.isInitialized) return
        autoBtn.setTextColor(if (auto) 0xFF5FD068.toInt() else Color.WHITE)
        sideBtn.text = when (analyst?.sideOverride) { true -> "W"; false -> "B"; null -> "⇅" }
        showBtn.alpha = if (shown) 1f else 0.5f
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "overlay closing")
        auto = false
        work.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        draw?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        draw = null; bar = null
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

        private const val DRAW_INTERVAL_MS = 200L   // at most 5 overlay redraws a second
        private const val OWN_FRAME_MS = 120L       // frames this soon after our redraw are ours
        private const val TICK_MS = 150L
        private const val SETTLE_MS = 350L          // the screen must be still this long
        private const val QUIET_MS = 1200L          // ... and our layer this long, for a masked check
        private const val MASKED_CHECK_MS = 2500L
        private const val PERIODIC_MS = 15_000L
        private const val HIDE_WAIT_MS = 250L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }
    }
}
