package io.github.chessoverlay.chess

/**
 * A minimal board: enough to turn a recognised piece placement into a full,
 * legal FEN for an engine, and to print engine moves readably. Squares are
 * 0..63 with a1 = 0, h1 = 7, a8 = 56. Pieces are FEN letters, '.' = empty.
 */
class Board private constructor(private val sq: CharArray) {

    fun copy() = Board(sq.copyOf())

    operator fun get(square: Int): Char = sq[square]

    /** FEN piece placement (first field). */
    fun placement(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val c = sq[rank * 8 + file]
                if (c == '.') {
                    empty++
                } else {
                    if (empty > 0) sb.append(empty).also { empty = 0 }
                    sb.append(c)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        return sb.toString()
    }

    fun kingSquare(white: Boolean): Int = sq.indexOf(if (white) 'K' else 'k')

    fun count(piece: Char): Int = sq.count { it == piece }

    /** True if `square` is attacked by a piece of the given colour. */
    fun attacked(square: Int, byWhite: Boolean): Boolean {
        val f = square % 8
        val r = square / 8
        fun at(file: Int, rank: Int): Char =
            if (file in 0..7 && rank in 0..7) sq[rank * 8 + file] else '#'
        fun own(c: Char, type: Char) = c == if (byWhite) type.uppercaseChar() else type
        // Pawns: a white pawn attacks diagonally upwards.
        val pr = if (byWhite) r - 1 else r + 1
        if (own(at(f - 1, pr), 'p') || own(at(f + 1, pr), 'p')) return true
        for ((df, dr) in KNIGHT) if (own(at(f + df, r + dr), 'n')) return true
        for ((df, dr) in KING) if (own(at(f + df, r + dr), 'k')) return true
        for ((df, dr) in ROOK_DIRS) {
            var x = f + df; var y = r + dr
            while (true) {
                val c = at(x, y)
                if (c == '#') break
                if (c != '.') { if (own(c, 'r') || own(c, 'q')) return true; break }
                x += df; y += dr
            }
        }
        for ((df, dr) in BISHOP_DIRS) {
            var x = f + df; var y = r + dr
            while (true) {
                val c = at(x, y)
                if (c == '#') break
                if (c != '.') { if (own(c, 'b') || own(c, 'q')) return true; break }
                x += df; y += dr
            }
        }
        return false
    }

    fun inCheck(white: Boolean): Boolean {
        val k = kingSquare(white)
        return k >= 0 && attacked(k, !white)
    }

    /** Castling rights that the placement allows: king and rook on their
     *  original squares. (Whether they moved before cannot be seen.) */
    fun castling(): String {
        val sb = StringBuilder()
        if (sq[4] == 'K') { if (sq[7] == 'R') sb.append('K'); if (sq[0] == 'R') sb.append('Q') }
        if (sq[60] == 'k') { if (sq[63] == 'r') sb.append('k'); if (sq[56] == 'r') sb.append('q') }
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    /** Why this placement with this side to move cannot be given to an
     *  engine, or null if it can. */
    fun problem(whiteToMove: Boolean): String? {
        // A real game can never break these (promotions only replace pawns),
        // so a board that does was read wrongly.
        if (count('K') != 1 || count('k') != 1) return "${count('K')} white and ${count('k')} black kings"
        for (i in (0..7) + (56..63)) if (sq[i] == 'P' || sq[i] == 'p') return "a pawn on the first or last rank"
        if (count('P') > 8 || count('p') > 8) return "more than 8 pawns of one colour"
        val white = sq.count { it.isUpperCase() }
        val black = sq.count { it.isLowerCase() }
        if (white > 16 || black > 16) return "$white white and $black black pieces; a side has at most 16"
        if (inCheck(!whiteToMove)) return "the side not to move is in check"
        return null
    }

    fun fen(whiteToMove: Boolean): String =
        "${placement()} ${if (whiteToMove) "w" else "b"} ${castling()} - 0 1"

    /** Play a UCI move (e2e4, e7e8q, e1g1) without legality checks. */
    fun play(uci: String) {
        val from = squareOf(uci.substring(0, 2))
        val to = squareOf(uci.substring(2, 4))
        val piece = sq[from]
        val white = piece.isUpperCase()
        // Castling: the king moves two files; bring the rook across.
        if (piece.lowercaseChar() == 'k' && kotlin.math.abs(to - from) == 2) {
            val (rf, rt) = if (to > from) from + 3 to from + 1 else from - 4 to from - 1
            sq[rt] = sq[rf]; sq[rf] = '.'
        }
        // En passant: a pawn moves diagonally onto an empty square.
        if (piece.lowercaseChar() == 'p' && (to - from) % 8 != 0 && sq[to] == '.') {
            sq[if (white) to - 8 else to + 8] = '.'
        }
        sq[to] = if (uci.length > 4) (if (white) uci[4].uppercaseChar() else uci[4]) else piece
        sq[from] = '.'
    }

    /** Short algebraic notation for a UCI move in this position: piece
     *  letter (with the file or rank when two such pieces could go there),
     *  x for captures, destination, =Q for promotions, O-O for castling, +
     *  for check. Mate is not detected here (that needs every reply). */
    fun moveText(uci: String): String {
        if (uci.length < 4) return uci
        val from = squareOf(uci.substring(0, 2))
        val to = squareOf(uci.substring(2, 4))
        if (from !in 0..63 || to !in 0..63) return uci
        val piece = sq[from]
        if (piece == '.') return uci
        val type = piece.uppercaseChar()
        val sb = StringBuilder()
        if (type == 'K' && kotlin.math.abs(to - from) == 2) {
            sb.append(if (to > from) "O-O" else "O-O-O")
        } else {
            val capture = sq[to] != '.' || (type == 'P' && (to - from) % 8 != 0)
            if (type == 'P') {
                if (capture) sb.append('a' + from % 8)
            } else {
                sb.append(type)
                if (type != 'K') sb.append(disambiguation(from, to, piece))
            }
            if (capture) sb.append('x')
            sb.append(uci.substring(2, 4))
            if (uci.length > 4) sb.append('=').append(uci[4].uppercaseChar())
        }
        val after = copy()
        after.play(uci)
        if (after.inCheck(!piece.isUpperCase())) sb.append('+')
        return sb.toString()
    }

    /** "", a file, a rank or a square: what tells `from` apart from other
     *  pieces like it that could also move to `to` (pinned ones cannot). */
    private fun disambiguation(from: Int, to: Int, piece: Char): String {
        val white = piece.isUpperCase()
        val others = (0..63).filter { o ->
            o != from && sq[o] == piece && reaches(o, to) &&
                copy().also { it.play(squareName(o) + squareName(to)) }.inCheck(white).not()
        }
        if (others.isEmpty()) return ""
        if (others.none { it % 8 == from % 8 }) return "${'a' + from % 8}"
        if (others.none { it / 8 == from / 8 }) return "${'1' + from / 8}"
        return squareName(from)
    }

    /**
     * Whether the piece on `from` could move to `to` by the way it moves
     * (paths clear, no own piece on `to`). Checks, pins and castling rights
     * are not considered: this only rules out moves no piece can make, so a
     * misread is not taken for a move.
     */
    fun canMove(from: Int, to: Int): Boolean {
        val p = sq[from]
        if (p == '.' || from == to) return false
        val t = sq[to]
        if (t != '.' && t.isUpperCase() == p.isUpperCase()) return false
        val df = to % 8 - from % 8
        val dr = to / 8 - from / 8
        return when (p.lowercaseChar()) {
            'p' -> {
                val dir = if (p.isUpperCase()) 1 else -1
                val start = if (p.isUpperCase()) 1 else 6
                when {
                    df == 0 && dr == dir -> t == '.'
                    df == 0 && dr == 2 * dir -> from / 8 == start && t == '.' && sq[from + 8 * dir] == '.'
                    // A capture, or en passant: onto an empty square only past an enemy pawn.
                    kotlin.math.abs(df) == 1 && dr == dir ->
                        t != '.' || sq[to - 8 * dir] == (if (p.isUpperCase()) 'p' else 'P')
                    else -> false
                }
            }
            'k' -> (kotlin.math.abs(df) <= 1 && kotlin.math.abs(dr) <= 1) ||
                (dr == 0 && kotlin.math.abs(df) == 2 && from % 8 == 4 && (from / 8 == 0 || from / 8 == 7))
            else -> reaches(from, to)
        }
    }

    /**
     * The moves `white` can make (UCI, promotions included) that do not
     * leave its own king in check. Castling rights and en passant timing
     * cannot be seen on a board, so they are allowed when the pieces fit.
     */
    fun legalMoves(white: Boolean): List<String> {
        val out = ArrayList<String>()
        for (from in 0..63) {
            val p = sq[from]
            if (p == '.' || p.isUpperCase() != white) continue
            for (to in 0..63) {
                if (!canMove(from, to)) continue
                if (p.lowercaseChar() == 'k' && kotlin.math.abs(to - from) == 2) {
                    // Castling: the rook in its corner, the squares between empty.
                    val rook = if (to > from) from + 3 else from - 4
                    if (sq[rook] != (if (white) 'R' else 'r')) continue
                    if ((minOf(from, rook) + 1 until maxOf(from, rook)).any { sq[it] != '.' }) continue
                }
                val base = squareName(from) + squareName(to)
                val promo = p.lowercaseChar() == 'p' && (to / 8 == 7 || to / 8 == 0)
                for (m in if (promo) listOf("${base}q", "${base}r", "${base}b", "${base}n") else listOf(base)) {
                    val b = copy()
                    b.play(m)
                    if (!b.inCheck(white)) out += m
                }
            }
        }
        return out
    }

    /** Whether the knight, bishop, rook or queen on `from` attacks `to`. */
    private fun reaches(from: Int, to: Int): Boolean {
        val df = to % 8 - from % 8
        val dr = to / 8 - from / 8
        val adf = kotlin.math.abs(df)
        val adr = kotlin.math.abs(dr)
        val line = when (sq[from].lowercaseChar()) {
            'n' -> return (adf == 1 && adr == 2) || (adf == 2 && adr == 1)
            'b' -> adf == adr
            'r' -> df == 0 || dr == 0
            'q' -> adf == adr || df == 0 || dr == 0
            else -> return false
        }
        if (!line) return false
        val step = Integer.signum(dr) * 8 + Integer.signum(df)
        var s = from + step
        while (s != to) { if (sq[s] != '.') return false; s += step }
        return true
    }

    /** Text for a whole line, e.g. "1. e4 e5 2. Nf3" (or "1... e5 2. Nf3"). */
    fun lineText(pv: List<String>, whiteToMove: Boolean, maxMoves: Int = 8): String {
        val b = copy()
        val sb = StringBuilder()
        var white = whiteToMove
        var n = 1
        for ((i, m) in pv.take(maxMoves).withIndex()) {
            if (white) sb.append("$n. ") else if (i == 0) sb.append("$n... ")
            sb.append(b.moveText(m)).append(' ')
            b.play(m)
            if (!white) n++
            white = !white
        }
        return sb.toString().trim()
    }

    companion object {
        private val KNIGHT = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)
        private val KING = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val ROOK_DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val BISHOP_DIRS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

        fun squareOf(name: String): Int {
            if (name.length != 2) return -1
            val f = name[0] - 'a'
            val r = name[1] - '1'
            return if (f in 0..7 && r in 0..7) r * 8 + f else -1
        }

        /** From the first FEN field. Throws IllegalArgumentException if malformed. */
        fun fromPlacement(placement: String): Board {
            val rows = placement.trim().split(' ')[0].split('/')
            require(rows.size == 8) { "placement needs 8 ranks" }
            val sq = CharArray(64) { '.' }
            for ((i, row) in rows.withIndex()) {
                val rank = 7 - i
                var file = 0
                for (c in row) {
                    if (c.isDigit()) {
                        file += c - '0'
                    } else {
                        require(c in "KQRBNPkqrbnp") { "bad piece '$c'" }
                        require(file < 8) { "rank too long" }
                        sq[rank * 8 + file] = c
                        file++
                    }
                }
                require(file == 8) { "rank ${8 - i} has $file squares" }
            }
            return Board(sq)
        }

        /**
         * The side to move for a recognised position. A side whose king is
         * in check must be the one to move; otherwise the preferred side, or
         * the other one if only that is valid. Returns null if neither is.
         */
        fun sideToMove(b: Board, preferWhite: Boolean): Boolean? {
            if (b.problem(preferWhite) == null) return preferWhite
            if (b.problem(!preferWhite) == null) return !preferWhite
            return null
        }

        /**
         * The single move that turns `before` into `after` (UCI, e.g. "e2e4",
         * "e1g1", "e7e8q"), or null if there is none. Tries every from/to
         * pair among the changed squares that the piece could move between
         * (see canMove), so castling, en passant and promotions are found
         * too. `white` = only moves of that colour.
         */
        fun findMove(before: Board, after: Board, white: Boolean? = null): String? {
            val changed = (0..63).filter { before[it] != after[it] }
            if (changed.isEmpty() || changed.size > 4) return null
            val target = after.placement()
            for (from in changed) {
                val p = before[from]
                if (p == '.' || after[from] != '.') continue  // the mover leaves its square empty
                if (white != null && p.isUpperCase() != white) continue
                for (to in changed) {
                    if (to == from || !before.canMove(from, to)) continue
                    val base = squareName(from) + squareName(to)
                    val promo = p.lowercaseChar() == 'p' && (to / 8 == 7 || to / 8 == 0)
                    for (suffix in if (promo) listOf("q", "r", "b", "n") else listOf("")) {
                        val m = base + suffix
                        val b = before.copy()
                        b.play(m)
                        if (b.placement() == target) return m
                    }
                }
            }
            return null
        }

        /**
         * Two moves, the first by `firstWhite`'s side, that turn `before`
         * into `after`: the reply came before the screen was read. The side
         * to move is then the same as before them. Null if none.
         */
        fun findTwoMoves(before: Board, after: Board, firstWhite: Boolean): Pair<String, String>? {
            val changed = (0..63).filter { before[it] != after[it] }
            if (changed.size < 2 || changed.size > 8) return null
            for (from in changed) {
                val p = before[from]
                if (p == '.' || p.isUpperCase() != firstWhite) continue
                // The first move may land where the reply recaptures, which can
                // look unchanged (a pawn taken and retaken by a pawn): any square.
                for (to in 0..63) {
                    if (!before.canMove(from, to)) continue
                    val base = squareName(from) + squareName(to)
                    val promo = p.lowercaseChar() == 'p' && (to / 8 == 7 || to / 8 == 0)
                    for (suffix in if (promo) listOf("q", "r", "b", "n") else listOf("")) {
                        val mid = before.copy()
                        mid.play(base + suffix)
                        val reply = findMove(mid, after, !firstWhite) ?: continue
                        return base + suffix to reply
                    }
                }
            }
            return null
        }

        fun squareName(sq: Int) = "${'a' + sq % 8}${'1' + sq / 8}"

        /**
         * Which colour moved between two placements, if they differ by about
         * one move: the colour of the pieces that left their squares.
         */
        fun mover(before: Board, after: Board): Boolean? {
            var white = 0
            var black = 0
            for (i in 0..63) {
                val a = before[i]
                val b = after[i]
                if (a != '.' && a != b) {
                    // A piece left (or was captured on) this square. Count
                    // only squares that became empty: captured pieces are
                    // replaced, the mover's origin square is emptied.
                    if (b == '.') { if (a.isUpperCase()) white++ else black++ }
                }
            }
            return when {
                white > 0 && black == 0 -> true
                black > 0 && white == 0 -> false
                else -> null
            }
        }
    }
}
