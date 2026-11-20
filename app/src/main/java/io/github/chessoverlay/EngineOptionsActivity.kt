package io.github.chessoverlay

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.engine.UciEngine
import io.github.chessoverlay.engine.UciOption
import io.github.chessoverlay.ui.choice
import io.github.chessoverlay.ui.dp
import io.github.chessoverlay.ui.heading
import io.github.chessoverlay.ui.note
import io.github.chessoverlay.ui.row
import io.github.chessoverlay.ui.toggle
import kotlin.concurrent.thread

/**
 * Every UCI option the selected engine declares, editable. Values are saved
 * per engine and sent each time the overlay starts the engine. Nothing is
 * hidden: UCI_LimitStrength / UCI_Elo, Smotherfish's "Analysis Elo" and
 * Smother* options, lc0's WeightsFile (for Maia networks), and so on.
 */
class EngineOptionsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(24f))
        }
        setContentView(ScrollView(this).apply { addView(col) })
        val id = intent.getStringExtra("engine") ?: run { finish(); return }
        val status = note(col, "Starting the engine to read its options…")
        thread {
            val reg = EngineRegistry(this)
            val info = reg.all().firstOrNull { it.id == id }
            if (info == null) { runOnUiThread { status.text = "Engine not found" }; return@thread }
            try {
                val e = UciEngine(info.file, reg.workDir)
                e.start()
                val opts = e.options.toList()
                val name = e.name
                e.quit()
                runOnUiThread { build(id, name, opts, status) }
            } catch (ex: Exception) {
                runOnUiThread {
                    status.text = "Could not start ${info.title}: ${ex.message}\n\n" +
                        "Engine files must be Android (arm64) builds; desktop Linux builds do not run on a phone."
                }
            }
        }
    }

    private fun build(id: String, engineName: String, opts: List<UciOption>, status: TextView) {
        status.text = "Changes apply the next time you tap Initialize. The app sets MultiPV and " +
            "UCI_AnalyseMode itself."
        heading(col, engineName)
        val saved = prefs.engineOptions(id)
        for (o in opts) {
            if (o.name.equals("MultiPV", true) || o.name.equals("UCI_AnalyseMode", true)) continue
            val current = saved[o.name] ?: o.default
            fun save(v: String) = prefs.setEngineOption(id, o.name, if (v == o.default) null else v)
            when (o.type) {
                "check" -> row(col, o.name, toggle(current.equals("true", true)) { save(it.toString()) })
                "combo" -> row(col, o.name, choice(o.vars, o.vars.indexOf(current).coerceAtLeast(0)) { save(o.vars[it]) })
                "spin", "string" -> {
                    val edit = EditText(this).apply {
                        setText(current)
                        if (o.type == "spin") inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        minEms = if (o.type == "spin") 5 else 9
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                val v = s.toString()
                                if (o.type == "spin") {
                                    val n = v.toIntOrNull() ?: return
                                    if ((o.min != null && n < o.min) || (o.max != null && n > o.max)) return
                                }
                                save(v)
                            }
                        })
                    }
                    val label = if (o.type == "spin" && o.min != null && o.max != null) "${o.name} (${o.min}–${o.max})" else o.name
                    row(col, label, edit)
                }
                // Buttons (Clear Hash etc.) act on a running engine; nothing to save.
                else -> {}
            }
        }
        col.addView(Button(this).apply {
            text = "Reset to the engine's defaults"
            setOnClickListener {
                for (o in opts) prefs.setEngineOption(id, o.name, null)
                recreate()
            }
        })
    }
}
