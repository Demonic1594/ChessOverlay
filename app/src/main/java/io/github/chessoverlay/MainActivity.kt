package io.github.chessoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.chessoverlay.engine.EngineInfo
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.overlay.OverlayService
import io.github.chessoverlay.ui.choice
import io.github.chessoverlay.ui.dp
import io.github.chessoverlay.ui.heading
import io.github.chessoverlay.ui.note
import io.github.chessoverlay.ui.row
import io.github.chessoverlay.ui.toggle
import io.github.chessoverlay.vision.ModelStore
import kotlin.concurrent.thread

/** Settings and the Initialize button. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var registry: EngineRegistry
    private lateinit var models: ModelStore
    private lateinit var modelStatus: TextView
    private lateinit var modelButton: Button
    private lateinit var engineSpinner: Spinner
    private var engines: List<EngineInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(filesDir)
        prefs = Prefs(this)
        registry = EngineRegistry(this)
        models = ModelStore(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(24f))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply { text = "ChessOverlay"; textSize = 26f })
        note(col, "Reads a chess board from your screen, in any app, and shows engine arrows, an evaluation bar and lines over it.")
        note(col, "Fair play: using engine help in live games against people is cheating. Chess sites detect it " +
            "and ban accounts. How you use this app, and what follows from it, is your responsibility.")
            .setPadding(0, dp(8f), 0, 0)

        heading(col, "Recognition models")
        modelStatus = note(col, "")
        modelButton = Button(this).apply { setOnClickListener { downloadModels() } }
        col.addView(modelButton)
        updateModels()

        heading(col, "Engine")
        engineSpinner = Spinner(this)
        col.addView(engineSpinner)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Options"
            setOnClickListener {
                engines.getOrNull(engineSpinner.selectedItemPosition)?.let {
                    startActivity(Intent(this@MainActivity, EngineOptionsActivity::class.java).putExtra("engine", it.id))
                }
            }
        })
        buttons.addView(Button(this).apply {
            text = "Add engine file"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                }, REQ_IMPORT)
            }
        })
        buttons.addView(Button(this).apply { text = "Refresh"; setOnClickListener { loadEngines() } })
        col.addView(buttons)
        note(col, "Built in: Smotherfish. Also found: engine apps that offer engines to chess GUIs " +
            "(Open Exchange, as DroidFish uses), and any UCI engine binary for arm64 you add " +
            "(Stockfish, Komodo, lc0 with a Maia network set in its options, …).")
        loadEngines()

        heading(col, "Analysis")
        val lineChoices = (1..5).toList()
        row(col, "Engine lines", choice(lineChoices.map { "$it" }, prefs.lines - 1) { prefs.lines = it + 1 })
        val times = listOf(1000, 2000, 3000, 5000, 10000, 0)
        row(col, "Time per position", choice(times.map { if (it == 0) "Until it changes" else "${it / 1000} s" },
            times.indexOf(prefs.thinkMs).coerceAtLeast(0)) { prefs.thinkMs = times[it] })
        row(col, "Start in auto mode", toggle(prefs.autoStart) { prefs.autoStart = it })
        note(col, "Auto mode follows the screen: when a move is made it reads the board again, works out " +
            "the move played and the side to move, and analyses until the next change.")
        val confs = listOf(0.3f, 0.5f, 0.7f, 0.9f)
        row(col, "Minimum recognition certainty", choice(confs.map { "${(it * 100).toInt()}%" },
            confs.indexOf(prefs.minConfidence).coerceAtLeast(0)) { prefs.minConfidence = confs[it] })

        heading(col, "On the board")
        row(col, "Arrows (best moves)", choice((0..5).map { "$it" }, prefs.arrows) { prefs.arrows = it })
        row(col, "Next moves of the best line", choice((0..10).map { "$it" }, prefs.pathPlies) { prefs.pathPlies = it })
        row(col, "Scores on the arrows", toggle(prefs.arrowLabels) { prefs.arrowLabels = it })
        row(col, "Grade the last move (auto mode)", toggle(prefs.annotate) { prefs.annotate = it })
        note(col, "The best move is the bold blue arrow; other lines are thinner, in their own colours. " +
            "The best line's next moves are slim white or black arrows, numbered per side in the corner " +
            "of the square they go to.")

        heading(col, "Floating card")
        row(col, "Evaluation bar", toggle(prefs.evalBar) { prefs.evalBar = it })
        row(col, "Engine lines", toggle(prefs.linesPanel) { prefs.linesPanel = it })
        col.addView(Button(this).apply {
            text = "Put the card back next to the board"
            setOnClickListener {
                prefs.cardX = -1; prefs.cardY = -1
                Toast.makeText(this@MainActivity, "Done (takes effect when the overlay starts)", Toast.LENGTH_SHORT).show()
            }
        })

        col.addView(Button(this).apply {
            text = "Initialize"
            textSize = 18f
            setOnClickListener { initialize() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64f)).apply { topMargin = dp(24f) })
        note(col, "The card: ▶ analyse now · A auto mode · ⇅ side to move (auto / W / B) · " +
            "◐ show or hide the arrows · ✕ close. Drag it by any part; tap the lines to fold them.")
            .apply { gravity = Gravity.CENTER }

        heading(col, "Debug")
        note(col, "If something goes wrong, copy the log and send it: it records engine crashes " +
            "(with the position), recognition results and errors.")
        val dbg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dbg.addView(Button(this).apply {
            text = "Copy debug log"
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ChessOverlay log", DebugLog.dump()))
                Toast.makeText(this@MainActivity, "Debug log copied", Toast.LENGTH_SHORT).show()
            }
        })
        dbg.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener { DebugLog.clear(); Toast.makeText(this@MainActivity, "Cleared", Toast.LENGTH_SHORT).show() }
        })
        col.addView(dbg)
    }

    override fun onResume() {
        super.onResume()
        updateModels()
    }

    private fun loadEngines() {
        thread {
            val list = try { registry.all() } catch (_: Exception) { registry.bundled() + registry.imported() }
            runOnUiThread {
                engines = list
                engineSpinner.adapter = android.widget.ArrayAdapter(this,
                    android.R.layout.simple_spinner_dropdown_item, list.map { it.title })
                engineSpinner.setSelection(list.indexOfFirst { it.id == prefs.engineId }.coerceAtLeast(0))
                engineSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                        list.getOrNull(pos)?.let { prefs.engineId = it.id }
                    }
                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            }
        }
    }

    private fun updateModels() {
        if (models.isBundled) {
            modelStatus.text = "Built into this app (chessimg2pos v2, MIT licence; board model in int8)."
            modelButton.visibility = android.view.View.GONE
        } else if (models.ready) {
            modelStatus.text = "Ready."
            modelButton.text = "Download again"
        } else {
            modelStatus.text = "Not downloaded yet. One download of about 170 MB (chessimg2pos, MIT licence)."
            modelButton.text = "Download"
        }
    }

    private fun downloadModels() {
        modelButton.isEnabled = false
        thread {
            try {
                var last = 0L
                models.download { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 300) {
                        last = now
                        val mb = done / 1_000_000
                        val t = if (total > 0) " of ${total / 1_000_000} MB" else " MB"
                        runOnUiThread { modelStatus.text = "Downloading… $mb$t" }
                    }
                }
                runOnUiThread { updateModels() }
            } catch (e: Exception) {
                runOnUiThread { modelStatus.text = "Download failed: ${e.message}" }
            } finally {
                runOnUiThread { modelButton.isEnabled = true }
            }
        }
    }

    private fun initialize() {
        if (!models.ready) {
            Toast.makeText(this, "Download the recognition models first", Toast.LENGTH_LONG).show(); return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow ChessOverlay to draw over other apps, then tap Initialize again",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

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
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); loadEngines() }
                }
            }
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_IMPORT = 2
    }
}
