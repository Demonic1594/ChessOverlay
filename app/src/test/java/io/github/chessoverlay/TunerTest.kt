package io.github.chessoverlay

import io.github.chessoverlay.vision.RecognizerMode
import io.github.chessoverlay.vision.Tuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The tuner against the real models (needs MODELS, as VisionTest). The
 * desktop ONNX Runtime has neither XNNPACK nor NNAPI: those are reported as
 * not available and left out, which is what must happen on a phone too when
 * one of them does not work.
 */
class TunerTest {
    @Test fun everyWayIsTimedAndTheKeptOneReadsTheSame() {
        val dir = System.getenv("MODELS")
        assumeTrue("set MODELS to run this test", dir != null && File(dir, "board.onnx").exists())
        val sample = Tuner.sample(javaClass.getResourceAsStream("/vision/screen1.rgb.gz")!!)
        val result = Tuner.tune(File(dir, "board.onnx"), File(dir, "locator.onnx"), sample, warmUps = 2, runs = 4)
        println(result.report())
        assertEquals("8/8/8/4p1K1/2k1P3/8/8/8", result.reading.placement)
        assertEquals(Tuner.candidates().toSet(), result.timings.map { it.mode }.toSet())
        // Every way that ran read the sample as the default does.
        for (t in result.timings) assertTrue("$t.mode: it ran but read differently", t.error != null || t.agreed == true)
        assertTrue("the default did not run", result.timings.any { it.mode == RecognizerMode.DEFAULT && it.usable })
        // What is kept is a way that ran and read right, and it is not slower than the default.
        val kept = result.timings.first { it.mode == result.chosen }
        assertTrue(kept.usable)
        val default = result.timings.first { it.mode == RecognizerMode.DEFAULT }
        assertTrue(kept.medianMs <= default.medianMs)
    }
}
