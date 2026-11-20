package io.github.chessoverlay

import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.engine.UciEngine
import io.github.chessoverlay.engine.UciOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UciTest {
    @Test fun parseOptions() {
        assertEquals(UciOption("Hash", "spin", "16", 1, 33554432),
            UciOption.parse("option name Hash type spin default 16 min 1 max 33554432"))
        assertEquals(UciOption("Move Overhead", "spin", "10", 0, 5000),
            UciOption.parse("option name Move Overhead type spin default 10 min 0 max 5000"))
        assertEquals(UciOption("SyzygyPath", "string", ""),
            UciOption.parse("option name SyzygyPath type string default <empty>"))
        assertEquals(UciOption("Clear Hash", "button", ""), UciOption.parse("option name Clear Hash type button"))
        val combo = UciOption.parse("option name Style type combo default Normal var Solid var Normal var Risky")!!
        assertEquals(listOf("Solid", "Normal", "Risky"), combo.vars)
        // Words that look like keywords inside a name.
        assertEquals("Use default min", UciOption.parse("option name Use default min type check default true")!!.name)
        assertNull(UciOption.parse("id name Stockfish"))
    }

    @Test fun parseInfo() {
        val l = PvLine.parse("info depth 20 seldepth 28 multipv 2 score cp -35 wdl 50 800 150 nodes 123456 nps 1 pv e7e5 g1f3 b8c6")!!
        assertEquals(2, l.multipv); assertEquals(20, l.depth); assertEquals(-35, l.scoreCp)
        assertEquals(listOf("e7e5", "g1f3", "b8c6"), l.pv)
        assertEquals("+0.35", l.scoreText(whiteToMove = false))
        val m = PvLine.parse("info depth 30 multipv 1 score mate -3 nodes 1 pv e8f8")!!
        assertEquals("M3", m.scoreText(whiteToMove = false))
        assertNull(PvLine.parse("info depth 12 score cp 20 lowerbound nodes 5 pv e2e4"))
        assertNull(PvLine.parse("info string NNUE evaluation using nn.nnue"))
    }

    /** Runs a real engine if ENGINE (env) points at one, e.g. a host build of Smotherfish. */
    @Test fun realEngineAnalysis() {
        val path = System.getenv("ENGINE")
        assumeTrue("set ENGINE to a UCI engine binary to run this test", path != null && File(path).canExecute())
        val e = UciEngine(File(path!!), File(System.getProperty("java.io.tmpdir")))
        e.start()
        assertTrue(e.options.any { it.name == "MultiPV" })
        val done = CountDownLatch(1)
        var final: List<PvLine> = emptyList()
        e.analyse("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", 3, 500) { lines, finished ->
            if (finished) { final = lines; done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(3, final.size)
        assertEquals(listOf(1, 2, 3), final.map { it.multipv })
        assertTrue(final.all { it.pv.isNotEmpty() && it.depth > 5 })
        // Restart a search while one runs: the first must stop cleanly.
        val second = CountDownLatch(1)
        e.analyse("4k3/8/8/8/8/8/8/R3K3 w - - 0 1", 1, 0) { _, _ -> second.countDown() }
        assertTrue(second.await(5, TimeUnit.SECONDS))
        e.stop()
        e.quit()
    }
}
