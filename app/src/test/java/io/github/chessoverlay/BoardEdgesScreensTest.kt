package io.github.chessoverlay

import io.github.chessoverlay.vision.BoardEdges
import io.github.chessoverlay.vision.Img
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The sharpening of a located board (BoardEdges) on two phone screenshots
 * of the test images (src/test/resources/vision): where the board really
 * is was read off the pictures (its first and its last row and column of
 * board colour), and what chessimg2pos's locator gave for them is in
 * expected.json. The locator's corners were up to 4.7 px off; the sharpened
 * ones are within 1.5.
 */
class BoardEdgesScreensTest {
    private fun load(name: String) = Img.fromRgb(GZIPInputStream(javaClass.getResourceAsStream("/vision/$name.rgb.gz")!!).readBytes())

    private fun corners(x0: Float, y0: Float, x1: Float, y1: Float) = listOf(
        floatArrayOf(x0, y0), floatArrayOf(x1, y0), floatArrayOf(x1, y1), floatArrayOf(x0, y1))

    @Test fun theEdgesOfTheBoardOnTwoPhoneScreens() {
        val cases = listOf(
            // The board lies across the width of the screen with 20 px of page on either side: 1,040 px wide.
            Triple("screen1", listOf(23.579f, 643.568f, 1059.813f, 641.225f, 1060.943f, 1681.226f, 21.970f, 1681.312f),
                floatArrayOf(20f, 640f, 1060f, 1680f)),
            // Turned for Black, and a strip of another colour just above the board.
            Triple("screen2_flipped", listOf(17.214f, 518.988f, 1064.153f, 519.457f, 1063.118f, 1561.947f, 20.159f, 1564.689f),
                floatArrayOf(20f, 520f, 1060f, 1560f)),
        )
        for ((name, located, truth) in cases) {
            val k = listOf(
                floatArrayOf(located[0], located[1]), floatArrayOf(located[2], located[3]),
                floatArrayOf(located[4], located[5]), floatArrayOf(located[6], located[7]))
            val r = BoardEdges.refine(load(name), k)
            val got = floatArrayOf(r[0][0], r[0][1], r[2][0], r[2][1])
            for (i in 0..3) assertTrue("$name: edge $i at ${got[i]}, the board's is at ${truth[i]}", abs(got[i] - truth[i]) <= 2f)
            assertTrue("$name is square", abs((got[2] - got[0]) - (got[3] - got[1])) <= 1f)
        }
    }
}
