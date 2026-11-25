package io.github.chessoverlay.vision

import io.github.chessoverlay.chess.Board
import kotlin.math.abs
import kotlin.math.max

/**
 * Follows one game on screen.
 *
 * - Change detection: the colours at 49 points of every square, kept from
 *   the last clean read, are compared with the screen. Points the overlay
 *   draws on are skipped (see Cover), so this works with the overlay
 *   showing; the recognizer (the expensive part, and the only step that
 *   needs the overlay hidden) runs only when squares really changed.
 * - A read that is not a reachable position (a piece caught mid-move) is
 *   reported as Invalid and changes nothing here, so the next check sees
 *   the same change and reads again.
 * - Game tracking: consecutive positions are linked by the exact move
 *   between them (Board.findMove), which gives the side to move and the move
 *   just played; a jump of several moves or a new game falls back to the
 *   colour that left squares, then to the side at the bottom. Two moves at
 *   once (a quick reply) are found too and keep the side to move.
 * - What only the moves show goes with them: a linked position is the last
 *   one after its move, so it keeps the castling rights, the en passant
 *   square and the move count (see Board).
 * - The game's memory: the positions of the line followed so far are kept
 *   ([history]). A reading that no move explains but that is one of them (a
 *   step back in the move list, an undo, a jump to an earlier move) is taken
 *   as it was then, side to move and all. From that come the number of the
 *   move ([moveNumber], when the game was followed from its start) and how
 *   often the position came up ([repetitions]). A position never seen
 *   before is read from its pieces: castling is then taken to be possible
 *   while king and rook stand on their squares.
 * - What the site shows: a clear last-move highlight (LastMoveHighlight)
 *   gives the side to move where nothing else does (a first read, a jump,
 *   a game joined late), puts a guessed side right, and tells whether a
 *   pawn just advanced two squares.
 * - Moves seen at once ([explainChange]): which squares changed, and the
 *   legal moves, usually leave exactly one move (or a move and its reply)
 *   that explains them. That is known within a millisecond of the board
 *   coming to rest, without the recognizer, which then checks it ([accept]
 *   compares against the position before, see [applyFast]).
 * - The game is kept when the board leaves the screen for a while (a
 *   notification, another app): [reset] with `keepGame`.
 */
class BoardTracker(private val clock: () -> Long = System::currentTimeMillis) {
    var corners: List<FloatArray>? = null
        private set
    var blackAtBottom = false
        private set
    var board: Board? = null
        private set
    var whiteToMove = true
        private set
    /** The last move linked (UCI): sites highlight its squares. */
    var lastMove: String? = null
        private set
    /** The site's last-move highlight in the last read, when it was clear (see LastMoveHighlight). */
    var lastSeen: LastMoveHighlight.Seen? = null
        private set

    /** A position the game went through, as it was taken then. */
    private class Seen(val board: Board, val placement: String, val whiteToMove: Boolean, val lastMove: String?,
                       val sideKnown: Boolean, val ply: Int?)

    /** The positions of the line followed so far, before the current one, oldest first. */
    private val history = ArrayList<Seen>()
    /** Whether [whiteToMove] is known (a move showed it, the user set it, a check forced it), not guessed from the picture. */
    private var sideKnown = false

    /** Moves played in the game when it was followed from its start position; else null. */
    var ply: Int? = null
        private set
    /** The number of the move to be played (1 at the start), when [ply] is known; else null. */
    val moveNumber: Int? get() = ply?.let { it / 2 + 1 }
    /**
     * The time between the last moves (the median of them) when four moves
     * were timed at least; else null. A step back, a jump or another game
     * starts the timing again.
     */
    fun paceMs(): Long? {
        if (moveTimes.size < 4) return null
        val gaps = (1 until moveTimes.size).map { moveTimes[it] - moveTimes[it - 1] }.sorted()
        return gaps[gaps.size / 2]
    }

