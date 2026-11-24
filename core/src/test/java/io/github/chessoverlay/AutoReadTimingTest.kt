package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.overlay.BoardGeom
import io.github.chessoverlay.overlay.Shape
import io.github.chessoverlay.overlay.ShapeCover
import io.github.chessoverlay.overlay.arrowOutline
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.ChangeWatcher
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto mode against a simulated screen: pieces glide over several frames
 * (as on chess sites), the overlay draws arrows, and the screen is polled
 * every 30 ms (as the overlay does). The board must be read only once pieces have stopped, soon
 * after, and never in flight.
 */
class AutoReadTimingTest {
    private val sq = 40
    private val start = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    private val corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f))

    /** A move animation: `move` glides from `t0` for `ms` milliseconds. */
    private class Glide(val before: Board, val move: String, val t0: Long, val ms: Long)

    private fun colour(piece: Char, row: Int, col: Int) = when {
        piece == '.' -> if ((row + col) % 2 == 0) 0xEED8B5 else 0xB58863
        piece.isUpperCase() -> 0xFFFFFF
        else -> 0x101010 + ("pnbrqk".indexOf(piece) * 0x0A0A0A)
    }

    /** The screen at time t, given the glides in order. */
    private fun screen(t: Long, glides: List<Glide>, arrow: List<Shape>? = null): Img {
        var board = glides.first().before
        var flying: Pair<Char, FloatArray>? = null
        for (g in glides) {
            if (t < g.t0) break
            val from = Board.squareOf(g.move.substring(0, 2)); val to = Board.squareOf(g.move.substring(2, 4))
            val after = g.before.copy().also { it.play(g.move) }
            if (t >= g.t0 + g.ms) { board = after; continue }
            // In flight: neither square shows the piece; it is drawn in between.
            val f = (t - g.t0).toFloat() / g.ms
            val x = (from % 8) + ((to % 8) - (from % 8)) * f
            val y = (7 - from / 8) + ((7 - to / 8) - (7 - from / 8)) * f
            board = g.before.copy().also { it.play(g.move) }
            flying = g.before[from] to floatArrayOf(x, y)
            board = Board.fromPlacement(board.placement().let { p ->
                // Hide the destination's piece while it is still flying there.
                val b = Board.fromPlacement(p); val chars = CharArray(64) { b[it] }; chars[to] = g.before[to]
                (7 downTo 0).joinToString("/") { r -> (0..7).joinToString("") { c -> chars[r * 8 + c].toString() } }
                    .replace(Regex("\\.+")) { it.value.length.toString() }
            })
            break
        }
        val img = Img(8 * sq + 20, 8 * sq + 20)
        for (row in 0 until 8) for (col in 0 until 8) for (y in 0 until sq) for (x in 0 until sq) {
            val p = board[(7 - row) * 8 + col]
            val inPiece = p != '.' && x in sq / 4 until 3 * sq / 4 && y in sq / 4 until 3 * sq / 4
            img.px[(10 + row * sq + y) * img.w + 10 + col * sq + x] = colour(if (inPiece) p else '.', row, col)
        }
        flying?.let { (p, at) ->
            val x0 = (10 + at[0] * sq + sq / 4).toInt(); val y0 = (10 + at[1] * sq + sq / 4).toInt()
            for (y in y0 until y0 + sq / 2) for (x in x0 until x0 + sq / 2) img.px[y * img.w + x] = colour(p, 0, 0)
        }
        if (arrow != null) {
            val c = ShapeCover(arrow, 0f)
            for (y in 0 until img.h) for (x in 0 until img.w) if (c.covered(x, y)) img.px[y * img.w + x] = 0x1F7AE6
        }
        return img
    }

    private fun rec(b: Board) = Recognition(b.placement(), false, 0.99f, 0.99f, corners, 0)

    /** Polls every 30 ms from `from` to `to`; returns the times READ was decided. */
    private fun poll(w: ChangeWatcher, t: BoardTracker, from: Long, to: Long, glides: List<Glide>,
                     arrow: List<Shape>? = null, cover: BoardTracker.Cover? = null, step: Long = 30): List<Long> {
        val reads = ArrayList<Long>()
        val (xs, ys) = t.points()!!
        var now = from
        while (now <= to) {
            val px = BoardTracker.sample(screen(now, glides, arrow), xs, ys, 0, 0)
            if (w.offer(now, px, t, cover, 0) == ChangeWatcher.Decision.READ) reads += now
            now += step
        }
        return reads
    }

    /**
     * In a fast game the overlay checks the board every 20 ms and reads
     * once it has been still for 55 ms. A move must still never be read in
     * flight, and lands sooner in the analysis.
     */
    @Test fun inAFastGameTheReadIsSoonerAndNeverInFlight() {
        val t = BoardTracker()
        val w = ChangeWatcher(stillMs = 55)
        val e4 = start.copy().also { it.play("e2e4") }
        val both = e4.copy().also { it.play("e7e5") }
        // 1. e4 glides from 100 to 300 ms; 1... e5 from 900 to 1000 (a short animation, as sites draw quick ones).
        val glides = listOf(Glide(start, "e2e4", 100, 200), Glide(e4, "e7e5", 900, 100))
        t.accept(screen(0, glides), rec(start), null, strict = true)
        val reads = poll(w, t, 0, 1400, glides, step = 20)
        val first = reads.first()
        assertTrue("read at $first: before the piece stopped (300 ms)", first >= 300)
        assertTrue("read at $first: later than 300 + 55 + 20", first <= 375)
        assertTrue("a read while the piece was in flight (900-1000 ms): $reads", reads.none { it in 901..1054 })
        assertTrue("no read once the second move landed", reads.any { it in 1055..1075 })
        // The capture taken at the first read shows the board as it stopped, and one in flight does not.
        assertFalse(w.captureMatches(screen(950, glides), t))
    }

    @Test fun readsSoonAfterThePieceStopsAndNeverInFlight() {
        val t = BoardTracker()
        val w = ChangeWatcher()
        val glides = listOf(Glide(start, "e2e4", 100, 200))
        t.accept(screen(0, glides), rec(start), null, strict = true)
        val reads = poll(w, t, 0, 1000, glides)
        assertTrue("no read", reads.isNotEmpty())
        val first = reads.first()
        assertTrue("read at $first, before the piece stopped at 300 ms + 80", first >= 380)
        assertTrue("read at $first: too slow", first <= 300 + 80 + 2 * 30)
        // The changed squares already show the move, before any recognizer.
        assertEquals(listOf("e2e4"), t.explainChange(w.still!!, null))
        // A capture taken while the piece was still moving does not match the still board.
        assertFalse(w.captureMatches(screen(250, glides), t))
        assertTrue(w.captureMatches(screen(first + 30, glides), t))
        // After the read, nothing more happens.
        val after = start.copy().also { it.play("e2e4") }
        assertTrue(t.accept(screen(first + 30, glides), rec(after), null, strict = true) is BoardTracker.Result.Position)
        w.reset()
        assertEquals(emptyList<Long>(), poll(w, t, first + 60, 2000, glides))
    }

    @Test fun ownArrowsDoNotTriggerReads() {
        val t = BoardTracker()
        val w = ChangeWatcher()
        val glides = listOf(Glide(start, "e2e4", 10_000, 200))   // nothing moves in this window
        t.accept(screen(0, glides), rec(start), null, strict = true)
        val g = BoardGeom(corners, false)
        val arrow = listOf(Shape.Poly(arrowOutline(listOf(g.centre(Board.squareOf("g1")), g.centre(Board.squareOf("f3"))),
            g.sq * 0.17f, g.sq * 0.42f, g.sq * 0.27f), 0xE61F7AE6.toInt()))
        val cover = ShapeCover(arrow, 2f)
        assertEquals(emptyList<Long>(), poll(w, t, 0, 1500, glides, arrow, cover))
    }

    @Test fun theArrowedMoveIsSeenThroughTheArrow() {
        // The overlay suggests Nf3 and it is played: its squares are partly under the arrow.
        val t = BoardTracker()
        val w = ChangeWatcher()
        val p = start.copy().also { it.play("e2e4"); it.play("e7e5") }
        val glides = listOf(Glide(p, "g1f3", 100, 200))
        t.accept(screen(0, glides), rec(p), null, strict = true)
        val g = BoardGeom(corners, false)
        val arrow = listOf(Shape.Poly(arrowOutline(listOf(g.centre(Board.squareOf("g1")), g.centre(Board.squareOf("f3"))),
            g.sq * 0.17f, g.sq * 0.42f, g.sq * 0.27f), 0xE61F7AE6.toInt()))
        val cover = ShapeCover(arrow, 2f)
        val reads = poll(w, t, 0, 1000, glides, arrow, cover)
        assertTrue("read after the knight landed at 300 ms: $reads", reads.isNotEmpty() && reads.first() >= 380)
        assertEquals(listOf("g1f3"), t.explainChange(w.still!!, w.stillCover))
    }

    @Test fun aQuickReplyIsWaitedForAndBothMovesAreFound() {
        val t = BoardTracker()
        val w = ChangeWatcher()
        val afterE4 = start.copy().also { it.play("e2e4") }
        // Black replies 50 ms after White's piece lands: shorter than the stillness wait.
        val glides = listOf(Glide(start, "e2e4", 100, 200), Glide(afterE4, "c7c5", 350, 200))
        t.accept(screen(0, glides), rec(start), null, strict = true)
        val reads = poll(w, t, 0, 1500, glides)
        assertTrue("read at ${reads.firstOrNull()} before the reply landed at 550 ms", reads.first() >= 550 + 80)
        assertEquals("both moves seen at once", listOf("e2e4", "c7c5"), t.explainChange(w.still!!, null))
        val both = afterE4.copy().also { it.play("c7c5") }
        val r = t.accept(screen(reads.first() + 30, glides), rec(both), null, strict = true) as BoardTracker.Result.Position
        assertEquals("White to move again after both moves", true, r.whiteToMove)
    }
}

