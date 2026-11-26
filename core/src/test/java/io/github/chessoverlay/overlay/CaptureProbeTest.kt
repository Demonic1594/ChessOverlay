package io.github.chessoverlay.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProbeTest {
    @Test
    fun theSquareInAFrameMeansTheWholeScreenIsShared() {
        val p = CaptureProbe()
        p.start(1000)
        assertNull("the square is not up yet", p.offer(1005, 990) { true })
        p.drawn = 1010
        assertNull("a frame from before it was drawn says nothing", p.offer(1020, 1015) { true })
        assertEquals(CaptureProbe.Answer.IN_CAPTURE, p.offer(1040, 1030) { true })
        assertFalse(p.running)
    }

    @Test
    fun aFrameWithoutItWellAfterMeansOneAppIsShared() {
        val p = CaptureProbe()
        p.start(1000); p.drawn = 1000
        // Early frames may have been composed before the square: wait for one from well after.
        assertNull(p.offer(1040, 1030) { false })
        assertEquals(CaptureProbe.Answer.NOT_IN_CAPTURE, p.offer(3000, 2500) { false })
    }

    @Test
    fun noFrameIsNotKnownAndNothingWaitedForIt() {
        val p = CaptureProbe(waitMs = 6000, tries = 2)
        p.start(1000); p.drawn = 1000
        assertNull(p.offer(4000, 900) { error("an old frame is not looked at") })
        assertEquals(CaptureProbe.Answer.NOT_KNOWN, p.offer(7000, 900) { false })
        assertTrue(p.mayStart)
        p.start(8000)
        assertEquals(CaptureProbe.Answer.NOT_KNOWN, p.offer(14_000, 900) { false })
        assertFalse("tried enough", p.mayStart)
        p.reset()
        assertTrue("a new capture starts over", p.mayStart)
    }

    @Test
    fun theSquareGoesOffTheBoardAndAwayFromTheBars() {
        // A portrait phone, the board across the middle.
        val r = CaptureProbe.place(1440, 3120, intArrayOf(0, 929, 1440, 2369), null, 12)
        assertNotNull(r); r!!
        assertTrue("off the board", r[3] < 929 || r[1] > 2369)
        assertTrue("away from the bars", r[1] > 3120 * 8 / 100 && r[3] < 3120 * 92 / 100)
        // Not under the card either.
        val card = intArrayOf(0, 300, 1440, 900)
        val c = CaptureProbe.place(1440, 3120, intArrayOf(0, 929, 1440, 2369), card, 12)
        assertNotNull(c)
        assertTrue("below the board, the card being above it", c!![1] > 2369)
        // A board over the whole screen: no place.
        assertNull(CaptureProbe.place(1080, 1080, intArrayOf(0, 0, 1080, 1080), null, 12))
    }
}
