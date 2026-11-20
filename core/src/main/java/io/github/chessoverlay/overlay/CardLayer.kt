package io.github.chessoverlay.overlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the card shows. `dp` = pixels per dp. */
data class CardStyle(
    val dp: Float = 1f,
    val evalBar: Boolean = true,
    val showLines: Boolean = true,
    val lines: Int = 3,
    val arrows: Int = 2,
)

/** The state of the card's buttons. `side`: null = worked out, true/false = White/Black forced. */
data class CardControls(val auto: Boolean = false, val side: Boolean? = null, val shown: Boolean = true)

enum class CardButton { READ, AUTO, SIDE, SHOW, CLOSE }

class CardHit(val button: CardButton, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** `foldTop`: taps below this (not on a button) fold the lines. */
class CardLayout(val shapes: List<Shape>, val height: Float, val hits: List<CardHit>, val foldTop: Float) {
    fun buttonAt(x: Float, y: Float): CardButton? = hits.firstOrNull { x in it.x0..it.x1 && y in it.y0..it.y1 }?.button
}

/**
 * The floating card, in its own coordinates (0, 0 = its top-left):
 *
 *     ⠿  ▶  A  ⇅  ◐  ✕              ○ d24  [+1.2]
 *     ▕██████████████░░░░░░░░░░░░░░░░▏
 *     ▍+1.2  Bb5 a6 Ba4 Nf6 O-O Be7 Re1 …
 *     ▍+0.9  d4 exd4 Nxd4 Nf6 Nxc6 …
 *     ▍+0.4  Bc4 Bc5 c3 Nf6 …
 *     a message line, only when there is something to say
 *
 * The rows are always there (empty until lines arrive), so the card keeps
 * its size from move to move.
 *
 * The pill is the score from White's view; the bar fills from the side of
 * the player at the bottom of the board; the dot is the side to move. Each
 * engine line is one row: its chip matches its arrow, the first move is
 * bold, the side to move's later moves are bright and the opponent's
 * dimmed, so a line reads at a glance without move numbers (which a
 * screenshot cannot know).
 */
object CardLayer {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private val BACKGROUND = 0xF2181818.toInt()
    private val TEXT = 0xFFE8E8E8.toInt()
    private val DIM = 0xFF8E8E8E.toInt()
    private val TRACK = 0xFF3A3A3A.toInt()
    private val BAR_WHITE = 0xFFEDEDED.toInt()
    private val ON = 0xFF5FD068.toInt()

    /**
     * The card for state `s`. Its height depends only on the settings (and
     * on a rare message), not on whether lines have arrived, so it never
     * jumps. While a new position waits for its first lines, `ghost` (the
     * last state that had lines) is shown faded in their place.
     */
    fun build(s: OverlayState, width: Float, st: CardStyle, ui: CardControls, m: TextMeasure,
              collapsed: Boolean, ghost: OverlayState? = null): CardLayout {
        val d = st.dp
        val pad = 10 * d
        val body = ArrayList<Shape>()
        val hits = ArrayList<CardHit>()
        // What to show: this position's lines, or the last ones faded.
        val shownState = if (s.lines.isEmpty() && ghost != null && ghost.lines.isNotEmpty()) ghost else s
        val faded = shownState !== s
        val top = shownState.lines.firstOrNull()
        fun c(color: Int) = if (faded) fade(color) else color

        // Row 1: grip and buttons on the left, depth and score on the right.
        val rowH = 38 * d
        val mid = rowH / 2
        var x = 4 * d
        val gripW = 24 * d
        body += centred(x + gripW / 2, mid, "⠿", 16 * d, DIM, false, m)
        x += gripW
        var right = width - pad
        if (top != null) {
            val score = shortScore(top, shownState.whiteToMove)
            val white = top.whiteScore(shownState.whiteToMove) >= 0
            val ts = 14 * d
            val tw = m.width(score, ts, true)
            val pw = max(tw + 14 * d, 50 * d)
            val ph = 24 * d
            body += Shape.Box(right - pw, mid - ph / 2, right, mid + ph / 2, ph / 2,
                c(if (white) BAR_WHITE else 0xFF0E0E0E.toInt()), if (white) 0 else c(0xFF5A5A5A.toInt()), 1.2f * d)
            body += centred(right - pw / 2, mid, score, ts, c(if (white) 0xFF111111.toInt() else WHITE), true, m)
            right -= pw + 8 * d
            val ds = 12 * d
            val depth = "d${top.depth}"
            val dw = m.width(depth, ds, false)
            body += Shape.Text(right, mid + ds * 0.36f, depth, ds, c(DIM), false, Align.RIGHT, dw)
            right -= dw + 9 * d
            body += Shape.Circle(right, mid, 5 * d, c(if (s.whiteToMove) WHITE else 0xFF0E0E0E.toInt()),
                if (s.whiteToMove) 0 else 0xFFA0A0A0.toInt(), 1.2f * d)
            right -= 9 * d
        }
        val buttons = listOf(
            CardButton.READ to "▶",
            CardButton.AUTO to "A",
            CardButton.SIDE to when (ui.side) { null -> "⇅"; true -> "W"; false -> "B" },
            CardButton.SHOW to "◐",
            CardButton.CLOSE to "✕",
        )
        val bw = min(38 * d, max(28 * d, (right - x) / buttons.size))
        for ((b, label) in buttons) {
            val color = when {
                b == CardButton.AUTO && ui.auto -> ON
                b == CardButton.SIDE && ui.side != null -> ON
                b == CardButton.SHOW && !ui.shown -> DIM
                else -> WHITE
            }
            body += centred(x + bw / 2, mid, label, 17 * d, color, b == CardButton.AUTO || b == CardButton.SIDE, m)
            hits += CardHit(b, x, 0f, x + bw, rowH)
            x += bw
        }
        var y = rowH

        // Row 2: the evaluation bar (an empty track until there is a score).
        if (st.evalBar) {
            val h = 12 * d
            val bh = 6 * d
            val cy = y + h / 2 - 1 * d
            val x0 = pad
            val x1 = width - pad
            body += Shape.Box(x0, cy - bh / 2, x1, cy + bh / 2, bh / 2, TRACK)
            if (top != null) {
                val share = (x1 - x0) * winChance(top.whiteScore(shownState.whiteToMove)).toFloat()
                if (share > 1f) {
                    body += if (!s.blackAtBottom) Shape.Box(x0, cy - bh / 2, x0 + share, cy + bh / 2, bh / 2, c(BAR_WHITE))
                    else Shape.Box(x1 - share, cy - bh / 2, x1, cy + bh / 2, bh / 2, c(BAR_WHITE))
                }
            }
            val mx = (x0 + x1) / 2
            body += Shape.Box(mx - 0.75f * d, cy - 5 * d, mx + 0.75f * d, cy + 5 * d, 0f, 0xFF808080.toInt())
            y += h
        }

        // The engine lines: always st.lines rows, so the card keeps its size.
        val message = s.status.takeIf { it.isNotEmpty() && it != OverlayState.ANALYSING }
        var messageShown = false
        if (st.showLines && !collapsed) {
            val ts = 13 * d
            val lineH = 22 * d
            val board = shownState.board
            val shown = if (board != null) shownState.lines.take(st.lines) else emptyList()
            if (shown.isEmpty()) {
                // Nothing yet: say why in the first row.
                val text = fit(message ?: s.status.ifEmpty { "Looking for a board…" }, width - 2 * pad, ts, m)
                body += Shape.Text(pad, y + lineH / 2 + ts * 0.36f, text, ts, DIM, false, Align.LEFT, m.width(text, ts, false))
                messageShown = true
            } else {
                val scores = shown.map { shortScore(it, shownState.whiteToMove) }
                val scoreW = scores.maxOf { m.width(it, ts, true) } + 9 * d
                for ((i, line) in shown.withIndex()) {
                    val base = y + i * lineH + lineH / 2 + ts * 0.36f
                    val chip = if (i < st.arrows) BoardLayer.LINE_COLORS[i % BoardLayer.LINE_COLORS.size] else 0xFF5A5A5A.toInt()
                    body += Shape.Box(pad, y + i * lineH + 4 * d, pad + 4 * d, y + (i + 1) * lineH - 4 * d, 2 * d, c(chip))
                    val sx = pad + 10 * d
                    body += Shape.Text(sx, base, scores[i], ts, c(WHITE), true, Align.LEFT, m.width(scores[i], ts, true))
                    moves(body, board!!, line, sx + scoreW, width - pad, base, ts, m, faded)
                }
            }
            y += st.lines * lineH
        }

        // A message that has no row yet (rare: errors, a manual read that failed).
        if (!messageShown && (message != null || (top == null && !(st.showLines && !collapsed)))) {
            val ts = 12.5f * d
            val h = 22 * d
            val text = fit(message ?: s.status.ifEmpty { "Looking for a board…" }, width - 2 * pad, ts, m)
            body += Shape.Text(pad, y + h / 2 + ts * 0.36f, text, ts, DIM, false, Align.LEFT, m.width(text, ts, false))
            y += h
        }
        y += 4 * d
        val bg = Shape.Box(0f, 0f, width, y, 14 * d, BACKGROUND)
        return CardLayout(listOf(bg) + body, y, hits, rowH)
    }

    /** One line's moves as text pieces, cut with "…" where the row ends. */
    private fun moves(out: MutableList<Shape>, board: Board, line: PvLine, x0: Float, x1: Float,
                      base: Float, ts: Float, m: TextMeasure, faded: Boolean) {
        fun c(color: Int) = if (faded) fade(color) else color
        val tokens = san(board, line)
        val space = m.width(" ", ts, false)
        val more = m.width("…", ts, false)
        var x = x0
        for ((i, t) in tokens.withIndex()) {
            val bold = i == 0
            val w = m.width(t, ts, bold)
            val room = if (i == tokens.size - 1) x1 else x1 - more - space
            if (x + w > room) {
                if (x + more <= x1) out += Shape.Text(x, base, "…", ts, c(DIM), false, Align.LEFT, more)
                return
            }
            out += Shape.Text(x, base, t, ts, c(if (bold) WHITE else if (i % 2 == 0) TEXT else DIM), bold, Align.LEFT, w)
            x += w + space
        }
    }

    /** The line in short algebraic notation, with + for checks and # for the mate that ends a mating line. */
    fun san(board: Board, line: PvLine): List<String> {
        val b = board.copy()
        val out = ArrayList<String>()
        for (mv in line.pv.take(16)) {
            if (mv.length < 4) break
            out += b.moveText(mv)
            b.play(mv)
        }
        val mate = line.mate
        if (mate != null && out.isNotEmpty() && out.last().endsWith("+")) {
            val plies = if (mate > 0) 2 * mate - 1 else 2 * abs(mate)
            if (line.pv.size == plies && out.size == plies) out[out.size - 1] = out.last().dropLast(1) + "#"
        }
        return out
    }

    /** "+1.3", "-0.4", "0.0", "M3", "-M5" (White's view, one decimal). */
    fun shortScore(line: PvLine, whiteToMove: Boolean): String {
        if (line.mate != null) return line.scoreText(whiteToMove)
        val v = line.whiteScore(whiteToMove)
        val r = Math.signum(v) * Math.round(abs(v) * 10) / 10.0  // symmetric: -0.35 -> -0.4 like +0.35 -> +0.4
        return when {
            r == 0.0 -> "0.0"
            r > 0 -> "+" + String.format(java.util.Locale.US, "%.1f", r)
            else -> String.format(java.util.Locale.US, "%.1f", r)
        }
    }

    /** The same colour at 40% opacity: the last position's lines while new ones come. */
    private fun fade(color: Int): Int = (color and 0xFFFFFF) or (((color ushr 24) * 2 / 5) shl 24)

    private fun centred(cx: Float, cy: Float, text: String, size: Float, color: Int, bold: Boolean, m: TextMeasure) =
        Shape.Text(cx, cy + size * 0.36f, text, size, color, bold, Align.CENTER, m.width(text, size, bold))

    private fun fit(s: String, w: Float, size: Float, m: TextMeasure): String {
        if (m.width(s, size, false) <= w) return s
        var e = s
        while (e.isNotEmpty() && m.width("$e…", size, false) > w) e = e.dropLast(1)
        return "$e…"
    }
}
