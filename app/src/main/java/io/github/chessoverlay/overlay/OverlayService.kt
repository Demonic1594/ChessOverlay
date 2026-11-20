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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.chessoverlay.MainActivity
import io.github.chessoverlay.Prefs
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.engine.UciEngine
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.ModelStore
import io.github.chessoverlay.vision.Recognizer

/**
 * The overlay: reads the screen (MediaProjection), finds the board, runs the
 * selected engine and draws its lines over whatever app is showing. A small
 * bar controls it: analyse now, auto mode, side to move, show/hide, close.
 */
class OverlayService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("overlay-worker").apply { start() }
    private val work = Handler(worker.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Object()
    private var latest: Image? = null

    private var draw: DrawView? = null
    private var bar: LinearLayout? = null
    private var engine: UciEngine? = null
    private var recognizer: Recognizer? = null
    private var analyst: Analyst? = null

    @Volatile private var auto = false
    @Volatile private var busy = false
    private var shown = true
    private lateinit var autoBtn: TextView
    private lateinit var sideBtn: TextView
    private lateinit var showBtn: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundNotification()
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (projection == null && code == Activity.RESULT_OK && data != null) {
            startCapture(code, data)
            showViews()
            work.post { setUp() }
        }
        return START_NOT_STICKY
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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        val r = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 3)
        // Keep only the newest frame; frames only arrive when the screen changes.
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(frameLock) { latest?.close(); latest = img }
        }, work)
        reader = r
        display = mp.createVirtualDisplay("chessoverlay", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    /** Engine and recognizer start on the worker thread (they take a moment). */
    private fun setUp() {
        val models = ModelStore(filesDir)
        if (!models.ready) {
            status("Download the recognition models in the app first")
            return
        }
        try {
            status("Loading the recognizer…")
            val rec = Recognizer(models.board, models.locator)
            recognizer = rec
            status("Starting the engine…")
            val reg = EngineRegistry(this)
            val all = reg.all()
            val info = all.firstOrNull { it.id == prefs.engineId } ?: all.firstOrNull()
                ?: run { status("No engine installed"); return }
            val e = UciEngine(info.file, reg.workDir)
            e.start()
            for ((k, v) in prefs.engineOptions(info.id)) e.setOption(k, v)
            e.isReady()
            engine = e
            analyst = Analyst(rec, e) { st -> main.post { draw?.state = st } }.apply {
                lines = prefs.lines
                thinkMs = prefs.thinkMs.toLong()
                minConfidence = prefs.minConfidence
                annotate = prefs.annotate
            }
            status("${e.name} ready: tap ▶ to analyse, A for auto")
        } catch (ex: Exception) {
            status("Could not start: ${ex.message}")
        }
    }

    private fun status(s: String) = main.post { draw?.state = OverlayState(status = s) }

    /** Read the screen without our own drawings on it, then analyse. */
    private fun analyseNow(isAuto: Boolean) {
        val a = analyst ?: return
        if (busy) return
        busy = true
        val hideFirst = !isAuto && shown && draw?.state?.corners != null
        val hiddenForCapture = draw?.visibility == View.VISIBLE && hideFirst
        if (hiddenForCapture) draw?.visibility = View.INVISIBLE
        // Give the screen a frame to redraw without the overlay.
        work.postDelayed({
            try {
                val img = grab()
                if (hiddenForCapture) main.post { if (shown) draw?.visibility = View.VISIBLE }
                if (img != null) a.analyse(img, isAuto) else if (!isAuto) status("No screen image yet")
            } catch (ex: Exception) {
                status("Error: ${ex.message}")
            } finally {
                busy = false
            }
        }, if (hiddenForCapture) 180L else 0L)
    }

    private fun grab(): Img? = synchronized(frameLock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width; val h = image.height
        val ps = plane.pixelStride; val rs = plane.rowStride
        val out = Img(w, h)
        val row = ByteArray(rs)
        for (y in 0 until h) {
            buf.position(y * rs)
            buf.get(row, 0, minOf(rs, buf.remaining()))
            for (x in 0 until w) {
                val o = x * ps
                out.px[y * w + x] = ((row[o].toInt() and 0xff) shl 16) or
                    ((row[o + 1].toInt() and 0xff) shl 8) or (row[o + 2].toInt() and 0xff)
            }
        }
        out
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            analyseNow(true)
            main.postDelayed(this, (prefs.autoSeconds * 1000).toLong())
        }
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
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
            setOnClickListener { onClick() }
        }
        val handle = button("⠿") {}
        val bl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE0202020.toInt())
            addView(handle)
            addView(button("▶") { if (auto) toggleAuto(); analyseNow(false) })
            autoBtn = button("A") { toggleAuto() }.also { addView(it) }
            sideBtn = button("⇅") { cycleSide() }.also { addView(it) }
            showBtn = button("◐") { toggleShown() }.also { addView(it) }
            addView(button("✕") { stopSelf() })
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f); y = dp(80f)
        }
        // Drag the bar by its handle.
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
        main.removeCallbacks(autoTick)
        if (auto) main.post(autoTick)
        updateButtons()
    }

    /** Side to move: automatic -> White -> Black -> automatic. */
    private fun cycleSide() {
        val a = analyst ?: return
        a.sideOverride = when (a.sideOverride) { null -> true; true -> false; false -> null }
        updateButtons()
        if (!auto) analyseNow(false)
    }

    private fun toggleShown() {
        shown = !shown
        draw?.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        updateButtons()
    }

    @SuppressLint("SetTextI18n")
    private fun updateButtons() {
        if (!::autoBtn.isInitialized) return
        autoBtn.setTextColor(if (auto) 0xFF5FD068.toInt() else Color.WHITE)
        sideBtn.text = when (analyst?.sideOverride) { true -> "W"; false -> "B"; null -> "⇅" }
        showBtn.alpha = if (shown) 1f else 0.5f
    }

    override fun onDestroy() {
        auto = false
        main.removeCallbacks(autoTick)
        draw?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        draw = null; bar = null
        work.post {
            runCatching { analyst?.stop() }
            runCatching { engine?.quit() }
            runCatching { recognizer?.close() }
            synchronized(frameLock) { latest?.close(); latest = null }
            worker.quitSafely()
        }
        display?.release()
        reader?.close()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "io.github.chessoverlay.STOP"
        private const val CHANNEL = "overlay"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, OverlayService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }
    }
}
