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
        get() = p.getInt("arrows", 3)
        set(v) = p.edit().putInt("arrows", v.coerceIn(0, 5)).apply()

    /** Moves of the best line drawn as a numbered path after the first (0 = off). */
    var pathPlies: Int
        get() = p.getInt("path_plies", 4)
        set(v) = p.edit().putInt("path_plies", v.coerceIn(0, 10)).apply()

    /** Analysis time per position in ms; 0 = until the position changes. */
    var thinkMs: Int
        get() = p.getInt("think_ms", 3000)
        set(v) = p.edit().putInt("think_ms", v.coerceAtLeast(0)).apply()

    var evalBar: Boolean
        get() = p.getBoolean("eval_bar", true)
        set(v) = p.edit().putBoolean("eval_bar", v).apply()

    var linesPanel: Boolean
        get() = p.getBoolean("lines_panel", true)
        set(v) = p.edit().putBoolean("lines_panel", v).apply()

    var arrowLabels: Boolean
        get() = p.getBoolean("arrow_labels", true)
        set(v) = p.edit().putBoolean("arrow_labels", v).apply()

    /** Mark the last move played (best/good/inaccuracy/mistake/blunder) in auto mode. */
    var annotate: Boolean
        get() = p.getBoolean("annotate", true)
        set(v) = p.edit().putBoolean("annotate", v).apply()

    /** Seconds between screen reads in auto mode. */
    var autoSeconds: Float
        get() = p.getFloat("auto_seconds", 1.5f)
        set(v) = p.edit().putFloat("auto_seconds", v.coerceIn(0.5f, 10f)).apply()

    /** Boards whose least certain square is below this are not analysed. */
    var minConfidence: Float
        get() = p.getFloat("min_conf", 0.5f)
        set(v) = p.edit().putFloat("min_conf", v.coerceIn(0f, 1f)).apply()

    fun engineOptions(engineId: String): Map<String, String> {
        val prefix = "$engineId\u0000"
        return engines.all.filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
            .mapValues { it.value.toString() }
    }

    fun setEngineOption(engineId: String, name: String, value: String?) {
        val key = "$engineId\u0000$name"
        engines.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
