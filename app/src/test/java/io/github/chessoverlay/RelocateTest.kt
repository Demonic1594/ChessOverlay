package io.github.chessoverlay

import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.overlay.Analyst
import io.github.chessoverlay.overlay.Look
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * From a phone: a game started as Black, the board was found while the
 * site was still moving it into place, and every later read at those
 * corners was nonsense (read confidently, so the board was never looked
 * for again): "Analysing…" forever. A board that moved must be found again.
 */
class RelocateTest {
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

    /** The same screen with everything moved down by `dy` pixels. */
    private fun shifted(img: Img, dy: Int): Img {
        val out = Img(img.w, img.h)
        val bg = img[5, 5]
        for (y in 0 until img.h) for (x in 0 until img.w) {
            val sy = y - dy
            out.px[y * img.w + x] = if (sy in 0 until img.h) img[x, sy] else bg
        }
        return out
    }

    @Test fun aBoardThatMovedIsFoundAgain() {
        val dir = System.getenv("MODELS")?.let(::File)
        assumeTrue(dir != null && File(dir, "board.onnx").exists())
        val engine = System.getenv("ENGINE")?.let(::File)
        assumeTrue(engine != null && engine.canExecute())
        val states = java.util.concurrent.CopyOnWriteArrayList<OverlayState>()
        Recognizer(File(dir, "board.onnx"), File(dir, "locator.onnx")).use { rec ->
            lateinit var a: Analyst
            val work = File(System.getProperty("java.io.tmpdir"), "relocate-engine").apply { mkdirs() }
            EngineHost(engine!!, work, emptyMap(), onUpdate = { a.onEngine(it) }, onStatus = {}).use { host ->
                a = Analyst(rec, host) { states += it }
                val screen = load("screen1")
                assertEquals(Look.NEW, a.look(screen, auto = true))
                val first = a.board.corners!!.map { it.copyOf() }
                // The board slides down a third of a square... and further.
                for (dy in listOf(60, 180)) {
                    val r = a.look(shifted(screen, dy), auto = true)
                    assertEquals("moved by $dy px", Look.SAME, r)
                    assertNotEquals("corners follow the board", first[0][1], a.board.corners!![0][1], 1f)
                    assertEquals("8/8/8/4p1K1/2k1P3/8/8/8", a.board.board!!.placement())
                }
            }
        }
    }
}
