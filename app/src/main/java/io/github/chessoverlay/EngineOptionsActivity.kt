package io.github.chessoverlay

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.engine.UciEngine
import io.github.chessoverlay.engine.UciOption
import io.github.chessoverlay.ui.Rows
import io.github.chessoverlay.ui.dp
import kotlin.concurrent.thread

/**
 * Every UCI option the selected engine declares, editable, grouped by what
 * it is for. Values are saved per engine and sent each time the overlay
 * starts the engine. Nothing is hidden: UCI_LimitStrength / UCI_Elo,
 * Smotherfish's "Analysis Elo" and Smother* options, lc0's WeightsFile (for
 * Maia networks), and so on.
 */
class EngineOptionsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_engine_options)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val id = intent.getStringExtra("engine") ?: run { finish(); return }
        val status = findViewById<TextView>(R.id.status)
        thread {
            val reg = EngineRegistry(this)
            val info = reg.all().firstOrNull { it.id == id } ?: reg.all().firstOrNull()
            if (info == null) { runOnUiThread { done("No engine found") }; return@thread }
            runOnUiThread { findViewById<TextView>(R.id.engineName).text = info.title }
            try {
                val e = UciEngine(info.file, reg.workDir)
                e.start()
                val opts = e.options.toList()
                val name = e.name
                e.quit()
                runOnUiThread { build(info.id, name, opts) }
            } catch (ex: Exception) {
                runOnUiThread {
                    done("Could not start ${info.title}: ${ex.message}\n\n" +
                        "Engine files must be Android (arm64) builds; desktop Linux builds do not run on a phone.")
                }
            }
        }
        status.text = "Starting the engine to read its options…"
    }

    private fun done(message: String) {
        findViewById<TextView>(R.id.status).text = message
        findViewById<LinearProgressIndicator>(R.id.progress).visibility = View.GONE
    }

    private fun build(id: String, engineName: String, opts: List<UciOption>) {
        findViewById<TextView>(R.id.engineName).text = engineName
        done("Changes apply the next time you start the overlay. The app sets MultiPV and UCI_AnalyseMode itself.")
        val filter = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.filter)
        filter.visibility = View.VISIBLE
        filter.editText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = showOptions(id, opts, s.toString().trim())
        })
        showOptions(id, opts, "")
        findViewById<View>(R.id.reset).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                for (o in opts) prefs.setEngineOption(id, o.name, null)
                recreate()
            }
        }
    }

    /** The options whose name contains `query`, in their groups. */
    private fun showOptions(id: String, opts: List<UciOption>, query: String) {
        val saved = prefs.engineOptions(id)
        val groups = findViewById<LinearLayout>(R.id.groups)
        groups.removeAllViews()
        val shown = opts.filter {
            it.type != "button" && !it.name.equals("MultiPV", true) && !it.name.equals("UCI_AnalyseMode", true) &&
                (query.isEmpty() || it.name.contains(query, true) || groupOf(it.name).contains(query, true))
        }
        if (shown.isEmpty()) {
            groups.addView(TextView(this, null, 0, R.style.Caption).apply {
                text = "No option matches “$query”."
                setPadding(dp(4f), dp(20f), dp(4f), 0)
            })
        }
        for ((group, members) in shown.groupBy { groupOf(it.name) }.toSortedMap(compareBy { GROUPS.indexOf(it) })) {
            groups.addView(TextView(this, null, 0, R.style.Section).apply { text = group },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(24f); bottomMargin = dp(8f); marginStart = dp(4f)
                })
            val card = MaterialCardView(this)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(col)
            groups.addView(card)
            val rows = Rows(col)
            for (o in members) optionRow(rows, id, o, saved[o.name])
        }
    }

    private fun optionRow(rows: Rows, id: String, o: UciOption, saved: String?) {
        val current = saved ?: o.default
        fun save(v: String) = prefs.setEngineOption(id, o.name, if (v == o.default) null else v)
        fun note(extra: String) = (if (saved != null && saved != o.default) "Changed · " else "") + extra
        val name = io.github.chessoverlay.ui.breakable(o.name)
        when (o.type) {
            "check" -> rows.switch(name, note("Default: ${if (o.default.equals("true", true)) "on" else "off"}"),
                current.equals("true", true)) { save(it.toString()) }
            "combo" -> if (o.vars.size <= 6) {
                rows.chips(name, note("Default: ${o.default}"), o.vars, o.vars.indexOf(current).coerceAtLeast(0)) { save(o.vars[it]) }
            } else {
                rows.input(name, note("One of: ${o.vars.joinToString(", ")}"), current, numeric = false, wide = true) { v ->
                    if (v in o.vars) save(v)
                }
            }
            "spin" -> {
                val range = if (o.min != null && o.max != null) "${o.min} to ${o.max}" else ""
                val field = rows.input(name, note("Default ${o.default}" + if (range.isNotEmpty()) " · $range" else ""),
                    current, numeric = true, wide = false) { v -> }
                // Saved only when in range; out of range is shown, not saved.
                field.editText?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val n = s.toString().toIntOrNull()
                        val lo = o.min
                        val hi = o.max
                        val ok = n != null && (lo == null || n >= lo) && (hi == null || n <= hi)
                        field.error = if (ok || s.isNullOrEmpty()) null else range
                        if (ok) save(n.toString())
                    }
                })
            }
            else -> rows.input(name, note(if (o.default.isEmpty() || o.default == "<empty>") "Empty by default" else "Default: ${o.default}"),
                current, numeric = false, wide = true) { save(it) }
        }
    }

    companion object {
        private val GROUPS = listOf("Strength", "Smotherfish", "Performance", "Other")

        fun groupOf(name: String): String = when {
            name.startsWith("Smother", true) -> "Smotherfish"
            listOf("Elo", "Skill", "LimitStrength", "Analysis").any { name.contains(it, true) } -> "Strength"
            listOf("Thread", "Hash", "Ponder", "Overhead", "Numa", "SyzygyPath", "Syzygy").any { name.contains(it, true) } -> "Performance"
            else -> "Other"
        }
    }
}
