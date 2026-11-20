package io.github.chessoverlay

import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The Kotlin port against chessimg2pos (Python) on the same images; the
 * expected values in src/test/resources/vision were produced with
 * chessimg2pos 0.2.0 and Pillow.
 */
class VisionTest {
    /** Test images: gzipped "W H\n" + raw RGB bytes (made from PNGs with Pillow). */
    private fun load(name: String): Img {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/vision/${name.removeSuffix(".png")}.rgb.gz")!!).readBytes()
        val nl = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, nl).split(' ').map { it.toInt() }
        val img = Img(w, h)
        for (i in 0 until w * h) {
            val o = nl + 1 + 3 * i
            img.px[i] = ((bytes[o].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
        }
        return img
    }

    @Test fun resizeMatchesPillow() {
        val src = load("sample1.png")
        for ((w, h) in listOf(256 to 256, 320 to 320, 97 to 131, 700 to 500)) {
            val mine = src.resize(w, h)
            val ref = load("resize_sample1_${w}x$h.png")
            var maxDiff = 0
            var differing = 0
            for (i in mine.px.indices) {
                var d = 0
                for (s in intArrayOf(16, 8, 0)) d = maxOf(d, abs(((mine.px[i] shr s) and 0xff) - ((ref.px[i] shr s) and 0xff)))
                if (d > 0) differing++
                maxDiff = maxOf(maxDiff, d)
            }
            assertTrue("${w}x$h: max channel difference $maxDiff ($differing pixels)", maxDiff <= 1)
        }
    }

    /** Needs MODELS (env) = a folder with board.onnx and locator.onnx. */
    @Test fun recognitionMatchesPython() {
        val dir = System.getenv("MODELS")
        assumeTrue("set MODELS to run this test", dir != null && File(dir, "board.onnx").exists())
        val expected = JSONObject(javaClass.getResourceAsStream("/vision/expected.json")!!.bufferedReader().readText())
        Recognizer(File(dir, "board.onnx"), File(dir, "locator.onnx"), threads = 4).use { rec ->
            for (name in expected.keys()) {
                val e = expected.getJSONObject(name)
                val r = rec.recognise(load("$name.png"))
                val fen = if (r.blackAtBottom) "black" else "white"
                println("$name: ${r.placement} $fen conf=${"%.3f".format(r.confidence)} corners=${r.corners.map { c -> c.map { it.toInt() } }}")
                assertEquals(name, e.getString("fen"), r.placement)
                assertEquals(name, e.getString("orientation"), fen)
                assertEquals(name, e.getDouble("confidence"), r.confidence.toDouble(), 0.02)
                val ec = e.getJSONArray("board_corners")
                for (i in 0 until 4) for (j in 0 until 2) {
                    val d = abs(ec.getJSONArray(i).getDouble(j) - r.corners[i][j])
                    assertTrue("$name corner $i: off by $d px", d <= 2.0)
                }
            }
        }
    }
}
