package io.github.chessoverlay

import io.github.chessoverlay.overlay.Look
import io.github.chessoverlay.overlay.ReadPacing
import io.github.chessoverlay.overlay.ScreenMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingTest {
    @Test fun missesPauseLongerAndAGoodReadEndsThePause() {
        val p = ReadPacing()
        assertTrue(p.mayRead(0))
        p.done(1000, Look.INVALID, auto = true)
        assertFalse(p.mayRead(1400)); assertTrue(p.mayRead(1500))
        p.done(1500, Look.NO_BOARD, auto = true)
        assertFalse(p.mayRead(2400)); assertTrue(p.mayRead(2500))
        repeat(5) { p.done(3000, Look.INVALID, auto = true) }
        assertEquals("at most 5", 5, p.misses)
        assertFalse(p.mayRead(3000 + 7999)); assertTrue(p.mayRead(3000 + 8000))
        p.done(4000, Look.NEW, auto = true)
        assertTrue(p.mayRead(4000)); assertEquals(0, p.misses)
        p.done(5000, Look.INVALID, auto = false)
        assertTrue("a read by hand starts no pause", p.mayRead(5000))
        assertFalse(p.mayFind(5500)); assertTrue(p.mayFind(6000))
    }

    @Test fun theScreenIsStillWhenFewPointsChange() {
        val m = ScreenMotion(stillMs = 300)
        val (xs, _) = m.points(1080, 2340)
        assertEquals(ScreenMotion.GRID_W * ScreenMotion.GRID_H, xs.size)
        val px = IntArray(xs.size) { 0x202020 }
        assertFalse(m.offer(0, px))
        assertFalse(m.offer(200, px.copyOf()))
        assertTrue(m.offer(300, px.copyOf()))
        // A few points (a clock ticking) do not count as moving; a tenth of the screen does.
        val tick = px.copyOf().also { for (i in 0 until 5) it[i] = 0xFFFFFF }
        assertTrue(m.offer(400, tick))
        val anim = px.copyOf().also { for (i in 0 until it.size / 10) it[i] = 0xFFFFFF }
        assertFalse(m.offer(500, anim))
        assertFalse(m.offer(700, anim.copyOf()))
        assertTrue(m.offer(800, anim.copyOf()))
        // Another screen size starts again.
        m.points(2340, 1080)
        assertFalse(m.offer(900, px))
    }
}