/** Colours that change: the board theme mid-game, and players swapping sides. */
class ColourChangeTest {
    private val sq = 40
    private val corners = listOf(floatArrayOf(10f, 10f), floatArrayOf(330f, 10f), floatArrayOf(330f, 330f), floatArrayOf(10f, 330f))
    private val start = Board.fromPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")

    private fun render(b: Board, light: Int, dark: Int, black: Boolean = false): Img {
        val img = Img(8 * sq + 20, 8 * sq + 20)
        for (row in 0 until 8) for (col in 0 until 8) for (y in 0 until sq) for (x in 0 until sq) {
            val square = if (black) row * 8 + (7 - col) else (7 - row) * 8 + col
            val p = b[square]
            val inPiece = p != '.' && x in sq / 4 until 3 * sq / 4 && y in sq / 4 until 3 * sq / 4
            img.px[(10 + row * sq + y) * img.w + 10 + col * sq + x] = when {
                inPiece && p.isUpperCase() -> 0xFFFFFF
                inPiece -> 0x101010 + ("pnbrqk".indexOf(p) * 0x0A0A0A)
                (row + col) % 2 == 0 -> light
                else -> dark
            }
        }
        return img
    }

    private fun rec(b: Board, black: Boolean) = Recognition(b.placement(), black, 0.99f, 0.99f, corners, 0)

