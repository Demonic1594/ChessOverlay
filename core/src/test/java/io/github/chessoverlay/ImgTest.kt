package io.github.chessoverlay

import io.github.chessoverlay.vision.Img
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

/** The fused crop and resize gives the same pixels as cropping, padding and resizing. */
class ImgTest {
    private fun random(w: Int, h: Int, seed: Int): Img {
        val r = Random(seed)
        return Img(w, h, IntArray(w * h) { r.nextInt(0x1000000) })
    }

    @Test fun aScreenPaddedToASquare() {
        for ((w, h) in listOf(144 to 312, 312 to 144, 97 to 97, 400 to 211)) {
            val img = random(w, h, w * h)
            val side = maxOf(w, h)
            assertArrayEquals("$w x $h", img.padSquare(side).resize(32, 32).px,
                img.resizeRegion(0, 0, side, side, 32, 32).px)
        }
    }

    @Test fun aCropResized() {
        val img = random(300, 500, 7)
        // Inside, over the edges (black outside), the same size as the output (no resampling one way).
        for (r in listOf(intArrayOf(20, 40, 260, 280), intArrayOf(-15, -30, 250, 240), intArrayOf(100, 300, 330, 540),
                         intArrayOf(10, 10, 74, 138))) {
            assertArrayEquals(r.joinToString(), img.crop(r[0], r[1], r[2], r[3]).resize(64, 64).px,
                img.resizeRegion(r[0], r[1], r[2] - r[0], r[3] - r[1], 64, 64).px)
        }
        assertArrayEquals(img.crop(10, 10, 74, 138).resize(64, 50).px, img.resizeRegion(10, 10, 64, 128, 64, 50).px)
    }

    @Test fun aCropPaddedToASquare() {
        val img = random(360, 780, 3)
        val (x0, y0, x1, y1) = listOf(30, 200, 330, 560)
        val side = maxOf(x1 - x0, y1 - y0)
        assertArrayEquals(img.crop(x0, y0, x1, y1).padSquare(side).resize(40, 40).px,
            img.resizeRegion(x0, y0, side, side, 40, 40, x1 - x0, y1 - y0).px)
    }
}
