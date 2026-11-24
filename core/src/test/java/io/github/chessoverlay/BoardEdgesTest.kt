package io.github.chessoverlay

import io.github.chessoverlay.vision.BoardEdges
import io.github.chessoverlay.vision.Img
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The board's edges where the picture shows them, when the locator's corners are a little off. */
class BoardEdgesTest {
    private val page = 0x262421
    private val light = 0xF0D9B5
    private val dark = 0xB58863

    private fun picture(w: Int = 400, h: Int = 400, x0: Int = 60, y0: Int = 80, side: Int = 300, page: Int = this.page): Img {
        val img = Img(w, h)
        for (i in img.px.indices) img.px[i] = page
        for (y in y0 until y0 + side) for (x in x0 until x0 + side) {
            img.px[y * w + x] = if (((y - y0) * 8 / side + (x - x0) * 8 / side) % 2 == 0) light else dark
        }
        return img
    }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) = listOf(
        floatArrayOf(x0.toFloat(), y0.toFloat()), floatArrayOf(x1.toFloat(), y0.toFloat()),
        floatArrayOf(x1.toFloat(), y1.toFloat()), floatArrayOf(x0.toFloat(), y1.toFloat()))

    private fun assertBox(img: Img, k: List<FloatArray>, x0: Float, y0: Float, x1: Float, y1: Float, within: Float = 1f) {
        val r = BoardEdges.refine(img, k)
        val got = floatArrayOf(r[0][0], r[0][1], r[2][0], r[2][1])
        val want = floatArrayOf(x0, y0, x1, y1)
        for (i in 0..3) assertTrue("corner $i: ${got[i]} not ${want[i]} (from ${k[0][0]}, ${k[0][1]}, ${k[2][0]}, ${k[2][1]})",
            abs(got[i] - want[i]) <= within)
    }

    @Test fun theEdgesOfABoardTheLocatorPutABitOff() {
        val img = picture()
        // Off by up to 12 px on a 300 px board (4 %), each side its own way.
        assertBox(img, box(50, 88, 372, 371), 60f, 80f, 360f, 380f)
        assertBox(img, box(72, 70, 350, 370), 60f, 80f, 360f, 380f)
        // A larger board: the same in proportion.
        val big = picture(1200, 1900, 100, 350, 1000)
        assertBox(big, box(60, 400, 1120, 1320), 100f, 350f, 1100f, 1350f, 1.5f)
    }

    @Test fun exactCornersStay() {
        assertBox(picture(), box(60, 80, 360, 380), 60f, 80f, 360f, 380f)
        // A page the colour of the light squares: the dark ones still show where the board is.
        assertBox(picture(page = 0xEEEBE4), box(55, 85, 365, 375), 60f, 80f, 360f, 380f)
    }

    @Test fun whatThePictureDoesNotSayIsLeftAlone() {
        // The board at the left edge of the picture: no page on that side to compare with, so the edge lies
        // a side's length (the top and the bottom were found) from the right one.
        val img = picture(x0 = 0)
        assertBox(img, box(4, 90, 304, 370), 0f, 80f, 300f, 380f)
        // Only 19 px of page below the board: the range is cut to leave room for the band, and is still enough.
        assertBox(picture(x0 = 0), box(6, 80, 306, 380), 0f, 80f, 300f, 380f)
        // Something next to the board over a third of its right side does not move that edge.
        val next = picture()
        for (y in 80 until 180) for (x in 375 until 395) next.px[y * 400 + x] = 0xFFFFFF
        assertBox(next, box(60, 80, 350, 380), 60f, 80f, 360f, 380f, 1.5f)
        // Not a board at all (one colour): nothing is found, the corners stay.
        val blank = Img(400, 400).also { im -> im.px.fill(0x262421) }
        val k = box(60, 80, 360, 380)
        assertTrue(BoardEdges.refine(blank, k) === k)
    }

    @Test fun aTiltedBoardKeepsTheLocatorsCorners() {
        val img = picture()
        val tilted = listOf(floatArrayOf(66f, 76f), floatArrayOf(358f, 92f), floatArrayOf(352f, 384f), floatArrayOf(62f, 370f))
        assertTrue(BoardEdges.refine(img, tilted) === tilted)
        // The box of that tilted board is refined as what it is: a box.
        assertBox(img, box(62, 76, 358, 374), 60f, 80f, 360f, 380f)
    }

    @Test fun aSideWithLittlePageAroundIt() {
        val img = picture()   // 20 px of page below the board
        // Just room enough between the located edge and the picture's end: the band of page moves nearer.
        assertBox(img, box(60, 80, 360, 372), 60f, 80f, 360f, 380f)
        // Only 9 px of page below (the located edge is nearly 3 % of a side inside the true one, more than
        // the cut range covers): the left and the right are found, and the board is square.
        assertBox(img, box(55, 86, 366, 390), 60f, 80f, 360f, 380f)
    }

    @Test fun whatTheLocatorMissedOfTheBoard() {
        // The locator missed a column: its box is not square, and the edges are looked for further out.
        assertBox(picture(), box(97, 80, 360, 380), 60f, 80f, 360f, 380f)
        // Two columns and a rank: the band of page beyond the left and the bottom would lie on the board, so the
        // page is what the right and the top show; the left edge is too far off to be found, and lies a side's
        // length from the right one.
        assertBox(picture(), box(120, 80, 360, 343), 60f, 80f, 360f, 380f)
    }

    @Test fun aBoardThatSpansTheWidthOfThePicture() {
        // As on a phone: 10 px of page on either side of the board, so neither vertical edge has page beyond it.
        val img = picture(w = 320, h = 440, x0 = 10, y0 = 70, side = 300)
        assertBox(img, box(4, 64, 316, 382), 10f, 70f, 310f, 370f)
        assertBox(img, box(12, 75, 308, 366), 10f, 70f, 310f, 370f)
    }
}