    @Test fun aNewBoardThemeIsReadOnceAndTheGameGoesOn() {
        val t = BoardTracker()
        val w = ChangeWatcher()
        val p = start.copy().also { it.play("e2e4"); it.play("e7e5") }
        t.accept(render(start, 0xEED8B5, 0xB58863), rec(start, false), null, strict = true)
        t.accept(render(p, 0xEED8B5, 0xB58863), rec(p, false), null, strict = true)
        // The user picks a blue theme: every square changes colour, no piece moves.
        val blue = render(p, 0xEAE9D2, 0x4B7399)
        val (xs, ys) = t.points()!!
        var reads = 0
        for (now in 0L..600L step 60) if (w.offer(now, BoardTracker.sample(blue, xs, ys, 0, 0), t, null, 0) == ChangeWatcher.Decision.READ) {
            reads++
            assertEquals(BoardTracker.Result.Same, t.accept(blue, rec(p, false), null, strict = true))
            w.reset()
        }
        assertEquals("one read for the new colours, then quiet", 1, reads)
        assertTrue(t.whiteToMove)
        // The game continues in the new colours.
        val q = p.copy().also { it.play("g1f3") }
        assertEquals("g1f3", (t.accept(render(q, 0xEAE9D2, 0x4B7399), rec(q, false), null, strict = true)
            as BoardTracker.Result.Position).move)
    }

    @Test fun aRematchWithSidesSwapped() {
        val t = BoardTracker()
        val end = Board.fromPlacement("6k1/5ppp/8/8/8/8/5PPP/3R2K1")   // the last game ended
        t.accept(render(end, 0xEED8B5, 0xB58863), rec(end, false), null, strict = true)
        // New game: the user now plays Black, so Black is at the bottom; White still moves first.
        val r = t.accept(render(start, 0xEED8B5, 0xB58863, black = true), rec(start, true), null, strict = true)
        // A change this big is a new game, not a misread: accepted on the first read.
        assertTrue(r is BoardTracker.Result.Position)
        assertTrue(t.blackAtBottom)
        assertTrue(t.whiteToMove)
    }
}
