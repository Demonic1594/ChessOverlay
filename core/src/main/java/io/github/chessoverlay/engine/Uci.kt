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

/**
 * Groups "info … pv" lines into whole iterations. An engine prints one line
 * per MultiPV rank at the end of each depth; passing each line on as it
 * comes mixes depths (rank 1 already at depth 21, ranks 2 and 3 still at
 * 20), so arrows swap and repeat for a moment. This releases a set only when
 * it is whole: all `expected` ranks are in, or the next iteration has begun
 * (a position with fewer legal moves than ranks), or the search ended.
 */
class PvCollector(private val expected: Int) {
    private val pending = sortedMapOf<Int, PvLine>()
    private var complete: List<PvLine> = emptyList()

    /** Adds a line; returns the new whole set when this line completes one. */
    fun add(pv: PvLine): List<PvLine>? {
        var out: List<PvLine>? = null
        if (pv.multipv == 1 && pending.isNotEmpty()) out = release()
        pending[pv.multipv] = pv
        if (pending.size >= expected) out = release()
        return out
    }

    /** The search ended: the last whole set (or the partial one, if none was whole). */
    fun finish(): List<PvLine> = if (complete.isNotEmpty()) complete else pending.values.toList()

    /**
     * No more lines came for a while: the set so far is the whole iteration.
     * An engine gives fewer lines than asked when the position has fewer
     * legal moves (two moves out of check, with three lines asked), and one
     * whose depth is capped (a strength limit) gives no next iteration that
     * would close this one. Null if nothing is waiting.
     */
    fun flush(): List<PvLine>? = if (pending.isEmpty()) null else release()

    /** Lines of an unfinished set are waiting. */
    val waiting: Boolean get() = pending.isNotEmpty()

    private fun release(): List<PvLine> {
        complete = pending.values.toList()
        pending.clear()
        return complete
    }
}

/**
 * Keeps arrows from jumping back and forth. At low depths an engine often
 * switches its best move for one iteration and then switches back; showing
 * each switch makes the arrows flicker. A change of the arrows' moves (the
 * first move of each line with an arrow, and the best line's next moves) is
 * shown once two iterations in a row agree on it, or at once from depth
 * DEEP (iterations are slow there, and a change means something). Scores
 * and depth move with the lines they belong to.
 */
class LineStabilizer(private val arrows: Int, private val pathPlies: Int) {
    private var shown: List<PvLine>? = null
    private var pendingKey: String? = null

    fun reset() { shown = null; pendingKey = null }

    /** The lines to show for this iteration (the last shown ones, while a change is unconfirmed). */
    fun offer(lines: List<PvLine>, finished: Boolean): List<PvLine> {
        val cur = shown
        val key = key(lines)
        val depth = lines.firstOrNull()?.depth ?: 0
        if (cur == null || key == key(cur) || finished || depth >= DEEP || key == pendingKey) {
            shown = lines
            pendingKey = null
            return lines
        }
        pendingKey = key
        return cur
    }

    private fun key(lines: List<PvLine>): String {
        val sb = StringBuilder()
        for ((i, l) in lines.take(maxOf(1, arrows)).withIndex()) {
            sb.append(l.pv.take(if (i == 0) pathPlies + 1 else 1).joinToString(" ")).append('|')
        }
        return sb.toString()
    }

    companion object {
        const val DEEP = 18
    }
}

/**
 * The first, shallow iterations of a search change their minds every few
 * milliseconds, so lines below `minDepth` that arrive within `waitMs` of the
 * start are held back. Held, never dropped: if nothing deeper has come by
 * [releaseAt], [release] gives the last held lines. An engine whose depth is
 * capped (a strength limit such as UCI_LimitStrength with a low Elo) may
 * never go deeper than depth 1.
 */
class FirstLinesGate<T>(private val minDepth: Int, private val waitMs: Long) {
    private var startedAt = 0L
    private var shown = false
    private var held: T? = null

    fun start(now: Long) { startedAt = now; shown = false; held = null }

    val releaseAt: Long get() = startedAt + waitMs

    /** True: show `item` now. False: it is held until [releaseAt]. */
    fun offer(item: T, depth: Int, finished: Boolean, now: Long): Boolean {
        if (shown || finished || depth >= minDepth || now - startedAt >= waitMs) {
            shown = true
            held = null
            return true
        }
        held = item
        return false
    }

    /** At [releaseAt]: the held lines, unless something was shown meanwhile. */
    fun release(): T? {
        if (shown) return null
        val h = held ?: return null
        held = null
        shown = true
        return h
    }
}
