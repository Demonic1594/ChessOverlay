package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.overlay.Analyst
import io.github.chessoverlay.overlay.Look
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.vision.BoardTracker
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The whole fast path with the real recognizer: a real board, a move made
 * by moving the queen's pixels, the move seen from the changed squares and
 * analysed before the recognizer runs, then confirmed by it without a
 * second analysis. Needs MODELS (as VisionTest); the engine is a stand-in.
 */
class FastReadTest {
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

    /** Copy square `from`'s pixels onto square `to` (a board filling `img`, White at the bottom). */
    private fun copySquare(src: Img, dst: Img, from: String, to: String) {
        val s = src.w / 8.0
        val f = Board.squareOf(from); val t = Board.squareOf(to)
        val fx = (f % 8 * s).toInt(); val fy = ((7 - f / 8) * s).toInt()
        val tx = (t % 8 * s).toInt(); val ty = ((7 - t / 8) * s).toInt()
        for (y in 0 until s.toInt()) for (x in 0 until s.toInt()) dst.px[(ty + y) * dst.w + tx + x] = src.px[(fy + y) * src.w + fx + x]
    }

    private fun fakeEngine(dir: File): File {
        val bin = File(dir, "lines.sh")
        bin.writeText("""
            #!/bin/sh
            while read -r line; do
              case "${'$'}line" in
                uci) echo "id name Lines"; echo "option name MultiPV type spin default 1 min 1 max 5"; echo uciok ;;
                isready) echo readyok ;;
                go*) echo "info depth 12 seldepth 14 multipv 1 score cp 150 nodes 1000 pv a2a3" ;;
                stop) echo "bestmove a2a3" ;;
                quit) exit 0 ;;
              esac
            done
        """.trimIndent() + "\n")
        bin.setExecutable(true)
        return bin
    }

    @Test fun aMoveIsAnalysedBeforeTheRecognizerAndConfirmedByIt() {
        val dir = System.getenv("MODELS")?.let(::File)
        assumeTrue("set MODELS to run this test", dir != null && File(dir, "board.onnx").exists())
        val work = File(System.getProperty("java.io.tmpdir"), "fast-read").apply { mkdirs() }
        val states = java.util.concurrent.CopyOnWriteArrayList<OverlayState>()
        Recognizer(File(dir, "board.onnx"), File(dir, "locator.onnx")).use { rec ->
            lateinit var a: Analyst
            EngineHost(fakeEngine(work), work, emptyMap(), onUpdate = { a.onEngine(it) }, onStatus = {}).use { host ->
                a = Analyst(rec, host) { states += it }
                val before = load("sample2")
                assertEquals(Look.NEW, a.look(before, auto = true))
                assertEquals("5rk1/qbR3pp/pn2p3/1p3pQ1/4P3/6P1/PP3PBP/5RK1", a.board.board!!.placement())
                assertTrue(a.board.whiteToMove)

                // Qg5-h6 (both dark squares): the queen's square onto h6, an empty dark square onto g5.
                val after = Img(before.w, before.h).also { before.px.copyInto(it.px) }
                copySquare(before, after, "g5", "h6")
                copySquare(before, after, "d4", "g5")
                val (xs, ys) = a.board.points()!!
                val px = BoardTracker.sample(after, xs, ys, 0, 0)

                val t0 = System.nanoTime()
                assertTrue("the move is seen from the changed squares", a.fastLook(px, null))
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("seen at once in $ms ms")
                val expected = "5rk1/qbR3pp/pn2p2Q/1p3p2/4P3/6P1/PP3PBP/5RK1"
                assertEquals(expected, states.last().board!!.placement())
                assertFalse("Black to move", states.last().whiteToMove)
                val analysed = DebugLog.dump().lines().count { "analyse $expected" in it }

                // The recognizer reads the clean capture: the same position, so no second analysis.
                assertEquals(Look.NEW, a.look(after, auto = true))
                assertEquals(expected, a.board.board!!.placement())
                assertFalse(a.board.whiteToMove)
                val log = DebugLog.dump()
                assertEquals("analysed once", analysed, log.lines().count { "analyse $expected" in it })
                assertFalse("no correction: $log", "was wrong" in log)
                assertEquals("g5h6", a.board.lastMove)
            }
        }
    }
}
