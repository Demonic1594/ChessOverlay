package io.github.chessoverlay.overlay

import io.github.chessoverlay.chess.Board
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the board layer shows. `dp` = pixels per dp. */
data class BoardStyle(
    val arrows: Int = 2,
    val pathPlies: Int = 3,
    val arrowLabels: Boolean = false,
    val dp: Float = 1f,
)

/**
 * The drawing on the board itself, kept to what belongs on squares:
 *
 * - the best move as one bold arrow, the next best lines as thin ones
 *   (knight moves bend like an L, so they do not cross other pieces);
 * - the best line's continuation as slim arrows in the colour of the side
 *   that plays them, each numbered in a small disc in a corner of its
 *   destination square (counted per side, see [pathLabels]);
 * - the grade of the move just played, in the opposite corner.
 *
 * Scores, the evaluation and the lines live in the floating card.
 */
object BoardLayer {
    /** Line colours, best first (blue, orange, purple, teal, pink); the card's chips use the same. */
    val LINE_COLORS = intArrayOf(0xFF1F7AE6.toInt(), 0xFFF08A1C.toInt(), 0xFF9C4DCC.toInt(),
        0xFF13A397.toInt(), 0xFFE0498A.toInt())

    private val WHITE_FILL = 0xF5FAFAFA.toInt()
    private val WHITE_EDGE = 0xE6303030.toInt()
    private val BLACK_FILL = 0xF5262626.toInt()
    private val BLACK_EDGE = 0xE6F2F2F2.toInt()

    fun build(s: OverlayState, st: BoardStyle, m: TextMeasure): List<Shape> {
        val k = s.corners ?: return emptyList()
        if (s.board == null) return emptyList()
        val g = BoardGeom(k, s.blackAtBottom)
        val sq = g.sq
        val edge = max(1.2f * st.dp, sq * 0.014f)
        val slots = CornerSlots()
        val out = ArrayList<Shape>()
        val marks = ArrayList<Shape>()   // drawn last, over the arrows

        s.lastMove?.let { mark ->
            val c = g.centre(mark.square, slots.take(mark.square, Corner.TOP_RIGHT))
            marks += Shape.Circle(c.x, c.y, sq * 0.17f, mark.color, 0xFFFFFFFF.toInt(), edge)
            marks += centred(c, mark.label, sq * 0.21f, 0xFFFFFFFF.toInt(), m)
        }

        // The other lines thin and lighter, weakest first.
        val n = min(st.arrows, s.lines.size)
        for (i in n - 1 downTo 1) {
            val mv = Move.parse(s.lines[i].pv.firstOrNull() ?: continue) ?: continue
            val color = LINE_COLORS[i % LINE_COLORS.size]
            out += Shape.Poly(arrowOutline(route(g, mv), sq * 0.095f, sq * 0.3f, sq * 0.18f),
                color.withAlpha(0xB8), 0x80000000.toInt(), edge)
            if (st.arrowLabels) marks += pill(g.centre(mv.to, 0f, 0.34f), s.lines[i].scoreText(s.whiteToMove), sq * 0.2f, color, m)
        }

        // The best line's continuation over them (a reply may be the same
        // move as another line's arrow), under the best move.
        val best = s.lines.firstOrNull()
        if (best != null && st.pathPlies > 0) {
            val plies = min(st.pathPlies + 1, best.pv.size)
            val labels = pathLabels(s.whiteToMove, plies)
            for (i in 1 until plies) {
                val mv = Move.parse(best.pv[i]) ?: break
                val (white, number) = labels[i]
                val fill = if (white) WHITE_FILL else BLACK_FILL
                val line = if (white) WHITE_EDGE else BLACK_EDGE
                out += Shape.Poly(arrowOutline(route(g, mv), sq * 0.07f, sq * 0.24f, sq * 0.14f), fill, line, edge)
                val c = g.centre(mv.to, slots.take(mv.to, Corner.TOP_LEFT))
                marks += Shape.Circle(c.x, c.y, sq * 0.155f, fill or OPAQUE, line or OPAQUE, edge)
                marks += centred(c, "$number", sq * 0.2f, if (white) 0xFF161616.toInt() else 0xFFFFFFFF.toInt(), m)
            }
        }

        // The best move on top.
        if (n > 0) {
            Move.parse(best?.pv?.firstOrNull() ?: "")?.let { mv ->
                out += Shape.Poly(arrowOutline(route(g, mv), sq * 0.17f, sq * 0.42f, sq * 0.27f),
                    LINE_COLORS[0].withAlpha(0xE6), 0x99000000.toInt(), edge)
                if (st.arrowLabels) marks += pill(g.centre(mv.to, 0f, 0.34f), best!!.scoreText(s.whiteToMove), sq * 0.2f, LINE_COLORS[0], m)
            }
        }
        val all = out + marks
        return if (s.provisional) all.map { it.alphaScaled(0.55f) } else all
    }

