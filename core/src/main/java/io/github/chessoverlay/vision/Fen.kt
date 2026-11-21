package io.github.chessoverlay.vision

/** FEN placement helpers; class order of the models. */
object Fen {
    const val CLASSES = "1KQRBNPkqrbnp"  // index 0 = empty

    fun argmax(v: FloatArray): Int = v.indices.maxBy { v[it] }

    /** Class index of a board piece ('.' = empty). */
    fun classOf(piece: Char): Int = if (piece == '.') 0 else CLASSES.indexOf(piece)

    fun fromIndices(idx: IntArray): String {
        val sb = StringBuilder()
        for (r in 0 until 8) {
            var empty = 0
            for (c in 0 until 8) {
                val ch = CLASSES[idx[r * 8 + c]]
                if (ch == '1') empty++ else { if (empty > 0) sb.append(empty); empty = 0; sb.append(ch) }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }
        return sb.toString()
    }

    private fun expand(p: String) = p.replace(Regex("[2-8]")) { "1".repeat(it.value.toInt()) }

    private fun compress(p: String) = p.replace(Regex("1{2,8}")) { it.value.length.toString() }

    fun flip(p: String): String =
        compress(expand(p).split('/').reversed().joinToString("/") { it.reversed() })
}

/** Board-level consistency step (port of chessimg2pos v2 legality.py). */
object Legality {
    private val K = Fen.CLASSES.indexOf('K'); private val k = Fen.CLASSES.indexOf('k')
    private val P = Fen.CLASSES.indexOf('P'); private val p = Fen.CLASSES.indexOf('p')
    private val WHITE = "KQRBNP".map { Fen.CLASSES.indexOf(it) }.toSet()
    private val BLACK = "kqrbnp".map { Fen.CLASSES.indexOf(it) }.toSet()

    private fun demote(probs: Array<FloatArray>, idx: IntArray, sq: Int, banned: Set<Int>, lock: Float): Boolean {
        if (probs[sq][idx[sq]] >= lock) return false
        val order = probs[sq].indices.sortedByDescending { probs[sq][it] }
        for (c in order) if (c !in banned) { idx[sq] = c; return true }
        return false
    }

    fun fix(probs: Array<FloatArray>, lock: Float = 0.97f): Pair<IntArray, Int> {
        val idx = IntArray(64) { Fen.argmax(probs[it]) }
        val orig = idx.copyOf()
        for (sq in (0 until 8) + (56 until 64)) if (idx[sq] == P || idx[sq] == p) demote(probs, idx, sq, setOf(P, p), lock)
        for (king in listOf(K, k)) {
            val squares = (0 until 64).filter { idx[it] == king }.sortedByDescending { probs[it][king] }
            if (squares.size > 1) for (s in squares.drop(1)) demote(probs, idx, s, setOf(king), lock)
        }
        for ((side, pawn) in listOf(WHITE to P, BLACK to p)) {
            val pawns = (0 until 64).filter { idx[it] == pawn }.sortedBy { probs[it][pawn] }
            if (pawns.size > 8) for (s in pawns.take(pawns.size - 8)) demote(probs, idx, s, setOf(pawn), lock)
            val pieces = (0 until 64).filter { idx[it] in side }.sortedBy { probs[it][idx[it]] }
            if (pieces.size > 16) for (s in pieces.take(pieces.size - 16)) demote(probs, idx, s, side, lock)
        }
        return idx to (0 until 64).count { idx[it] != orig[it] }
    }
}
