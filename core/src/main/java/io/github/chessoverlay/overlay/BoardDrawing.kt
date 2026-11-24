package io.github.chessoverlay.overlay

/**
 * The board layer's drawing, as shapes. It is built again only when it would
 * change (the same [signature] gives the same picture), so that the layer
 * does not redraw on every engine update.
 *
 * [clear] empties it for a capture of the screen and forgets what was drawn:
 * the next state is drawn again even when it is the same one, because
 * nothing is on screen then. A read that ends with the same position (a
 * misread, a highlight that went, a capture taken too early) must not leave
 * the board without its arrows.
 */
class BoardDrawing(private val style: () -> BoardStyle, private val measure: TextMeasure) {
    var shapes: List<Shape> = emptyList()
        private set
    private var key: String? = null

    /** The drawing of `s`. True when it is new, so [shapes] changed. */
    fun show(s: OverlayState): Boolean {
        val st = style()
        val k = signature(s, st)
        if (k == key) return false
        key = k
        shapes = BoardLayer.build(s, st, measure)
        return true
    }

    /** Nothing is drawn now. */
    fun clear() {
        key = null
        shapes = emptyList()
    }

    companion object {
        /** What the board drawing depends on: same signature, same picture. */
        fun signature(s: OverlayState, st: BoardStyle): String {
            val k = s.corners ?: return ""
            val sb = StringBuilder()
            for (p in k) sb.append(p[0].toInt()).append(',').append(p[1].toInt()).append(';')
            sb.append(s.blackAtBottom).append(s.whiteToMove).append(s.provisional).append(st)
            s.board?.let { sb.append(it.placement()) }
            s.lastMove?.let { sb.append(it.square).append(it.label) }
            for ((i, l) in s.lines.take(st.arrows.coerceAtLeast(1)).withIndex()) {
                val n = if (i == 0) st.pathPlies + 1 else 1
                sb.append('|').append(l.pv.take(n).joinToString(" "))
                if (st.arrowLabels) sb.append(l.scoreText(s.whiteToMove))
            }
            return sb.toString()
        }
    }
}
