package io.github.chessoverlay

import io.github.chessoverlay.vision.ReadStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadStatsTest {
    @Test fun oneLineSaysHowTheGameWent() {
        val s = ReadStats()
        repeat(10) { s.position() }
        repeat(8) { s.seenAtOnce() }
        repeat(7) { s.confirmed() }
        s.corrected()
        for (ms in listOf(50L, 60, 55, 70, 65, 200, 58, 62, 61, 59)) s.read(ms)
        s.misread()
        s.sideFromHighlight()
        assertEquals("10 positions analysed; 8 moves seen at once (7 confirmed, 1 corrected); " +
            "10 reads, median 61 ms, 90% within 200 ms, slowest 200 ms; side to move from the highlight 1 times; " +
            "1 misread, 0 without a board", s.summary())
        assertEquals("0 positions analysed", ReadStats().summary())
    }
}
