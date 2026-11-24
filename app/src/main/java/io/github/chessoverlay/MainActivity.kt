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
import io.github.chessoverlay.engine.EngineCheck
import io.github.chessoverlay.engine.EngineInfo
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.overlay.BoardStyle
import io.github.chessoverlay.overlay.OverlayService
import io.github.chessoverlay.ui.CheckRow
import io.github.chessoverlay.ui.PreviewBoardView
import io.github.chessoverlay.ui.Rows
import io.github.chessoverlay.vision.ModelStore
import kotlin.concurrent.thread

/**
 * The app's one screen: a setup checklist with the start button, the
 * engine, and the settings in groups (analysis, the drawing on the board
 * with a live preview, the floating card, help and debug).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var registry: EngineRegistry
    private lateinit var models: ModelStore
    private lateinit var modelsRow: CheckRow
    private lateinit var permissionRow: CheckRow
    private lateinit var engineRow: CheckRow
    private lateinit var start: MaterialButton
    private lateinit var startHint: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var preview: PreviewBoardView
    private var engines: List<EngineInfo> = emptyList()
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(filesDir)
        prefs = Prefs(this)
        registry = EngineRegistry(this)
        models = ModelStore(this)
        setContentView(R.layout.activity_main)

        val checks = Rows(findViewById(R.id.checks))
        modelsRow = checks.check(R.drawable.ic_grid, "Board recognition")
        permissionRow = checks.check(R.drawable.ic_layers, "Draw over other apps")
        engineRow = checks.check(R.drawable.ic_engine, "Engine")
        progress = findViewById(R.id.modelProgress)
        start = findViewById(R.id.start)
        startHint = findViewById(R.id.startHint)
        start.setOnClickListener { if (OverlayService.running) stopOverlay() else initialize() }

        findViewById<View>(R.id.engineChange).setOnClickListener { pickEngine() }
        findViewById<View>(R.id.engineOptions).setOnClickListener {
            startActivity(Intent(this, EngineOptionsActivity::class.java).putExtra("engine", prefs.engineId))
        }
        findViewById<View>(R.id.engineAdd).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        }

        buildAnalysis(Rows(findViewById(R.id.analysis)))
        preview = findViewById(R.id.preview)
        buildBoard(Rows(findViewById(R.id.boardSettings)))
        buildCard(Rows(findViewById(R.id.cardSettings)))
        buildDebug(Rows(findViewById(R.id.debug)))
        updatePreview()

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        findViewById<TextView>(R.id.version).text = "ChessOverlay $version"
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
    }

    // ---- settings ----------------------------------------------------------

    private fun buildAnalysis(r: Rows) {
        r.chips("Engine lines", "How many of the best moves are searched and listed.",
            (1..5).map { "$it" }, prefs.lines - 1) { prefs.lines = it + 1 }
        val times = listOf(1000, 2000, 3000, 5000, 10000, 0)
        r.chips("Time per position", "∞ = keeps thinking until the position changes.",
            times.map { if (it == 0) "∞" else "${it / 1000} s" }, times.indexOf(prefs.thinkMs).coerceAtLeast(0)) {
            prefs.thinkMs = times[it]
        }
        r.switch("Start in auto mode", "Follows the game: reads the board after every move and analyses until the next.",
            prefs.autoStart) { prefs.autoStart = it }
        val confs = listOf(0.3f, 0.5f, 0.7f, 0.9f)
        r.chips("Recognition certainty", "Boards read less surely than this are not analysed.",
            confs.map { "${(it * 100).toInt()}%" }, confs.indexOf(prefs.minConfidence).coerceAtLeast(1)) {
            prefs.minConfidence = confs[it]
        }
    }

    private fun buildBoard(r: Rows) {
        r.chips("Arrows", "Best moves drawn: the best in bold blue, others thinner.",
            (0..5).map { "$it" }, prefs.arrows) { prefs.arrows = it; updatePreview() }
        r.chips("Next moves of the best line", "Numbered per side in the corner of the square they go to.",
            (0..6).map { "$it" }, prefs.pathPlies.coerceAtMost(6)) { prefs.pathPlies = it; updatePreview() }
        r.switch("Scores on the arrows", null, prefs.arrowLabels) { prefs.arrowLabels = it; updatePreview() }
        r.switch("Grade the last move", "★ best · ✓ good · ?! inaccuracy · ? mistake · ?? blunder (auto mode).",
            prefs.annotate) { prefs.annotate = it; updatePreview() }
    }

    private fun buildCard(r: Rows) {
        r.switch("Evaluation bar", "Score and bar in the card.", prefs.evalBar) { prefs.evalBar = it }
        r.switch("Engine lines", "One row per line, colours matching the arrows. Tap the card's lines to fold them.",
            prefs.linesPanel) { prefs.linesPanel = it }
        r.action(R.drawable.ic_restore, "Put the card back next to the board",
            "After you dragged it somewhere. Applies when the overlay starts.") {
            prefs.cardX = -1; prefs.cardY = -1
            toast("The card will follow the board again")
        }
    }

    private fun buildDebug(r: Rows) {
        r.action(R.drawable.ic_play, "Test the engine", "Runs it on a position for a few seconds, with your options, and says what it did.") {
            testEngine()
        }
        r.action(R.drawable.ic_copy, "Copy the debug log", "What the overlay saw and did: reads, engine starts and errors.") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ChessOverlay log", DebugLog.dump()))
            toast("Debug log copied")
        }
        r.action(R.drawable.ic_share, "Share the last misread screen", "The screen a read last failed on, as the app saw it.") {
            shareMisread()
        }
        r.action(R.drawable.ic_delete, "Clear the log", null) {
            DebugLog.clear()
            toast("Log cleared")
        }
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

    private fun updatePreview() {
        preview.style = BoardStyle(arrows = prefs.arrows, pathPlies = prefs.pathPlies, arrowLabels = prefs.arrowLabels)
        preview.grade = prefs.annotate
    }

    // ---- setup checklist ---------------------------------------------------

    private fun refresh() {
        when {
            models.isBundled -> modelsRow.set(CheckRow.State.OK, "Built in")
            models.ready -> modelsRow.set(CheckRow.State.OK, "Downloaded")
            downloading -> modelsRow.set(CheckRow.State.BUSY, "Downloading…")
            else -> modelsRow.set(CheckRow.State.TODO, "One download, about 170 MB", "Download") { downloadModels() }
        }
        if (Settings.canDrawOverlays(this)) permissionRow.set(CheckRow.State.OK, "Allowed")
        else permissionRow.set(CheckRow.State.TODO, "Needed for arrows over other apps", "Allow") { askOverlayPermission() }
        val e = engines.firstOrNull { it.id == prefs.engineId } ?: engines.firstOrNull()
        if (e != null) engineRow.set(CheckRow.State.OK, e.title) else engineRow.set(CheckRow.State.BUSY, "Looking for engines…")
        findViewById<TextView>(R.id.engineName).text = e?.title ?: "…"
        findViewById<TextView>(R.id.engineKind).text = when (e?.kind) {
            EngineInfo.Kind.BUNDLED -> "Built in"
            EngineInfo.Kind.OPEN_EXCHANGE -> "From an engine app"
            EngineInfo.Kind.IMPORTED -> "Added from a file"
            null -> ""
        }

        val running = OverlayService.running
        start.text = if (running) "Stop overlay" else "Start overlay"
        start.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        start.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.bad else R.color.accent))
        startHint.text = if (running) "The overlay is running. Open the app with the board."
            else "Then open the app with the board. Android asks to allow screen capture each time."
    }

    private fun loadEngines() {
        thread {
            val list = try { registry.all() } catch (_: Exception) { registry.bundled() + registry.imported() }
            runOnUiThread { engines = list; refresh() }
        }
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
                            modelsRow.set(CheckRow.State.BUSY, "Downloading… ${done / 1_000_000} MB" +
                                if (total > 0) " of ${total / 1_000_000} MB" else "")
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
        start.postDelayed({ refresh() }, 300)
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
    }
}
