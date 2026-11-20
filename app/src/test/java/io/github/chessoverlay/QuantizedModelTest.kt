package io.github.chessoverlay

import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The int8 board model the app bundles (tools/quantize_models.py) reads
 * every test image exactly as the original does. Runs when MODELS (the
 * originals) and QMODELS (the bundled ones) are set, as in CI.
 */
class QuantizedModelTest {
    private fun load(name: String): Img {
        val bytes = GZIPInputStream(javaClass.getResourceAsStream("/vision/$name.rgb.gz")!!).readBytes()
        val nl = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, nl).split(' ').map { it.toInt() }
        val img = Img(w, h)
        for (i in 0 until w * h) {
            val o = nl + 1 + 3 * i
            img.px[i] = ((bytes[o].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
        }
        return img
    }

    @Test fun sameReadingsAsTheOriginal() {
        val orig = System.getenv("MODELS")?.let(::File)
        val quant = System.getenv("QMODELS")?.let(::File)
        assumeTrue(orig != null && quant != null && File(orig, "board.onnx").exists() && File(quant, "board.onnx").exists())
        Recognizer(File(orig, "board.onnx"), File(orig, "locator.onnx")).use { a ->
            Recognizer(File(quant, "board.onnx"), File(quant, "locator.onnx")).use { b ->
                for (name in listOf("sample1", "sample2", "sample3", "screen1", "screen2_flipped")) {
                    val img = load(name)
                    val ra = a.recognise(img)
                    val rb = b.recognise(img, knownCorners = ra.corners)
                    assertEquals(name, ra.placement, rb.placement)
                    assertEquals(name, ra.blackAtBottom, rb.blackAtBottom)
                    assertEquals(name, ra.drawn, rb.drawn)
                }
            }
        }
    }
}
