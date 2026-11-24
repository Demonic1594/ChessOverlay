package io.github.chessoverlay

import android.content.Context
import android.content.SharedPreferences
import io.github.chessoverlay.overlay.Analyst
import org.json.JSONArray
import org.json.JSONObject

/**
 * Positions kept from the overlay (the app's Positions card): in the app's
 * settings as JSON, to copy again or to share as text. At most [MAX]: a
 * position to come back to, not a database.
 */
object KeptPositions {
    const val MAX = 100

    class Kept(val fen: String, val note: String, val time: Long)

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Newest first. */
    fun all(ctx: Context): List<Kept> {
        val text = prefs(ctx).getString("kept_positions", null) ?: return emptyList()
        val array = try { JSONArray(text) } catch (_: Exception) { return emptyList() }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Kept(o.getString("fen"), o.optString("note"), o.optLong("time"))
        }
    }

    fun add(ctx: Context, position: Analyst.Position) {
        val array = JSONArray()
        array.put(JSONObject().put("fen", position.fen).put("note", position.summary()).put("time", System.currentTimeMillis()))
        for (k in all(ctx).take(MAX - 1)) array.put(JSONObject().put("fen", k.fen).put("note", k.note).put("time", k.time))
        prefs(ctx).edit().putString("kept_positions", array.toString()).apply()
    }

    fun remove(ctx: Context, fen: String) {
        val array = JSONArray()
        for (k in all(ctx)) if (k.fen != fen) array.put(JSONObject().put("fen", k.fen).put("note", k.note).put("time", k.time))
        prefs(ctx).edit().putString("kept_positions", array.toString()).apply()
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("kept_positions").apply()

    /** All of them for a message: one position per paragraph. */
    fun text(all: List<Kept>): String = all.joinToString("\n\n") { "${it.note}\n${it.fen}" }
}