    /** A game whose moves come less than [FAST_PACE_MS] apart of late: the overlay reads and shows faster then. */
    fun fastGame(): Boolean = paceMs()?.let { it <= FAST_PACE_MS } ?: false
    /** How many times the current position came up in the game, itself included. */
    var repetitions = 1
        private set
    /** When the last moves were played (`clock`), to tell a fast game. */
    private val moveTimes = ArrayDeque<Long>()
    // Sample points (screen pixels) for the current corners, and their
    // colours in the last clean read: 64 squares x PER points, row-major from
    // the top-left square as drawn.
    private var xs: IntArray? = null
    private var ys: IntArray? = null
    private var ref: IntArray? = null

    /** Which screen pixels the overlay draws on (they are not compared). */
    fun interface Cover {
        fun covered(x: Int, y: Int): Boolean
    }

    sealed class Result {
        /** Nothing on the board changed. */
        object Same : Result()
        /** A new position: `move` was played when it is known; `back`: it is one the game was in before. */
        data class Position(
            val board: Board,
            val whiteToMove: Boolean,
            val move: String?,
            val previous: Board?,
            val previousWhiteToMove: Boolean,
            val back: Boolean = false,
        ) : Result()
        /** Recognised, but not a position an engine can take. */
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Forget where the board is. `keepGame`: remember the position (the board
     * left the screen, or the screen turned): when it is seen again, the game
     * goes on from it, side to move and all.
     */
    fun reset(keepGame: Boolean = false) {
        TrackerLog.add(if (keepGame) "board lost; the game is kept" else "reset: a new game")
        corners = null; xs = null; ys = null; ref = null; pendingFast = null
        if (!keepGame) {
            board = null; lastMove = null
            history.clear(); sideKnown = false; ply = null; repetitions = 1
            moveTimes.clear()
        }
    }

    // ---- moves seen at once -------------------------------------------------

    private class Fast(val prev: Board, val prevWhite: Boolean, val prevLast: String?)
    private var pendingFast: Fast? = null

    /**
     * The move (or a move and its reply) that explains which squares
     * changed since the last clean read, if exactly one does; null leaves it
     * to the recognizer. `now`: the colours at [points], `cover` as in
     * [compare].
     *
     * A move must change its squares (the pieces differ; one may be hidden
     * under the overlay's drawing), and every changed square must belong to
     * it, to the last move (whose highlight goes away) or to a king (a check
     * highlight). Promotions are left to the recognizer (which piece?).
     */
    fun explainChange(now: IntArray, cover: Cover?): List<String>? {
        val b = board ?: return null
        val x = xs ?: return null
        val y = ys ?: return null
        val r = ref ?: return null
        if (now.size != r.size) return null
        val state = squareStates(r, now, x, y, cover)
        val changed = BooleanArray(64)
        val blind = BooleanArray(64)
        for (sq in 0 until 64) when (state[PositionDecoder.drawnIndex(sq, blackAtBottom)]) {
            CHANGED -> changed[sq] = true
            BLIND -> blind[sq] = true
        }
        val nChanged = changed.count { it }
        if (nChanged == 0 || nChanged > MAX_FAST_CHANGED) return null
        val extra = BooleanArray(64)
        lastMove?.let { m -> extra[Board.squareOf(m.substring(0, 2))] = true; extra[Board.squareOf(m.substring(2, 4))] = true }
        b.kingSquare(true).takeIf { it >= 0 }?.let { extra[it] = true }
        b.kingSquare(false).takeIf { it >= 0 }?.let { extra[it] = true }

        fun fits(after: Board, last: String): Boolean {
            var blindDiff = 0
            var seenDiff = 0
            val allowed = extra.copyOf()
            allowed[Board.squareOf(last.substring(0, 2))] = true
            allowed[Board.squareOf(last.substring(2, 4))] = true
            for (sq in 0 until 64) {
                if (b[sq] == after[sq]) continue
                allowed[sq] = true
                when {
                    changed[sq] -> seenDiff++
                    blind[sq] -> blindDiff++
                    else -> return false   // a piece changed here, but the square looks the same
                }
            }
            if (seenDiff == 0 || blindDiff > 1) return false
            for (sq in 0 until 64) if (changed[sq] && !allowed[sq]) return false
            return true
        }

        val white = whiteToMove
        val moves = b.legalMoves(white)
        val singles = moves.filter { m -> fits(b.copy().also { it.play(m) }, m) }
        if (singles.isNotEmpty()) {
            val m = singles.singleOrNull() ?: return null
            return if (m.length > 4) null else listOf(m)
        }
        // A quick reply (a premove): one move each.
        val finals = HashMap<String, List<String>>()
        for (m in moves) {
            val f = Board.squareOf(m.substring(0, 2))
            if (!changed[f] && !blind[f]) continue
            val one = b.copy().also { it.play(m) }
            for (reply in one.legalMoves(!white)) {
                val rf = Board.squareOf(reply.substring(0, 2))
                if (!changed[rf] && !blind[rf]) continue
                val two = one.copy().also { it.play(reply) }
                if (fits(two, reply)) finals[two.placement()] = listOf(m, reply)
                if (finals.size > 1) return null
            }
        }
        val pair = finals.values.singleOrNull() ?: return null
        return if (pair.any { it.length > 4 }) null else pair
    }

