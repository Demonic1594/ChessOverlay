package io.github.chessoverlay

import android.content.Context
import android.content.SharedPreferences

/** App settings and per-engine UCI option values. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val engines: SharedPreferences = ctx.getSharedPreferences("engine_options", Context.MODE_PRIVATE)

    var engineId: String
        get() = p.getString("engine", "bundled:libsmotherfish.so")!!
        set(v) = p.edit().putString("engine", v).apply()

    /** Number of engine lines (MultiPV), 1-5. */
    var lines: Int
        get() = p.getInt("lines", 3)
        set(v) = p.edit().putInt("lines", v.coerceIn(1, 5)).apply()

    /** Arrows drawn: the first move of the best N lines. */
    var arrows: Int
        get() = p.getInt("arrows", 2)
        set(v) = p.edit().putInt("arrows", v.coerceIn(0, 5)).apply()

    /** Moves of the best line drawn as a numbered path after the first (0 = off). */
    var pathPlies: Int
        get() = p.getInt("path_plies", 2)
        set(v) = p.edit().putInt("path_plies", v.coerceIn(0, 10)).apply()

    /** Analysis time per position in ms; 0 = until the position changes. */
    var thinkMs: Int
        get() = p.getInt("think_ms", 0)
        set(v) = p.edit().putInt("think_ms", v.coerceAtLeast(0)).apply()

    var evalBar: Boolean
        get() = p.getBoolean("eval_bar", true)
        set(v) = p.edit().putBoolean("eval_bar", v).apply()

    var linesPanel: Boolean
        get() = p.getBoolean("lines_panel", true)
        set(v) = p.edit().putBoolean("lines_panel", v).apply()

    var arrowLabels: Boolean
        get() = p.getBoolean("arrow_labels", false)
        set(v) = p.edit().putBoolean("arrow_labels", v).apply()

    /** Mark the last move played (best/good/inaccuracy/mistake/blunder) in auto mode. */
    var annotate: Boolean
        get() = p.getBoolean("annotate", true)
        set(v) = p.edit().putBoolean("annotate", v).apply()

    /** Start in auto mode when the overlay opens. */
    var autoStart: Boolean
        get() = p.getBoolean("auto_start", true)
        set(v) = p.edit().putBoolean("auto_start", v).apply()

    /** Where the user put the floating card (screen pixels); y < 0 = not moved, it follows the board. */
    var cardX: Int
        get() = p.getInt("card_x", -1)
        set(v) = p.edit().putInt("card_x", v).apply()

    var cardY: Int
        get() = p.getInt("card_y", -1)
        set(v) = p.edit().putInt("card_y", v).apply()

    /** The card shows the evaluation row only. */
    var cardCollapsed: Boolean
        get() = p.getBoolean("card_collapsed", false)
        set(v) = p.edit().putBoolean("card_collapsed", v).apply()

    /** Boards whose least certain square is below this are not analysed. */
    var minConfidence: Float
        get() = p.getFloat("min_conf", 0.5f)
        set(v) = p.edit().putFloat("min_conf", v.coerceIn(0f, 1f)).apply()

    /** How the overlay runs the recognition models (see RecognizerMode.toString), as the phone was measured. */
    var recognizerMode: String?
        get() = p.getString("recognizer_mode", null)
        set(v) = p.edit().putString("recognizer_mode", v).apply()

    /** The way of running the models the overlay is using, when it is not the default; cleared when the overlay stops.
     *  If it is still set when the overlay next starts, the app died while using it: it is not used again. */
    var recognizerInUse: String?
        get() = p.getString("recognizer_in_use", null)
        set(v) = p.edit().putString("recognizer_in_use", v).apply()

    /** Whether the models were measured on this phone (once; "Test recognition" measures again). */
    var recognizerTuned: Boolean
        get() = p.getBoolean("recognizer_tuned", false)
        set(v) = p.edit().putBoolean("recognizer_tuned", v).apply()

    /** Look for new versions on GitHub Releases (at most once a day). */
    var updateCheck: Boolean
        get() = p.getBoolean("update_check", true)
        set(v) = p.edit().putBoolean("update_check", v).apply()

    /** When the latest release was last looked up. */
    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0L)
        set(v) = p.edit().putLong("last_update_check", v).apply()

    fun engineOptions(engineId: String): Map<String, String> {
        val prefix = "$engineId\u0000"
        return engines.all.filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .mapValues { it.value.toString() }
    }

    /** Forgets every option saved for an engine (it was removed). */
    fun clearEngineOptions(engineId: String) {
        val prefix = "$engineId\u0000"
        engines.edit().apply { engines.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }.apply()
    }

    fun setEngineOption(engineId: String, name: String, value: String?) {
        val key = "$engineId\u0000$name"
        engines.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
