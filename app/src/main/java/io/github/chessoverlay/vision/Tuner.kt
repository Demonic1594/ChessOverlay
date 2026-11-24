package io.github.chessoverlay.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Finds the fastest way to run the board model on this phone.
 *
 * Most of the time of a read is the board model's. ONNX Runtime has more
 * than one way to run a model (its own CPU code with any number of threads;
 * XNNPACK; NNAPI), and which is fastest differs per phone. Each
 * [RecognizerMode] is timed on a sample board, and has to read it as the
 * default does; the fastest that is a tenth faster than the default is
 * kept, else the default stays. The default is timed once more at the end,
 * and its better time is what the others have to beat.
 *
 * The overlay does this once, the first time it starts, with the engine
 * searching as it will be then, among the ways that run the same code as
 * the default does (the CPU with another number of threads); the app's
 * "Test recognition" tries all of them, XNNPACK and NNAPI included, shows
 * the table and keeps the result.
 */
object Tuner {
    /** The sample board the app ships, to measure on: gzipped "w h" and a newline, then RGB bytes. */
    const val SAMPLE = "vision-sample.rgb.gz"

    fun sample(stream: InputStream): Img = Img.fromRgb(GZIPInputStream(stream).use { it.readBytes() })

    /** One way of running the board model: the median time of its reads, whether it read the sample as the default does, or why it could not run. */
    class Timing(val mode: RecognizerMode, val medianMs: Long = 0, val agreed: Boolean? = null, val error: String? = null) {
        val usable: Boolean get() = error == null && agreed == true
    }

    class Result(val chosen: RecognizerMode, val timings: List<Timing>, val reading: Recognition) {
        private val default = timings.firstOrNull { it.mode == RecognizerMode.DEFAULT }

        /** The table, for the app and the log. */
        fun report(): String {
            val sb = StringBuilder("Reading one board, median of the runs, by how the models are run:\n")
            for (t in timings) {
                sb.append(t.mode.toString().padEnd(11))
                when {
                    t.error != null -> sb.append("not available: ").append(t.error!!.take(60))
                    t.agreed == false -> sb.append(t.medianMs).append(" ms, but it reads differently")
                    else -> {
                        sb.append(t.medianMs).append(" ms")
                        if (t.mode == chosen && chosen != RecognizerMode.DEFAULT) sb.append("  <- kept")
                    }
                }
                if (t.mode == RecognizerMode.DEFAULT) sb.append("  (default)")
                sb.append('\n')
            }
            val d = default
            if (chosen != RecognizerMode.DEFAULT && d != null && d.medianMs > 0) {
                sb.append("Kept ").append(chosen).append(": ").append((100 * (d.medianMs - chosenTime()) / d.medianMs)).append("% faster than ")
                    .append(RecognizerMode.DEFAULT).append(" on this phone.\n")
            } else {
                sb.append("Kept ").append(RecognizerMode.DEFAULT).append(": nothing else is clearly faster on this phone.\n")
            }
            return sb.toString()
        }

        private fun chosenTime() = timings.firstOrNull { it.mode == chosen }?.medianMs ?: 0
    }

    /**
     * The ways to try. The default (the CPU with two threads) and the CPU with
     * one and with four; with `all`, also XNNPACK with two and four and NNAPI,
     * which run other code (a provider's own, a phone's driver): what "Test
     * recognition" tries, not what the overlay does unasked.
     */
    fun candidates(all: Boolean = false): List<RecognizerMode> = listOf(
        RecognizerMode.DEFAULT,
        RecognizerMode(RecognizerMode.CPU, 1),
        RecognizerMode(RecognizerMode.CPU, 4),
    ) + if (all) listOf(
        RecognizerMode(RecognizerMode.XNNPACK, 2),
        RecognizerMode(RecognizerMode.XNNPACK, 4),
        RecognizerMode(RecognizerMode.NNAPI, 1),
    ) else emptyList()

    /**
     * Times each of `candidates` on the board of `sample`. `progress` is told
     * how far it is. Nothing here throws for a way that does not work: that
     * one is reported and left out.
     */
    fun tune(boardModel: File, locatorModel: File, sample: Img, candidates: List<RecognizerMode> = candidates(),
             warmUps: Int = 2, runs: Int = 6, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val env = OrtEnvironment.getEnvironment()
        // Where the board is on the sample, and how the default reads it. The
        // locator is not timed: a read at known corners does not use it.
        val (corners, reference) = Recognizer(boardModel, locatorModel, RecognizerMode.DEFAULT).use { r ->
            val c = r.locate(sample)
            c to r.recognise(sample, knownCorners = c)
        }
        val input = Recognizer.input(Recognizer.boardInput(sample, corners))

        fun measure(mode: RecognizerMode, warmUps: Int, runs: Int): Timing {
            var opts: OrtSession.SessionOptions? = null
            try {
                opts = OrtSession.SessionOptions().also { mode.applyTo(it) }
                val session = env.createSession(boardModel.absolutePath, opts)
                session.use {
                    var out = Recognizer.runBoard(env, session, input)   // the first run loads and tunes the kernels
                    repeat(warmUps - 1) { out = Recognizer.runBoard(env, session, input) }
                    val times = ArrayList<Long>(runs)
                    repeat(runs) {
                        val t0 = System.nanoTime()
                        out = Recognizer.runBoard(env, session, input)
                        times += System.nanoTime() - t0
                    }
                    val reading = Recognizer.interpret(out, corners, legality = true)
                    val same = reading.placement == reference.placement && reading.blackAtBottom == reference.blackAtBottom &&
                        abs(reading.confidence - reference.confidence) < 0.1f
                    return Timing(mode, medianMs(times), same)
                }
                throw IllegalStateException("unreachable")
            } catch (e: Exception) {
                // A way that does not work here (no such provider in this build, a model it cannot run): reported, not thrown.
                return Timing(mode, error = e.message ?: e.javaClass.simpleName)
            } catch (e: LinkageError) {
                return Timing(mode, error = e.toString())
            } finally {
                opts?.close()
            }
        }

        val total = candidates.size + 1
        progress(0, total)
        val timings = ArrayList<Timing>()
        val first = measure(RecognizerMode.DEFAULT, warmUps + 1, runs)
        timings += first
        for ((i, mode) in candidates.withIndex()) {
            if (mode == RecognizerMode.DEFAULT) continue
            progress(i + 1, total)
            timings += measure(mode, warmUps, runs)
        }
        progress(total, total)
        // A phone's clock wanders (it gets warm, other things run): the
        // default's better time is what the others have to beat.
        val again = measure(RecognizerMode.DEFAULT, warmUps, runs)
        val defaultMs = minOf(first.medianMs, again.medianMs)
        val best = timings.filter { it.usable && it.mode != RecognizerMode.DEFAULT }.minByOrNull { it.medianMs }
        val chosen = if (best != null && best.medianMs < defaultMs * 0.9) best.mode else RecognizerMode.DEFAULT
        return Result(chosen, timings.map { if (it.mode == RecognizerMode.DEFAULT) Timing(it.mode, defaultMs, it.agreed) else it }, reference)
    }

    private fun medianMs(nanos: List<Long>): Long {
        val sorted = nanos.sorted()
        return sorted[sorted.size / 2] / 1_000_000
    }
}