    /**
     * For the first `plies` moves of a line starting with `whiteToMove`:
     * (is White's move, that side's own move number), each side counted from
     * 1 on its own. After 1. Bb5 a6 2. Ba4 Nf6 3. O-O the white numbers read
     * 1, 2, 3 and the black ones 1, 2.
     */
    fun pathLabels(whiteToMove: Boolean, plies: Int): List<Pair<Boolean, Int>> {
        var white = whiteToMove
        var w = 0
        var b = 0
        return List(plies) {
            val label = if (white) true to ++w else false to ++b
            white = !white
            label
        }
    }

    /** Arrow route: straight, or an L for a knight's jump (longer leg first). */
    fun route(g: BoardGeom, mv: Move): List<P> {
        val c0 = g.col(mv.from); val r0 = g.row(mv.from)
        val c1 = g.col(mv.to); val r1 = g.row(mv.to)
        val a = g.at(c0 + 0.5f, r0 + 0.5f)
        val b = g.at(c1 + 0.5f, r1 + 0.5f)
        val dc = abs(c1 - c0); val dr = abs(r1 - r0)
        if (!((dc == 1 && dr == 2) || (dc == 2 && dr == 1))) return listOf(a, b)
        val bend = if (dr == 2) g.at(c0 + 0.5f, r1 + 0.5f) else g.at(c1 + 0.5f, r0 + 0.5f)
        return listOf(a, bend, b)
    }

    private fun centred(c: P, text: String, size: Float, color: Int, m: TextMeasure) =
        Shape.Text(c.x, c.y + size * 0.36f, text, size, color, true, Align.CENTER, m.width(text, size, true))

    private fun pill(c: P, text: String, size: Float, color: Int, m: TextMeasure): List<Shape> {
        val w = m.width(text, size, true) + size * 0.7f
        val h = size * 1.35f
        return listOf(
            Shape.Box(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2, h / 2, color.withAlpha(0xF0)),
            Shape.Text(c.x, c.y + size * 0.36f, text, size, 0xFFFFFFFF.toInt(), true, Align.CENTER, w - size * 0.7f),
        )
    }

    private const val OPAQUE = 0xFF000000.toInt()
}

/** A UCI move's squares (promotion ignored: it does not change the arrow). */
class Move(val from: Int, val to: Int) {
    companion object {
        fun parse(uci: String): Move? {
            if (uci.length < 4) return null
            val f = Board.squareOf(uci.substring(0, 2))
            val t = Board.squareOf(uci.substring(2, 4))
            return if (f < 0 || t < 0 || f == t) null else Move(f, t)
        }
    }
}

enum class Corner(val dc: Float, val dr: Float) {
    TOP_LEFT(-0.31f, -0.31f), TOP_RIGHT(0.31f, -0.31f), BOTTOM_LEFT(-0.31f, 0.31f), BOTTOM_RIGHT(0.31f, 0.31f)
}

/** Hands out the four corners of each square, so markers never stack. */
class CornerSlots {
    private val used = HashMap<Int, MutableSet<Corner>>()

    fun take(square: Int, preferred: Corner): Corner {
        val set = used.getOrPut(square) { mutableSetOf() }
        val order = listOf(preferred) + Corner.values().filter { it != preferred }
        val c = order.firstOrNull { it !in set } ?: preferred
        set += c
        return c
    }
}

fun BoardGeom.centre(square: Int, corner: Corner): P = centre(square, corner.dc, corner.dr)
