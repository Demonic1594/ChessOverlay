package io.github.chessoverlay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.EngineCheck
import io.github.chessoverlay.engine.EngineInfo
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.BoardStyle
import io.github.chessoverlay.overlay.OverlayService
import io.github.chessoverlay.ui.PreviewBoardView
import io.github.chessoverlay.ui.Rows
import io.github.chessoverlay.vision.ModelStore
import io.github.chessoverlay.vision.Tuner
import kotlin.concurrent.thread

/**
 * The app's one screen, in four parts: the overlay (what it needs, the start
 * button, the engine), the last position the overlay saw with the engine's
 * arrows, the settings, and the tools. It names things and leaves out what
 * they do: that is in the Help dialog (the question mark).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var registry: EngineRegistry
    private lateinit var models: ModelStore
    private lateinit var status: TextView
    private lateinit var start: MaterialButton
    private lateinit var startHint: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var preview: PreviewBoardView
    private var engines: List<EngineInfo> = emptyList()
    private var downloading = false
    private val main = Handler(Looper.getMainLooper())

    /** Follows the overlay while the screen shows: the last position changes as the game goes on. */
    private val live = object : Runnable {
        override fun run() {
            showPosition()
            main.postDelayed(this, LIVE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(filesDir)
        prefs = Prefs(this)
        registry = EngineRegistry(this)
        models = ModelStore(this)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        findViewById<View>(R.id.help).setOnClickListener { help() }
        progress = findViewById(R.id.modelProgress)
        start = findViewById(R.id.start)
        startHint = findViewById(R.id.startHint)
        start.setOnClickListener { if (OverlayService.running) stopOverlay() else initialize() }
        findViewById<View>(R.id.engineChange).setOnClickListener { pickEngine() }
        findViewById<View>(R.id.engineOptions).setOnClickListener {
            startActivity(Intent(this, EngineOptionsActivity::class.java).putExtra("engine", prefs.engineId))
        }
        findViewById<View>(R.id.engineAdd).setOnClickListener { addEngineFile() }
        preview = findViewById(R.id.preview)
        buildPositionActions()
        buildSettings()
        buildTools()
        findViewById<TextView>(R.id.version).text = "ChessOverlay " +
            (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" })
        // The overlay runs as a foreground service and needs its notification;
        // Android 13 asks for that permission separately.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        loadEngines()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        main.post(live)
    }

    override fun onPause() {
        main.removeCallbacks(live)
        super.onPause()
    }

    // ---- what changes ------------------------------------------------------

    /** The needs, the start button and the engine, as they are now. */
    private fun refresh() {
        val checks = Rows(findViewById(R.id.checks))
        checks.clear()
        checks.statusChips(listOf(
            Rows.Status(if (downloading) "Models…" else "Models", models.isBundled || models.ready, busy = downloading) {
                if (!models.ready) downloadModels() else toast("The recognition models are here")
            },
            Rows.Status("Overlay", Settings.canDrawOverlays(this)) { askOverlayPermission() },
            Rows.Status("Engine", engines.isNotEmpty()) { addEngineFile() },
        ))
        val running = OverlayService.running
        val way = if (prefs.recognizerTuned) " · reads with ${prefs.recognizerMode ?: "cpu:2"}" else ""
        status.text = (if (running) "Running" else "Stopped") + way
        start.text = if (running) "Stop overlay" else "Start overlay"
        start.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        start.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.bad else R.color.accent))
        startHint.text = if (running) "Open the app with the board." else "Android asks to allow screen capture each time."
        val e = engines.firstOrNull { it.id == prefs.engineId } ?: engines.firstOrNull()
        // "(built in)" says the line under it.
        findViewById<TextView>(R.id.engineName).text = e?.title?.replace(" (built in)", "") ?: "No engine"
        findViewById<TextView>(R.id.engineKind).text = when (e?.kind) {
            EngineInfo.Kind.BUNDLED -> "Built in"
            EngineInfo.Kind.OPEN_EXCHANGE -> "From an engine app"
            EngineInfo.Kind.IMPORTED -> "Added from a file"
            null -> "Tap + to add an engine file"
        }
        showPosition()
    }

    // ---- the last position -------------------------------------------------

    /** What can be done with the last position and with the kept ones. */
    private fun buildPositionActions() {
        val actions = Rows(findViewById(R.id.positionActions))
        actions.buttons(listOf("Copy FEN", "Copy line")) { i ->
            val p = OverlayService.lastPosition ?: return@buttons
            if (i == 0) copy("ChessOverlay position", p.fen) else copy("ChessOverlay line", p.text())
        }
        actions.buttons(listOf("Analyse online", "Keep")) { i ->
            val p = OverlayService.lastPosition ?: return@buttons
            if (i == 0) analyseOnTheWeb(p.fen) else { KeptPositions.add(this, p); toast("Kept"); showPosition() }
        }
    }

    /** The position the overlay saw last (its board with the engine's arrows, its FEN and line), and the kept ones. */
    private fun showPosition() {
        val p = OverlayService.lastPosition
        val board = p?.let { try { Board.fromPlacement(it.fen.split(' ')[0]) } catch (_: Exception) { null } }
        val has = p != null && board != null
        findViewById<View>(R.id.positionActions).visibility = if (has) View.VISIBLE else View.GONE
        val fen = findViewById<TextView>(R.id.positionFen)
        fen.visibility = if (has) View.VISIBLE else View.GONE
        val title = findViewById<TextView>(R.id.positionTitle)
        val detail = findViewById<TextView>(R.id.positionDetail)
        if (p != null && board != null) {
            title.text = p.side + if (p.expected) " (expected)" else ""
            detail.text = listOfNotNull(p.moveNumber?.let { "move $it" }, p.score?.let { "eval $it" },
                if (p.depth > 0) "depth ${p.depth}" else null).joinToString(" · ") + (if (p.line.isNotEmpty()) "\n${p.line}" else "")
            fen.text = p.fen
            preview.shown = PreviewBoardView.Shown(board, p.whiteToMove, p.pv.takeIf { it.isNotEmpty() }?.let { PvLine(1, p.depth,
                p.whiteCp?.let { c -> if (p.whiteToMove) c else -c }, p.whiteMate?.let { m -> if (p.whiteToMove) m else -m }, it) })
        } else {
            title.text = "No position yet"
            detail.text = "The overlay's last position appears here."
            preview.shown = null
        }
        // The positions kept: one row each, and what to do with all of them.
        val kept = KeptPositions.all(this)
        val list = findViewById<android.widget.LinearLayout>(R.id.kept)
        list.removeAllViews()
        list.visibility = if (kept.isEmpty()) View.GONE else View.VISIBLE
        if (kept.isEmpty()) return
        val rows = Rows(list)
        rows.subhead("Kept (${kept.size})")
        for (k in kept) rows.action(R.drawable.ic_grid, k.note, k.fen) { askAboutKept(k) }
        rows.buttons(listOf("Share all", "Clear")) { i ->
            if (i == 0) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, KeptPositions.text(kept))
            }, "Share the kept positions"))
            else MaterialAlertDialogBuilder(this)
                .setMessage("Remove the ${kept.size} kept position${if (kept.size == 1) "" else "s"}?")
                .setNegativeButton("Keep them", null)
                .setPositiveButton("Remove") { _, _ -> KeptPositions.clear(this); showPosition() }
                .show()
        }
    }

    private fun askAboutKept(k: KeptPositions.Kept) {
        MaterialAlertDialogBuilder(this)
            .setTitle(k.note)
            .setItems(arrayOf("Copy the FEN", "Analyse online (lichess.org)", "Remove")) { _, which ->
                when (which) {
                    0 -> copy("ChessOverlay position", k.fen)
                    1 -> analyseOnTheWeb(k.fen)
                    else -> { KeptPositions.remove(this, k.fen); showPosition() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied")
    }

    /** lichess.org's analysis of a position: its address is the FEN with spaces as underscores. */
    private fun analyseOnTheWeb(fen: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("https://lichess.org/analysis/" + fen.replace(' ', '_'))), "Analyse the position"))
        } catch (e: Exception) {
            DebugLog.e("Positions", "no app to open an address", e)
            toast("No browser found")
        }
    }

    // ---- settings ----------------------------------------------------------

    /** Every setting, named only (what they do is in the Help dialog); what each changes is seen in the board above at once. */
    private fun buildSettings() {
        val r = Rows(findViewById(R.id.settings))
        val update = { updatePreview() }
        r.subhead("Analysis")
        r.chips("Lines", null, (1..5).map { "$it" }, prefs.lines - 1) { prefs.lines = it + 1 }
        val times = listOf(1000, 2000, 3000, 5000, 10000, 0)
        r.chips("Time per position", null, times.map { if (it == 0) "∞" else "${it / 1000} s" },
            times.indexOf(prefs.thinkMs).coerceAtLeast(0)) { prefs.thinkMs = times[it] }
        r.switch("Start in auto mode", null, prefs.autoStart) { prefs.autoStart = it }
        val confs = listOf(0.3f, 0.5f, 0.7f, 0.9f)
        r.chips("Recognition certainty", null, confs.map { "${(it * 100).toInt()}%" },
            confs.indexOf(prefs.minConfidence).coerceAtLeast(1)) { prefs.minConfidence = confs[it] }
        r.subhead("Board and card")
        r.chips("Arrows", null, (0..5).map { "$it" }, prefs.arrows) { prefs.arrows = it; update() }
        r.chips("Next moves of the best line", null, (0..6).map { "$it" }, prefs.pathPlies.coerceAtMost(6)) { prefs.pathPlies = it; update() }
        r.switch("Scores on the arrows", null, prefs.arrowLabels) { prefs.arrowLabels = it; update() }
        r.switch("Grade the last move", null, prefs.annotate) { prefs.annotate = it; update() }
        r.switch("Evaluation bar", null, prefs.evalBar) { prefs.evalBar = it }
        r.switch("Engine lines", null, prefs.linesPanel) { prefs.linesPanel = it }
        r.action(R.drawable.ic_restore, "Put the card back next to the board", null) {
            prefs.cardX = -1; prefs.cardY = -1
            toast("The card will follow the board again")
        }
        updatePreview()
    }

    private fun updatePreview() {
        preview.style = BoardStyle(arrows = prefs.arrows, pathPlies = prefs.pathPlies, arrowLabels = prefs.arrowLabels)
        preview.grade = prefs.annotate
    }

    // ---- tools and help ----------------------------------------------------

    private fun buildTools() {
        val r = Rows(findViewById(R.id.tools))
        r.action(R.drawable.ic_engine, "Test the engine", null) { testEngine() }
        r.action(R.drawable.ic_grid, "Test recognition", null) { testRecognition() }
        r.action(R.drawable.ic_copy, "Copy the debug log", null) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ChessOverlay log", DebugLog.dump()))
            toast("Debug log copied")
        }
        r.action(R.drawable.ic_share, "Share the last misread screen", null) { shareMisread() }
        r.action(R.drawable.ic_delete, "Clear the log", null) {
            DebugLog.clear()
            toast("Log cleared")
        }
    }

    /** What is on the screen and what it does, for anyone who wants to know. */
    private fun help() {
        MaterialAlertDialogBuilder(this)
            .setTitle("ChessOverlay")
            .setMessage(listOf(
                "START",
                "Start overlay, allow the screen capture, then open the app with the board: the board is read, " +
                    "and the engine's moves are drawn over it. Android asks to allow the capture each time.",
                "The chips above the button are what the overlay needs: the recognition models, permission to draw " +
                    "over other apps, and an engine. An amber one is tapped to set it right. Next to the engine, the arrows " +
                    "change it, the sliders open its options, and + adds an engine file.",
                "THE FLOATING CARD",
                "▶ read the screen now · A auto mode (follows the game) · ⇅ side to move (worked out, White, Black) · " +
                    "◐ show or hide the arrows · ✕ close. Drag it by any part; tap its lines to fold them; " +
                    "keep a finger on it to copy the position (FEN).",
                "ON THE BOARD",
                "The best move is the bold blue arrow, the other lines thinner ones; the best line's next moves are " +
                    "numbered per side in the corner of their squares; the last move is graded (★ best, ✓ good, ?! inaccuracy, " +
                    "? mistake, ?? blunder). A dimmed arrow is what the engine expected of the position, from its line for the one before.",
                "SETTINGS",
                "Lines: how many moves are searched and listed. Time per position: ∞ keeps thinking until the position changes. " +
                    "Start in auto mode: follows the game from the start. Recognition certainty: boards read less surely are not analysed. " +
                    "Arrows and next moves: what is drawn on the board; the board in this dialog's screen shows it as you change it.",
                "ENGINES",
                "Smotherfish is built in. Also found: engine apps that offer engines to chess GUIs (Open Exchange, as DroidFish uses). " +
                    "An engine file: any arm64 UCI engine (Stockfish, Komodo, lc0 with a Maia network…).",
                "POSITION",
                "The position the overlay saw last, with its FEN and the engine's line: copy either, analyse it online " +
                    "(lichess.org), or keep it; kept positions are shared as text.",
                "WHEN SOMETHING IS WRONG",
                "No lines: Test the engine. A read gone wrong: Copy the debug log, and share the last misread screen. " +
                    "Test recognition says how long a read takes on this phone and keeps the fastest way that reads right.",
                "FAIR PLAY",
                "Engine help in live games against people is cheating. Chess sites detect it and ban accounts. " +
                    "How you use this app is your responsibility.",
                "ABOUT",
                "ChessOverlay, copyright (C) 2026 Demonic1594. Free software under the GNU General Public License, " +
                    "version 3, with no warranty. Source: github.com/Demonic1594/ChessOverlay. " +
                    "Smotherfish by Demonic1594, based on Stockfish by the Stockfish developers (GPL-3.0). " +
                    "Board recognition: chessimg2pos by Mattia Dicio (MIT); ONNX Runtime by Microsoft (MIT).",
            ).joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun testEngine() {
        val e = engines.firstOrNull { it.id == prefs.engineId } ?: engines.firstOrNull()
        if (e == null) { toast("No engine found yet"); return }
        val busy = MaterialAlertDialogBuilder(this)
            .setTitle("Testing ${e.title}")
            .setMessage("Starting it and analysing for a few seconds…")
            .setCancelable(false)
            .show()
        thread(name = "engine-test") {
            val report = try {
                EngineCheck.run(e.file, registry.workDir, prefs.engineOptions(e.id))
            } catch (ex: Exception) {
                DebugLog.e("EngineCheck", "test failed", ex)
                EngineCheck.Report(false, "The test failed: ${ex.message}")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy.dismiss()
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (report.ok) "The engine works" else "The engine gave no lines")
                    .setMessage(report.text)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy the debug log") { _, _ ->
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("ChessOverlay log", DebugLog.dump()))
                        toast("Debug log copied")
                    }
                    .show()
            }
        }
    }

    private fun testRecognition() {
        val models = ModelStore(this)
        if (!models.ready) { toast("The recognition models are not here yet"); return }
        val busy = MaterialAlertDialogBuilder(this)
            .setTitle("Testing recognition")
            .setMessage("Reading a board, each way of running the models…")
            .setCancelable(false)
            .show()
        thread(name = "recognition-test") {
            val report = try {
                models.prepare()
                val result = Tuner.tune(models.board, models.locator, Tuner.sample(assets.open(Tuner.SAMPLE)), Tuner.candidates(all = true))
                prefs.recognizerMode = result.chosen.toString()
                prefs.recognizerTuned = true
                DebugLog.i("Tuner", "recognition tested:\n${result.report()}")
                result.report() + "\nThe overlay uses it from its next start."
            } catch (ex: Exception) {
                DebugLog.e("Tuner", "test failed", ex)
                "The test failed: ${ex.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy.dismiss()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Recognition")
                    .setMessage(report)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun loadEngines() {
        thread {
            val list = try { registry.all() } catch (_: Exception) { registry.bundled() + registry.imported() }
            runOnUiThread { engines = list; refresh() }
        }
    }

    private fun addEngineFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_IMPORT)
    }

    private fun pickEngine() {
        if (engines.isEmpty()) { toast("No engines found yet"); return }
        val current = engines.indexOfFirst { it.id == prefs.engineId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Engine")
            .setSingleChoiceItems(engines.map { it.title }.toTypedArray(), current) { d, i ->
                prefs.engineId = engines[i].id
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun downloadModels() {
        downloading = true
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        refresh()
        thread {
            try {
                var last = 0L
                models.download { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 200) {
                        last = now
                        runOnUiThread {
                            if (total > 0) { progress.isIndeterminate = false; progress.setProgressCompat((done * 100 / total).toInt(), true) }
                            status.text = "Downloading… ${done / 1_000_000} MB" + (if (total > 0) " of ${total / 1_000_000} MB" else "")
                        }
                    }
                }
                runOnUiThread { toast("Recognition models ready") }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed: ${e.message}") }
            } finally {
                runOnUiThread { downloading = false; progress.visibility = View.GONE; refresh() }
            }
        }
    }

    // ---- start and stop ----------------------------------------------------

    private fun initialize() {
        if (!models.ready) { toast("Download the recognition models first"); return }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow ChessOverlay to draw over other apps, then tap Start again")
            askOverlayPermission()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun stopOverlay() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        main.postDelayed({ refresh() }, 300)
    }

    private fun shareMisread() {
        val f = DebugCapture.file(this)
        if (!f.exists()) { toast("No misread screen saved yet"); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share the misread screen"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("Activity result API kept simple on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAPTURE -> if (resultCode == RESULT_OK && data != null) {
                OverlayService.start(this, resultCode, data)
                moveTaskToBack(true)
            }
            REQ_IMPORT -> if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "engine"
                thread {
                    val msg = try {
                        val e = registry.import(uri, name)
                        prefs.engineId = e.id
                        "Added ${e.title}"
                    } catch (ex: Exception) {
                        "Could not add the engine: ${ex.message}"
                    }
                    runOnUiThread { toast(msg); loadEngines() }
                }
            }
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_IMPORT = 2
        private const val REQ_NOTIFICATIONS = 3
        private const val LIVE_MS = 700L
    }
}
