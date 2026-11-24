package io.github.chessoverlay

import io.github.chessoverlay.ui.breakable
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test fun longOptionNamesBreakBetweenWords() {
        assertEquals("Smother​Win​Time​Percent", breakable("SmotherWinTimePercent"))
        assertEquals("UCI_​Limit​Strength", breakable("UCI_LimitStrength"))
        assertEquals("Hash", breakable("Hash"))
    }
}