    /**
     * Take moves found by [explainChange] as the new position now; the next
     * [accept] (the recognizer's reading of the clean capture) is judged from
     * the position before them, so a wrong guess is put right.
     */
    fun applyFast(moves: List<String>): Result.Position {
        TrackerLog.add("seen at once: ${moves.joinToString(" ")}")
        val prev = board ?: throw IllegalStateException("no game")
        val prevWhite = whiteToMove
        pendingFast = Fast(prev, prevWhite, lastMove)
        val b = prev.copy()
        for (m in moves) b.play(m)
        board = b
        whiteToMove = if (moves.size == 2) prevWhite else !prevWhite
        lastMove = moves.last()
        return Result.Position(b, whiteToMove, moves.singleOrNull(), prev, prevWhite)
    }

    /** Drop the moves of [applyFast] without a reading (the capture was thrown away). */
    fun abandonFast() {
        val f = pendingFast ?: return
        pendingFast = null
        board = f.prev; whiteToMove = f.prevWhite; lastMove = f.prevLast
    }

    /** The board's bounding box on screen (x0, y0, x1, y1), or null. */
    fun region(): IntArray? {
        val k = corners ?: return null
        return intArrayOf(k.minOf { it[0] }.toInt(), k.minOf { it[1] }.toInt(),
            k.maxOf { it[0] }.toInt() + 1, k.maxOf { it[1] }.toInt() + 1)
    }

    /** Screen positions of the change-detection points (x, y), once a board is known. */
    fun points(): Pair<IntArray, IntArray>? {
        val x = xs ?: return null
        val y = ys ?: return null
        return x to y
    }

    /**
     * Compare colours at [points] (`now`, -1 = not on screen) with the last
     * clean read. Points the `cover` says the overlay draws on are skipped.
     */
    fun compare(now: IntArray, cover: Cover?): Comparison {
        val x = xs; val y = ys; val r = ref
        if (x == null || y == null || r == null || now.size != r.size) return Comparison(64, 0)
        return compare(r, now, x, y, cover)
    }

    /** Squares that changed since the last clean read, in `img` (a crop whose top-left is screen (ox, oy)). */
    fun changedSquares(img: Img, ox: Int = 0, oy: Int = 0, cover: Cover? = null): Int {
        val x = xs ?: return 64
        val y = ys ?: return 64
        return compare(sample(img, x, y, ox, oy), cover).changed
    }

    /** Remember the board's colours from a clean (overlay-free) image. */
    private fun remember(img: Img, k: List<FloatArray>) {
        val (px, py) = samplePoints(k)
        xs = px; ys = py
        ref = sample(img, px, py, 0, 0)
    }

    /** A small change no move explains, read once: it must be read again the same way. */
    private var suspect: String? = null

