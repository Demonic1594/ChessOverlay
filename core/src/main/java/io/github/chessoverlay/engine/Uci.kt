package io.github.chessoverlay.engine

/** One UCI option as the engine declares it ("option name ... type ..."). */
data class UciOption(
    val name: String,
    val type: String,          // check, spin, combo, string, button
    val default: String,
    val min: Int? = null,
    val max: Int? = null,
    val vars: List<String> = emptyList(),
) {
    companion object {
        private val KEYWORDS = setOf("name", "type", "default", "min", "max", "var")

        /** Parse an "option ..." line; null if it is not one. */
        fun parse(line: String): UciOption? {
            val t = line.trim().split(Regex("\\s+"))
            if (t.isEmpty() || t[0] != "option") return null
            // Values (names, defaults, combo entries) may contain spaces: a
            // value runs until the next keyword.
            val fields = mutableMapOf<String, String>()
            val vars = mutableListOf<String>()
            var key: String? = null
            val value = StringBuilder()
            fun flush() {
                val k = key ?: return
                val v = value.toString().trim()
                if (k == "var") vars.add(v) else fields[k] = v
                value.setLength(0)
            }
            for (tok in t.drop(1)) {
                // An option name may contain spaces and keyword-like words;
                // per the UCI spec only "type" ends it.
                val isKeyword = if (key == "name") tok == "type" else tok in KEYWORDS
                if (isKeyword) {
                    flush(); key = tok
                } else {
                    if (value.isNotEmpty()) value.append(' ')
                    value.append(tok)
                }
            }
            flush()
            val name = fields["name"] ?: return null
            val type = fields["type"] ?: return null
            val def = fields["default"].let { if (it == "<empty>") "" else it ?: "" }
            return UciOption(name, type, def, fields["min"]?.toIntOrNull(), fields["max"]?.toIntOrNull(), vars)
        }
    }
}

/** One analysis line from "info ... multipv N ... score ... pv ..." */
data class PvLine(
    val multipv: Int,
    val depth: Int,
    val scoreCp: Int?,       // side to move's view
    val mate: Int?,          // moves to mate, negative = getting mated
    val pv: List<String>,
    val nodes: Long = 0,
    val wdl: Triple<Int, Int, Int>? = null,
) {
    /** Score for White in pawns (mate as +-100), given who is to move. */
    fun whiteScore(whiteToMove: Boolean): Double {
        val s = when {
            mate != null -> if (mate > 0) 100.0 else -100.0
            scoreCp != null -> scoreCp / 100.0
            else -> 0.0
        }
        return if (whiteToMove) s else -s
    }

    /** "+1.25", "-0.40", "M5", "-M3" from White's view. */
    fun scoreText(whiteToMove: Boolean): String {
        if (mate != null) {
            val forWhite = if (whiteToMove) mate else -mate
            return if (forWhite > 0) "M$forWhite" else "-M${-forWhite}"
        }
        val v = whiteScore(whiteToMove)
        return (if (v >= 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", v)
    }

    companion object {
        /** Parse an "info" line carrying a pv; null otherwise (or for bound-only scores). */
        fun parse(line: String): PvLine? {
            if (!line.startsWith("info ") || !line.contains(" pv ")) return null
            val t = line.trim().split(Regex("\\s+"))
            var depth = 0; var multipv = 1; var cp: Int? = null; var mate: Int? = null
            var nodes = 0L; var bound = false; var wdl: Triple<Int, Int, Int>? = null
            var i = 1
            var pv: List<String> = emptyList()
            while (i < t.size) {
                when (t[i]) {
                    "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                    "multipv" -> { multipv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i += 2 }
                    "nodes" -> { nodes = t.getOrNull(i + 1)?.toLongOrNull() ?: 0; i += 2 }
                    "score" -> {
                        when (t.getOrNull(i + 1)) {
                            "cp" -> cp = t.getOrNull(i + 2)?.toIntOrNull()
                            "mate" -> mate = t.getOrNull(i + 2)?.toIntOrNull()
                        }
                        i += 3
                        while (i < t.size && (t[i] == "lowerbound" || t[i] == "upperbound")) { bound = true; i++ }
                    }
                    "wdl" -> {
                        val w = t.getOrNull(i + 1)?.toIntOrNull()
                        val d = t.getOrNull(i + 2)?.toIntOrNull()
                        val l = t.getOrNull(i + 3)?.toIntOrNull()
                        if (w != null && d != null && l != null) wdl = Triple(w, d, l)
                        i += 4
                    }
                    "pv" -> { pv = t.subList(i + 1, t.size); i = t.size }
                    else -> i++
                }
            }
            if (bound || pv.isEmpty() || (cp == null && mate == null)) return null
            return PvLine(multipv, depth, cp, mate, pv, nodes, wdl)
        }
    }
}
