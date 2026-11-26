package io.github.chessoverlay.overlay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
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
import io.github.chessoverlay.vision.RecognizerMode

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
 * capture (the board layer is taken off when the read starts and kept off
 * until the capture is taken). A capture
 * that does not match the still board it was taken for (a piece started
 * moving) is thrown away, so a piece in flight is never read. Misreads are
 * retried with a growing pause and never replace the analysis.
 *
 * Before that capture, the squares that changed usually show the move on
 * their own (BoardTracker.explainChange): the engine starts on it at once,
 * the arrows come back as soon as the capture is taken, and the recognizer
 * then checks the move on that capture (and corrects it if needed).
 *
 * The windows get a new position at once; further engine results for the
 * same position at most every SAME_POSITION_MS, so the lines stay readable.
 */
class OverlayService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("overlay-worker").apply {
        start()
        // Reading the board runs while the engine searches on most cores: a little ahead of the engine's threads,
        // so a move is read on time in a fast game. Where the phone refuses, the default stays.
        Handler(looper).post {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
                .onFailure { DebugLog.w("Overlay", "reading thread priority unchanged: ${it.message}") }
        }
    }
    private val work = Handler(worker.looper)

    private var projection: MediaProjection? = null
    private var screen: ScreenSource? = null
    private lateinit var windows: OverlayWindows
    private val draw get() = windows.draw
    private val card get() = windows.card
    private var engine: EngineHost? = null
    private var recognizer: Recognizer? = null
    @Volatile private var analyst: Analyst? = null
    private var density = 1f
    private var screenW = 0
    private var screenH = 0

    @Volatile private var auto = false
    /** onDestroy() ran: a setUp() still running starts nothing more. */
    @Volatile private var destroyed = false
    @Volatile private var shown = true
    /** While a read's capture is pending, the board layer shows nothing whatever the analyst publishes (see captureBoard). */
    @Volatile private var holdDrawing = false
    /**
     * Our windows are not in the capture (one app shared, Android 14 and later): a read takes the frame as it is,
     * without taking the drawing off first or waiting for a frame without it, and the board's points under the
     * drawing are watched like the others. Found by probeCapture().
     */
    @Volatile private var cleanCapture = false

    // Auto mode (worker thread only).
    private var busy = false
    private val watcher = ChangeWatcher(STILL_MS, MIN_CHANGED, BLIND_MS)
    private val pacing = ReadPacing()
    private val motion = ScreenMotion()

    // Redraws: a new position at once, the same position's updates throttled.
    private var pendingState: OverlayState? = null
    private var lastPush = 0L
    private var pushedKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(filesDir)
        installCrashLogger()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        windows = OverlayWindows(this, prefs, { boardStyle() }, { cardStyle() }, ::onButton, ::copyPosition)
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
            windows.show(screenW, screenH)
            updateButtons()
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

    private var lastError: String? = null
    private var lastErrorAt = 0L

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            DebugLog.e(TAG, "$what failed", e)
            // The same error at every board check would flicker the card with
            // it (and hide the lines each time): it is shown again only after two seconds.
            val message = "Error ($what): ${e.message}"
            val now = SystemClock.uptimeMillis()
            if (message != lastError || now - lastErrorAt > 2000) postState(OverlayState(status = message))
            lastError = message
            lastErrorAt = now
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
        val reg = EngineRegistry(this)
        val all = reg.all()
        val info = all.firstOrNull { it.id == prefs.engineId } ?: all.firstOrNull()
        if (info == null) { postState(OverlayState(status = "No engine installed")); return }
        // Until the analyst exists (the engine may be started to measure recognition first), the engine's news is not for anyone.
        var ready: Analyst? = null
        val host = EngineHost(info.file, reg.workDir, prefs.engineOptions(info.id),
            onUpdate = { u -> ready?.onEngine(u) },
            onStatus = { s -> ready?.status(s) },
            defaultHashMb = OverlaySetup.hashForPhone(this), alternative = info.alternative)
        engine = host
        val rec = Recognizer(models.board, models.locator, OverlaySetup.howToRead(this, prefs, models, host) { postState(OverlayState(status = it)) })
        recognizer = rec
        if (rec.mode != RecognizerMode.DEFAULT) prefs.recognizerInUse = rec.mode.toString()
        val a = Analyst(rec, host) { st -> postState(st) }.apply {
            lines = prefs.lines
            thinkMs = prefs.thinkMs.toLong()
            minConfidence = prefs.minConfidence
            annotate = prefs.annotate
            arrows = prefs.arrows
            pathPlies = prefs.pathPlies
        }
        analyst = a
        ready = a
        if (destroyed) return   // closed while starting: onDestroy's cleanup on this thread closes what is made
        host.start()
        DebugLog.i(TAG, "ready: ${info.title}, ${prefs.lines} lines, think ${prefs.thinkMs} ms")
        postState(OverlayState(status = if (prefs.autoStart) "Looking for a board…" else "Tap ▶ to read the screen, A for auto mode"))
        main.post { if (!destroyed && prefs.autoStart && !auto) toggleAuto() }
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
        if (!holdDrawing) draw?.state = s
        card?.state = s
        s.corners?.let { windows.placeCard(it) }
        lastPosition = analyst?.position()
    }

    // ---- the card's buttons ------------------------------------------------

    private fun onButton(b: CardButton) {
        when (b) {
            CardButton.READ -> work.post { if (!busy) guarded("read") { read(auto = false) } }
            CardButton.AUTO -> toggleAuto()
            CardButton.SIDE -> cycleSide()
            CardButton.SHOW -> toggleShown()
            CardButton.CLOSE -> stopSelf()
        }
    }

    /** A finger kept on the card: the position as a FEN in the clipboard, for pasting anywhere. */
    private fun copyPosition() {
        val fen = analyst?.position()?.fen ?: return
        try {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ChessOverlay position", fen))
            DebugLog.i(TAG, "position copied: $fen")
            analyst?.flash(COPIED)
            work.postDelayed({ analyst?.unflash(COPIED) }, FLASH_MS)
        } catch (e: Exception) {
            DebugLog.e(TAG, "could not copy the position", e)
            analyst?.flash("Could not copy the position")
        }
    }

    // ---- auto mode -------------------------------------------------------

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            guarded("auto") { autoStep() }
            work.postDelayed(this, if (analyst?.board?.fastGame() == true) POLL_MS_FAST else POLL_MS)
        }
    }

    private fun autoStep() {
        val a = analyst ?: return
        if (busy) return
        // In a fast game (moves less than 1.5 s apart of late) the board is checked more often and read after
        // a shorter stillness: pieces in flight are still not read (AutoReadTimingTest), and a move lands
        // sooner in the analysis.
        watcher.stillMs = if (a.board.fastGame()) STILL_MS_FAST else STILL_MS
        val now = SystemClock.uptimeMillis()
        val tracker = a.board
        val pts = tracker.points()
        if (pts == null) {
            // No board yet: look for one now and then (our layer is empty, so
            // nothing flickers), once the screen is still: a board caught in
            // a site's opening animation would be found in the wrong place.
            // Not after the pause of misreads: a board coming back (after a
            // notification) is looked for within FIND_MS, not up to 8 s later.
            if (pacing.mayFind(now) && screenStill(now)) read(auto = true)
            return
        }
        if (!pacing.mayRead(now)) return
        val s = screen ?: return
        // No new frame since the last check (Android sends one only when the screen changes): the same pixels,
        // not read again. The watcher still gets them, as its stillness wait counts on.
        val px = polled?.takeIf { s.latestTime == polledTime && polledPts === pts.first }
            ?: (s.pixels(pts.first, pts.second) ?: return).also { polled = it; polledTime = s.latestTime; polledPts = pts.first }
        if (watcher.offer(now, px, tracker, cover(), pacing.lastRead) == ChangeWatcher.Decision.READ) {
            DebugLog.i(TAG, "board changed")
            read(auto = true, checkCapture = true)
        }
    }

    /** The board points' pixels at the last check, from the frame that arrived at `polledTime`. */
    private var polled: IntArray? = null
    private var polledTime = -1L
    private var polledPts: Any? = null

    /**
     * Whether our windows are in the screen capture (worker thread). A small magenta square is put up off the
     * board, and the frames that come after it are looked at every PROBE_TICK_MS (CaptureProbe): when the whole
     * screen is shared it is in them; when one app is shared (Android 14 and later lets the user choose), it is
     * not, and reads need not hide the drawing. Nothing waits for the answer: reads and checks go on meanwhile.
     */
    private fun probeCapture() {
        if (!probe.mayStart || !shown || screen == null) return
        val r = CaptureProbe.place(screenW, screenH, analyst?.board?.region(), windows.cardRect, PROBE_PX) ?: return
        probeRect = r
        probe.start(SystemClock.uptimeMillis())
        main.post { draw?.probe = r; probe.drawn = SystemClock.uptimeMillis() }
        work.removeCallbacks(probeTick)
        work.postDelayed(probeTick, PROBE_TICK_MS)
    }

    private val probeTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            guarded("probe") { checkProbe() }
            if (probe.running) work.postDelayed(this, PROBE_TICK_MS)
        }
    }

    private fun checkProbe() {
        val s = screen
        val r = probeRect
        if (s == null || r == null || !shown) {
            // Hidden with the eye button, the square is not drawn: a frame without it would prove nothing.
            probe.cancel(); main.post { draw?.probe = null }; probeAgain = true
            return
        }
        val x = (r[0] + r[2]) / 2; val y = (r[1] + r[3]) / 2
        val answer = probe.offer(SystemClock.uptimeMillis(), s.latestTime) {
            val px = s.pixels(intArrayOf(x - 2, x + 2, x, x - 2, x + 2), intArrayOf(y - 2, y - 2, y, y + 2, y + 2))
            px != null && px.count { p ->
                maxOf(kotlin.math.abs((p shr 16 and 0xff) - 0xff), kotlin.math.abs(p shr 8 and 0xff),
                    kotlin.math.abs((p and 0xff) - 0xff)) <= 24
            } >= 4
        } ?: return
        main.post { draw?.probe = null }
        DebugLog.i(TAG, "capture probe: " + when (answer) {
            CaptureProbe.Answer.IN_CAPTURE -> "square seen"
            CaptureProbe.Answer.NOT_IN_CAPTURE -> "square not seen"
            CaptureProbe.Answer.NOT_KNOWN -> "no new frame, not known"
        })
        // Clean only when two probes, at two moments, saw frames without the square (a window that came over it
        // once, a notification, would otherwise pass for one app shared). Not known: tried again after a good read.
        val clean = when (answer) {
            CaptureProbe.Answer.IN_CAPTURE -> { notSeen = 0; false }
            CaptureProbe.Answer.NOT_IN_CAPTURE -> ++notSeen >= 2
            CaptureProbe.Answer.NOT_KNOWN -> cleanCapture
        }
        probeAgain = answer == CaptureProbe.Answer.NOT_KNOWN
        if (answer == CaptureProbe.Answer.NOT_IN_CAPTURE && !clean) probeCapture()
        if (clean != cleanCapture) DebugLog.i(TAG, if (clean) "our windows are not in the capture (one app shared): reads take the frame as it is"
            else "our windows are in the capture: reads take the drawing off first")
        cleanCapture = clean
    }

    private val probe = CaptureProbe()
    private var probeRect: IntArray? = null
    /** Probes that saw frames without the square, in a row. */
    private var notSeen = 0
    /** No probe has answered yet, or the last saw no new frame: probe after the next good read (a board is known then, so the square can go off it). */
    private var probeAgain = true

    /** Before a board is known: whether the screen has been still a moment (see ScreenMotion). */
    private fun screenStill(now: Long): Boolean {
        val s = screen ?: return false
        val (xs, ys) = motion.points(s.width, s.height)
        val px = s.pixels(xs, ys) ?: return false
        return motion.offer(now, px)
    }

    /** What our windows cover on screen now (their pixels are not compared). */
    private fun cover(): BoardTracker.Cover? {
        if (cleanCapture) return null   // our windows are not in the capture: nothing of the board is hidden
        val c = windows.cardRect?.let { RectCover(it[0], it[1], it[2], it[3]) }
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
        // The move from the changed squares alone, analysed before the capture.
        val still = watcher.still
        val fast = auto && checkCapture && still != null && try {
            a.fastLook(still, watcher.stillCover)
        } catch (e: Throwable) {
            DebugLog.e(TAG, "move from the changed squares failed", e)
            false
        }
        var drawingBackDone = false
        captureBoard { img ->
          try {
            if (fast) {
                // The new position is published: its drawing (what the engine expected, if anything) may show
                // while the recognizer checks it.
                drawingBack()
                drawingBackDone = true
            }
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
                    if (fast) a.dropFast()
                } else {
                    // Keep the screen when a board is read wrongly time and again, to share from the app. Not when
                    // there is no board: that is any other screen the user went to.
                    if (auto && (r == Look.INVALID || r == Look.FAILED) && pacing.misses >= 1 && img != null)
                        DebugCapture.save(this@OverlayService, img, "$r")
                    // A misread or no board: try again soon, then less often.
                    pacing.done(now, r, auto)
                    if (probeAgain && r == Look.NEW && !probe.running) { probeAgain = false; probeCapture() }
                }
            }
          } finally {
            // Even if the thread's last act was an error: a busy overlay reads nothing more, and the drawing follows the state again.
            busy = false
            if (!drawingBackDone) drawingBack()
          }
        }
    }

    /** After a capture: the board layer follows the overlay's state again (the new position's lines, or those of the position before if it did not change). */
    private fun drawingBack() {
        main.post {
            holdDrawing = false
            analyst?.let { draw?.state = it.state() }
        }
    }

    /**
     * Take the screen without our drawing on the board, then look at it.
     *
     * The drawing goes when the read starts (it belongs to the position
     * before, and must be neither captured nor flash back before the new
     * one), and the overlay keeps it off until the capture is taken: there is
     * no frame in which it is blanked and put back. The card is hidden for
     * the capture only if it lies over the board.
     */
    private fun captureBoard(then: (Img?) -> Unit) {
        if (cleanCapture) {
            // The capture shows the app alone: the newest frame is the board as it is.
            then(screen?.grab())
            return
        }
        val region = analyst?.board?.region()
        val c = windows.cardRect
        // With no board known (looking for it) the card may lie over it anywhere: hidden then too.
        val hideCard = c != null && (region == null ||
            c[0] < region[2] && c[2] > region[0] && c[1] < region[3] && c[3] > region[1])
        holdDrawing = true
        main.post {
            // A frame captured in the last moment may still show the drawing: then a newer one is waited for.
            // So may a frame from before the card was hidden over the board.
            val drawn = draw?.clear() == true
            val hiddenAt = SystemClock.uptimeMillis() - if (drawn || hideCard) 0L else HIDE_WAIT_MS
            if (hideCard) windows.cardVisible(false)
            work.post(object : Runnable {
                var waited = 0L
                override fun run() {
                    val s = screen
                    if (s == null) { then(null); return }
                    if (s.latestTime > hiddenAt + 16 || waited >= HIDE_WAIT_MS) {
                        // The frame is in: the card may come back (the copying of the frame takes longer).
                        main.post { windows.cardVisible(true) }
                        val t0 = SystemClock.uptimeMillis()
                        val img = s.grab()
                        val copy = SystemClock.uptimeMillis() - t0
                        // Only when it costs: a capture that is slow to come or to copy delays every read.
                        if (waited + copy >= SLOW_CAPTURE_MS) DebugLog.i(TAG, "capture: frame after $waited ms, copied in $copy ms")
                        then(img)
                    } else {
                        waited += 16
                        work.postDelayed(this, 16)
                    }
                }
            })
        }
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
                // The board moves: the probes start over once it is found again.
                probe.reset(); notSeen = 0; probeAgain = true
                main.post { draw?.probe = null }
                analyst?.forget("Screen turned; looking for the board…")
                watcher.reset()
                pacing.reset()
            }
        }
        windows.resized(screenW, screenH)
    }

    private fun toggleAuto() {
        auto = !auto
        work.removeCallbacks(autoTick)
        if (auto) {
            work.post { pacing.reset(); watcher.reset() }
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
        windows.boardShown(shown)
        updateButtons()
    }

    private fun updateButtons() {
        card?.controls = CardControls(auto = auto, side = analyst?.sideOverride, shown = shown)
    }

    override fun onDestroy() {
        DebugLog.i(TAG, "overlay closing")
        analyst?.let { a -> runCatching { a.logStats() } }
        running = false
        auto = false
        work.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        windows.remove()
        destroyed = true
        prefs.recognizerInUse = null
        // On the worker: a setUp() still running there (the first start measures for seconds) finishes first, and
        // what it made is closed too, instead of an engine process and the models left running.
        work.post {
            val e = engine; val r = recognizer; val s = screen
            engine = null; recognizer = null; analyst = null; screen = null
            runCatching { e?.close() }
            runCatching { r?.close() }
            runCatching { s?.close() }
            prefs.recognizerInUse = null
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
        /** The position the overlay saw last, for the app's position card; it stays after the overlay stops. */
        @Volatile var lastPosition: Analyst.Position? = null
            internal set
        private const val COPIED = "Position copied (FEN)"
        private const val FLASH_MS = 2500L
        private const val CHANNEL = "overlay"
        private const val TAG = "Overlay"

        private const val SAME_POSITION_MS = 450L   // engine updates for one position: about 2 a second
        private const val POLL_MS = 30L             // board check, overlay showing
        private const val POLL_MS_FAST = 20L        // ... in a fast game
        private const val STILL_MS = 80L            // the board must not move for this long
        private const val STILL_MS_FAST = 55L       // ... in a fast game
        private const val MIN_CHANGED = 2           // a move changes at least two squares
        private const val BLIND_MS = 20_000L        // squares under our drawing: look this often
        private const val SLOW_CAPTURE_MS = 60L
        private const val HIDE_WAIT_MS = 250L
        /** The capture probe's square, and how often the frames after it are looked at. */
        private const val PROBE_PX = 12
        private const val PROBE_TICK_MS = 40L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }
    }
}