    /**
     * Take a recognition. `sideOverride` forces the side to move (null = work
     * it out). `strict` (auto mode): a small change that neither one nor two
     * moves explain is most likely a misread, so it counts only when the
     * next read sees the same. Returns what changed.
     *
     * During a game the reading goes through PositionDecoder: the position
     * the game can reach (no move, a move, a move each, on a board that may
     * have been turned round) that the model finds most likely. Without
     * that, the plain reading, turned the way Orientation decides.
     */
    fun accept(img: Img, rec: Recognition, sideOverride: Boolean?, strict: Boolean = false): Result {
        val before = board?.placement()
        val r = acceptRead(img, rec, sideOverride, strict)
        TrackerLog.add(when (r) {
            is Result.Same -> "same" + (if (sideOverride != null) " (side set by hand)" else "")
            is Result.Invalid -> "held: ${r.reason}; read ${rec.placement} (${(rec.confidence * 100).toInt()}%)"
            is Result.Position -> when {
                r.move != null -> "move ${r.move}"
                r.back -> "back to an earlier position"
                before == null -> "first position"
                Board.findTwoMoves(r.previous ?: r.board, r.board, r.previousWhiteToMove) != null -> "two moves"
                else -> "jump (no move explains it)"
            } + ", ${if (r.whiteToMove) "White" else "Black"} to move" + (if (sideKnown) "" else " (guessed)") +
                lastSeen?.let { "; highlight ${it.move}" }.orEmpty()
        }, board?.fen(whiteToMove))
        return r
    }

