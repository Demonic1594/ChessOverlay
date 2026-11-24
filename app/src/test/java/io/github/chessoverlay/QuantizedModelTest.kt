package io.github.chessoverlay

import io.github.chessoverlay.vision.Fen
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The int8 board model the app bundles (tools/quantize_models.py) reads
 * every test image as the original does, on every square the original is
 * sure of. Where the original itself hesitates (a rook seen upside down on
 * a phone screenshot, between it and a pawn), the quantized model's
 * probability may tip the other way, and which way depends on the
 * floating-point details of the CPU: such a square is left out. Runs when
 * MODELS (the originals) and QMODELS (the bundled ones) are set, as in CI.
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
                    assertEquals(name, ra.blackAtBottom, rb.blackAtBottom)
                    for (sq in 0 until 64) {
                        val pa = ra.drawnProbs!![sq]
                        val pb = rb.drawnProbs!![sq]
                        val ca = Recognizer.argmax(pa)
                        val cb = Recognizer.argmax(pb)
                        // The original hesitates on this square itself.
                        if (pa[ca] < SURE) continue
                        assertEquals("$name, square $sq: the original reads ${Fen.CLASSES[ca]} at ${String.format(java.util.Locale.US, "%.2f", pa[ca])}, " +
                            "the int8 model ${Fen.CLASSES[cb]} at ${String.format(java.util.Locale.US, "%.2f", pb[cb])}", ca, cb)
                    }
                }
            }
        }
    }
}

/** A square the original is at least this sure of must be read the same. */
private const val SURE = 0.8f