    private fun acceptRead(img: Img, rec: Recognition, sideOverride: Boolean?, strict: Boolean): Result {
        abandonFast()   // judge the reading from the position before any move seen at once
        val prev = board
        val prevWhite = whiteToMove
        val decoded = if (prev != null) PositionDecoder.decode(prev, prevWhite, blackAtBottom, rec,
            if (strict) PositionDecoder.TAU_FOLLOWING else PositionDecoder.TAU_BY_HAND) else null
        val black = decoded?.blackAtBottom
            ?: Orientation.decide(rec.drawn, rec.blackBottomP, prior = if (prev != null) blackAtBottom else null)
        val read = decoded?.board ?: try {
            Board.fromPlacement(Orientation.whiteView(rec.drawn, black))
        } catch (e: IllegalArgumentException) {
            return Result.Invalid(e.message ?: "bad board")
        }
        // The site's last-move highlight says who moved last, when it is clear.
        val hl = LastMoveHighlight.find(img, rec.corners, read, black)
        lastSeen = hl
        // The same pieces as before: the same position (perhaps turned round), the same game;
        // unless the side to move was only guessed and the highlight shows the other.
        val sameBoard = prev != null && read.placement() == prev.placement()
        val hlCorrects = hl != null && !sideKnown && !hl.moverWhite != prevWhite
        if (sameBoard && sideOverride == null && !hlCorrects) {
            corners = rec.corners
            blackAtBottom = black
            remember(img, rec.corners)
            return Result.Same
        }

        // How the game got here: one move, or a move each (a quick reply).
        val moves: List<String>? = decoded?.moves ?: prev?.let { p ->
            Board.findMove(p, read)?.let { listOf(it) } ?: Board.findTwoMoves(p, read, prevWhite)?.toList()
        }
        // A move is played by the side to move. When that side is known and a
        // piece of the other side seems to move, it is going back where it came
        // from: a step back in the move list, or an undo. When the side to move
        // was only guessed, such a move is a move the guess got wrong, and it
        // sets the side right.
        val moverWhite = moves?.firstOrNull()?.let { prev?.get(Board.squareOf(it.substring(0, 2)))?.isUpperCase() }
        val played = moves != null && moves.isNotEmpty() && (moverWhite == prevWhite || !sideKnown)
        val playedMoves = if (played) moves else null
        // No move of the game explains the reading. It may be a position the
        // game went through before (a step back of one or more moves, a jump
        // to an earlier move): then it is taken as it was then, with what a
        // picture cannot show (the side to move, the castling rights).
        val at = if (played || sameBoard) -1 else history.indexOfLast { it.placement == read.placement() }
        val seen = if (at >= 0) history[at] else null
        val b0 = if (playedMoves != null && prev != null) {
            // The new position is the old one after its move: that keeps the
            // castling rights, the en passant square and the move count.
            prev.copy().also { x -> playedMoves.forEach { x.play(it) } }
        } else when {
            seen != null -> seen.board
            // A piece that went back where it came from, in a position the game
            // never went through: known from its pieces only, as after a jump.
            moves != null && moves.isNotEmpty() -> Board.fromPlacement(read.placement())
            else -> read
        }
        // A position not followed move by move: the highlighted move tells
        // what the picture alone cannot, whether a pawn just advanced two squares.
        val useHl = hl != null && playedMoves == null && seen == null
        val b = if (useHl) b0.afterSeenMove(hl!!.move) else b0
        val diff = if (prev == null) 64 else (0..63).count { prev[it] != b[it] }
        if (prev != null && playedMoves == null && strict && sideOverride == null && !sameBoard) {
            // Or a change no move explains read with some squares in doubt: a wrong position analysed is worse
            // than one read later, so it too must be read the same way twice. A sure reading (a new game set
            // up, another position opened) and a position the game went through before count at once.
            val unsure = seen == null && rec.confidence < UNSURE_READ
            if ((diff <= SMALL_CHANGE || unsure) && suspect != b.placement()) {
                suspect = b.placement()
                return Result.Invalid(if (diff <= SMALL_CHANGE) "no move explains it; reading again"
                    else "unsure of the reading (${(rec.confidence * 100).toInt()}%); reading again")
            }
        }
        suspect = null
        val move = playedMoves?.singleOrNull()
        // A piece that went back where it came from (not a move of the side to move).
        val backMove = if (moves != null && playedMoves == null) moverWhite else null
        val prefer: Boolean = sideOverride ?: seen?.takeIf { it.sideKnown }?.whiteToMove ?: when {
            move != null -> !b[Board.squareOf(move.substring(2, 4))].isUpperCase()
            playedMoves != null -> prevWhite   // a move and the reply that came with it
            // What the site shows: the colour of the piece on the highlighted square moved last.
            hl != null -> !hl.moverWhite
            b.placement() == START -> true   // a new game: White begins, whichever side is at the bottom
            // In a game whose side to move is known, that side is to move again.
            backMove != null && sideKnown -> backMove == true
            // A small change is the rest of a move or two; a big one is another game.
            prev != null && diff <= 8 -> Board.mover(prev, b)?.let { !it } ?: !black
            else -> !black
        }
        // A position no game can reach is a misread (a piece caught mid-move):
        // keep the last good read, and its colours, so the change is still
        // seen and read again.
        val white = Board.sideToMove(b, prefer)
            ?: return Result.Invalid(b.problem(prefer) ?: "not a legal position")
        corners = rec.corners
        blackAtBottom = black
        remember(img, rec.corners)
        if (sameBoard && white == prevWhite) return Result.Same

        // The game's memory: where it went, for a step back and for repetitions.
        val oldPly = ply
        val oldKnown = sideKnown
        // The side to move is known when the user or a move gave it, when this
        // is the start position, when a check forced it (the side preferred was
        // impossible), or when a piece going back showed it.
        val known = sideOverride != null || playedMoves != null || b.placement() == START ||
            b.problem(prefer) != null || (backMove != null && oldKnown) || (hl != null && white == !hl.moverWhite)
        if (sameBoard) {
            sideKnown = known
            repetitions = 1 + history.count { it.board.samePosition(b, white) }
        } else {
            if (seen != null) {
                history.subList(at, history.size).clear()   // the line goes on from here
                moveTimes.clear()   // the times of a line that is not played now say nothing
            } else if (b.placement() == START && playedMoves == null) {
                history.clear()   // another game, or the game seen from its start
                moveTimes.clear()
            } else if (prev != null) {
                history.add(Seen(prev, prev.placement(), prevWhite, lastMove, oldKnown, oldPly))
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }
            when {
                seen != null -> { ply = seen.ply; sideKnown = known || seen.sideKnown }
                playedMoves != null -> { ply = oldPly?.plus(playedMoves.size); sideKnown = true }
                b.placement() == START -> { ply = 0; sideKnown = true }
                else -> { ply = null; sideKnown = known }
            }
            repetitions = 1 + history.count { it.board.samePosition(b, white) }
            if (playedMoves != null) {
                moveTimes.addLast(clock())
                while (moveTimes.size > MOVE_TIMES) moveTimes.removeFirst()
            }
        }
        board = b
        whiteToMove = white
        lastMove = playedMoves?.lastOrNull() ?: seen?.lastMove ?: hl?.move
        return Result.Position(b, white, move, prev, prevWhite, back = seen != null)
    }

    /** `changed` squares look different; `blind` ones had too few uncovered points to tell. */
    data class Comparison(val changed: Int, val blind: Int)

    companion object {
        private const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

        /** Changed squares up to which an unexplained change is suspected to be a misread. */
        private const val SMALL_CHANGE = 4
        /** Below this certainty of its least sure square, an unexplained reading is read again before it counts. */
        const val UNSURE_READ = 0.8f

        /** Positions kept of the line followed (a game has a few hundred at most). */
        private const val MAX_HISTORY = 1024
        /** Moves timed to tell the pace of a game. */
        private const val MOVE_TIMES = 7

        /** Moves less than this apart of late make a fast game (bullet; the last seconds of one). */
        const val FAST_PACE_MS = 1500L


        /** Points per square side: 7 x 7 over the inner 70% of each square. */
        private const val SAMPLES = 7
        const val PER = SAMPLES * SAMPLES
        /** Visible points a square needs for a verdict. */
        private const val MIN_POINTS = 5
        /** Mean colour change (largest channel, 0-255) that counts as changed. */
        private const val THRESHOLD = 18

        /** Screen positions of the sample points for a board with these corners. */
        fun samplePoints(k: List<FloatArray>): Pair<IntArray, IntArray> {
            val xs = IntArray(64 * PER)
            val ys = IntArray(64 * PER)
            for (row in 0 until 8) for (col in 0 until 8) for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                val u = (col + 0.15f + 0.7f * (sx + 0.5f) / SAMPLES) / 8f
                val v = (row + 0.15f + 0.7f * (sy + 0.5f) / SAMPLES) / 8f
                val i = (row * 8 + col) * PER + sy * SAMPLES + sx
                xs[i] = ((1 - u) * (1 - v) * k[0][0] + u * (1 - v) * k[1][0] + u * v * k[2][0] + (1 - u) * v * k[3][0]).toInt()
                ys[i] = ((1 - u) * (1 - v) * k[0][1] + u * (1 - v) * k[1][1] + u * v * k[2][1] + (1 - u) * v * k[3][1]).toInt()
            }
            return xs to ys
        }

        /** Colours at the given screen points in `img` (top-left at screen (ox, oy)); -1 off the image. */
        fun sample(img: Img, xs: IntArray, ys: IntArray, ox: Int, oy: Int): IntArray = IntArray(xs.size) { i ->
            val lx = xs[i] - ox
            val ly = ys[i] - oy
            if (lx in 0 until img.w && ly in 0 until img.h) img[lx, ly] else -1
        }

        /**
         * Squares whose colours differ between two samples of the same
         * points (largest channel change above THRESHOLD on average, over at
         * least MIN_POINTS uncovered points).
         */
        fun compare(a: IntArray, b: IntArray, xs: IntArray, ys: IntArray, cover: Cover?): Comparison {
            val st = squareStates(a, b, xs, ys, cover)
            return Comparison(st.count { it == CHANGED }, st.count { it == BLIND })
        }

        const val SAME = 0
        const val CHANGED = 1
        const val BLIND = 2

        /** Per drawn square (row-major from the top-left): SAME, CHANGED or BLIND (too few uncovered points). */
        fun squareStates(a: IntArray, b: IntArray, xs: IntArray, ys: IntArray, cover: Cover?): IntArray {
            val out = IntArray(64)
            for (sq in 0 until 64) {
                var n = 0
                var total = 0
                for (j in 0 until PER) {
                    val i = sq * PER + j
                    if (a[i] < 0 || b[i] < 0) continue
                    if (cover != null && cover.covered(xs[i], ys[i])) continue
                    total += colourDistance(a[i], b[i])
                    n++
                }
                out[sq] = if (n < MIN_POINTS) BLIND else if (total > THRESHOLD * n) CHANGED else SAME
            }
            return out
        }

        /** More changed squares than this is not a move or two (a dialog, a scroll). */
        private const val MAX_FAST_CHANGED = 12

        private fun colourDistance(a: Int, b: Int): Int = max(
            abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
            max(abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)), abs((a and 0xff) - (b and 0xff))),
        )
    }
}
