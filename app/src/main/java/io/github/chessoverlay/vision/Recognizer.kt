package io.github.chessoverlay.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the models are run: by ONNX Runtime's own code on the CPU with
 * `threads` threads, or by XNNPACK or NNAPI (the Android build of ONNX
 * Runtime has both). Which is fastest differs per phone; [Tuner] measures.
 * [toString] is the saved form, as "cpu:2".
 */
data class RecognizerMode(val provider: String = CPU, val threads: Int = DEFAULT_THREADS) {

    /** Sets this on `opts`; throws IllegalArgumentException when it is not available in this ONNX Runtime. */
    fun applyTo(opts: OrtSession.SessionOptions) {
        try {
            when (provider) {
                CPU -> opts.setIntraOpNumThreads(threads)
                XNNPACK -> {
                    opts.setIntraOpNumThreads(threads)
                    opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                NNAPI -> {
                    opts.setIntraOpNumThreads(threads)
                    opts.addNnapi()
                }
                else -> throw IllegalArgumentException("no such provider: $provider")
            }
        } catch (e: OrtException) {
            throw unavailable(e)
        } catch (e: LinkageError) {   // an older ONNX Runtime, or one without the provider
            throw unavailable(e)
        }
    }

    private fun unavailable(e: Throwable) = IllegalArgumentException("$this is not available here: ${e.message}", e)

    override fun toString() = "$provider:$threads"

    companion object {
        const val CPU = "cpu"
        const val XNNPACK = "xnnpack"
        const val NNAPI = "nnapi"
        const val DEFAULT_THREADS = 2

        /** Two threads of ONNX Runtime's own code: what is used until the phone has been measured. */
        val DEFAULT = RecognizerMode(CPU, DEFAULT_THREADS)

        /** From [toString]'s form; `fallback` when it is not one. */
        fun parse(text: String?, fallback: RecognizerMode = DEFAULT): RecognizerMode {
            val parts = text?.split(":") ?: return fallback
            if (parts.size != 2) return fallback
            val threads = parts[1].toIntOrNull() ?: return fallback
            if (threads < 1) return fallback
            return RecognizerMode(parts[0], threads)
        }
    }
}

/**
 * Chess board recognition: a Kotlin port of chessimg2pos v2 (MIT, Mattia
 * Dicio, github.com/mdicio/chessimg2pos), using its ONNX models:
 * locator.onnx finds the board's corners (two passes: whole image, then a
 * crop around the first guess); board.onnx classifies the 64 squares and the
 * orientation; a legality step fixes impossible boards on uncertain squares.
 *
 * Most of the time of a read is the board model's. [lastTiming] says where
 * the last read's time went, and [Tuner] finds the fastest [RecognizerMode].
 */
class Recognizer(boardModel: File, locatorModel: File, val mode: RecognizerMode = RecognizerMode.DEFAULT) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().also { o ->
        try {
            mode.applyTo(o)
        } catch (e: Exception) {
            o.close()
            throw e
        }
    }
    private val board = env.createSession(boardModel.absolutePath, opts)
    private val locator = env.createSession(locatorModel.absolutePath, opts)

    /** Where the time of the last read went, for the log. */
    var lastTiming = ""
        private set

    override fun close() {
        board.close(); locator.close(); opts.close()
    }

    /**
     * Find the board and read it. With `knownCorners` (the board has not
     * moved on screen) the locator is skipped: one model run instead of
     * three. A located board is sharpened to its own edges when `sharpen`
     * (see BoardEdges).
     */
    fun recognise(img: Img, legality: Boolean = true, knownCorners: List<FloatArray>? = null, sharpen: Boolean = true): Recognition {
        if (knownCorners != null) return read(img, knownCorners, legality)
        val t0 = System.nanoTime()
        var located = locate(img)
        // A screen shows a board upright. A skewed shape is the locator half on something else (a board beside
        // panels of the page): looked for again within a region somewhat wider than that shape, where the board
        // is most of the picture, it is found. Kept only if it then stands upright.
        if (!isAxisAligned(located)) {
            val again = locateAround(img, located)
            if (again != null && isAxisAligned(BoardEdges.refine(img, again))) {
                lastRelocated = true
                located = again
            }
        }
        val find = (System.nanoTime() - t0) / 1_000_000
        // The locator's corners are a little off; the board's own edges say where it is.
        val edges = if (sharpen) BoardEdges.refine(img, located) else located
        var r = read(img, edges, legality)
        var timing = lastTiming
        // Where the two disagree, the board is read at both and the surer reading kept (as fenshot does): edges
        // can be misled by a site's frame or a strip of the page, and corners a fifth of a square off cut every
        // square and lower the model's certainty across the board.
        lastAlignment = if (lastRelocated) "found again around a skewed first find" else null
        lastRelocated = false
        if (edges !== located && cornerShift(edges, located) > ALIGN_SHIFT && r.meanConfidence() < SURE) {
            val alt = read(img, located, legality)
            val a = r.meanConfidence(); val b = alt.meanConfidence()
            lastAlignment = String.format(java.util.Locale.US, "edges %.3f, locator %.3f: %s", a, b,
                if (b > a + ALIGN_MARGIN) "locator" else "edges")
            if (b > a + ALIGN_MARGIN) r = alt
            timing += "; second alignment: $lastTiming"
        }
        lastTiming = "find ${find} ms, $timing"
        return r
    }

    /** When the last located read compared two alignments: their certainty and the one kept (for the log). */
    @Volatile var lastAlignment: String? = null
        private set

    /** The board at `corners` read: its image prepared, the model run, the reading taken from the model's output. */
    private fun read(img: Img, corners: List<FloatArray>, legality: Boolean): Recognition {
        val t0 = System.nanoTime()
        val input = input(boardInput(img, corners))
        val t1 = System.nanoTime()
        val out = runBoard(env, board, input)
        val t2 = System.nanoTime()
        val r = interpret(out, corners, legality)
        val t3 = System.nanoTime()
        lastTiming = "prepare ${ms(t0, t1)} ms, model ${ms(t1, t2)} ms, rest ${ms(t2, t3)} ms"
        return r
    }

    /** The last located read had to look again around a skewed first find (for the log). */
    @Volatile var lastRelocated = false

    /** The locator again, on the region around `k` widened by RELOCATE_PAD of its side; null if too small. */
    private fun locateAround(img: Img, k: List<FloatArray>): List<FloatArray>? {
        val b = cornersToBox(k)
        val pad = RELOCATE_PAD * max(b[2] - b[0], b[3] - b[1])
        val x0 = max(0, (b[0] - pad).toInt()); val y0 = max(0, (b[1] - pad).toInt())
        val x1 = min(img.w, (b[2] + pad).toInt()); val y1 = min(img.h, (b[3] + pad).toInt())
        if (x1 - x0 < 32 || y1 - y0 < 32) return null
        val r = refineBox(img, cornersToBox(locateOnce(img, x0, y0, x1 - x0, y1 - y0).map { floatArrayOf(it[0] + x0, it[1] + y0) }))
        return locateOnce(img, r[0], r[1], r[2] - r[0], r[3] - r[1]).map { floatArrayOf(it[0] + r[0], it[1] + r[1]) }
    }

    /** Board corners (TL, TR, BR, BL) in image pixels. */
    fun locate(img: Img): List<FloatArray> {
        val rough = locateOnce(img, 0, 0, img.w, img.h)
        val r = refineBox(img, cornersToBox(rough))
        return locateOnce(img, r[0], r[1], r[2] - r[0], r[3] - r[1]).map { floatArrayOf(it[0] + r[0], it[1] + r[1]) }
    }

    /** The locator on the region (x0, y0, w, h) of `img`, padded to a square with black at the right or bottom. */
    private fun locateOnce(img: Img, x0: Int, y0: Int, w: Int, h: Int): List<FloatArray> {
        val side = max(w, h)
        val x = FloatArray(3 * LOC_SIZE * LOC_SIZE)
        img.resizeRegion(x0, y0, side, side, LOC_SIZE, LOC_SIZE, w, h).toTensor(x)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, LOC_SIZE.toLong(), LOC_SIZE.toLong())).use { t ->
            locator.run(mapOf("image" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = (out[0].value as Array<FloatArray>)[0].map { it * side }
                return if (v.size == 4) boxToCorners(v[0], v[1], v[2], v[3])
                else listOf(floatArrayOf(v[0], v[1]), floatArrayOf(v[2], v[3]), floatArrayOf(v[4], v[5]), floatArrayOf(v[6], v[7]))
            }
        }
    }

    companion object {
        const val BOARD_SIZE = 256
        const val LOC_SIZE = 320

        fun softmax(v: FloatArray): FloatArray {
            val m = v.max()
            val e = FloatArray(v.size) { exp((v[it] - m).toDouble()).toFloat() }
            val s = e.sum()
            for (i in e.indices) e[i] /= s
            return e
        }

        fun argmax(v: FloatArray): Int = Fen.argmax(v)

        fun boxToCorners(x0: Float, y0: Float, x1: Float, y1: Float) =
            listOf(floatArrayOf(x0, y0), floatArrayOf(x1, y0), floatArrayOf(x1, y1), floatArrayOf(x0, y1))

        /** How much wider than a skewed first find the region looked in again is (of its side). */
        const val RELOCATE_PAD = 0.35f
        /** Edges and locator further apart than this (of the side): both alignments are read. */
        const val ALIGN_SHIFT = 0.02f
        /** A reading this sure of the whole board needs no second alignment. */
        const val SURE = 0.995f
        /** The locator's alignment must be this much surer to be kept over the edges'. */
        const val ALIGN_MARGIN = 0.005f

        fun cornersToBox(c: List<FloatArray>) =
            floatArrayOf(c.minOf { it[0] }, c.minOf { it[1] }, c.maxOf { it[0] }, c.maxOf { it[1] })

        /** Region (x0, y0, x1, y1) around a rough box for the locator's second pass. */
        fun refineBox(img: Img, box: FloatArray, margin: Float = 0.1f): IntArray {
            val bw = box[2] - box[0]; val bh = box[3] - box[1]
            var x0 = max(0, (box[0] - margin * bw).toInt()); var y0 = max(0, (box[1] - margin * bh).toInt())
            var x1 = min(img.w, (box[2] + margin * bw).toInt()); var y1 = min(img.h, (box[3] + margin * bh).toInt())
            if (x1 - x0 < 32 || y1 - y0 < 32) { x0 = 0; y0 = 0; x1 = img.w; y1 = img.h }
            return intArrayOf(x0, y0, x1, y1)
        }

        fun isAxisAligned(c: List<FloatArray>, tol: Float = 0.006f): Boolean {
            val b = cornersToBox(c)
            val side = max(max(b[2] - b[0], b[3] - b[1]), 1e-6f)
            val r = boxToCorners(b[0], b[1], b[2], b[3])
            for (i in 0 until 4) for (j in 0 until 2) if (kotlin.math.abs(c[i][j] - r[i][j]) / side > tol) return false
            return true
        }

        /** Python's round(): halves go to the even neighbour. */
        private fun pyRound(v: Float): Int = Math.rint(v.toDouble()).toInt()

        /** The classifier input: crop + resize for screenshots, perspective warp for photos. */
        fun boardInput(img: Img, corners: List<FloatArray>): Img {
            if (isAxisAligned(corners)) {
                val b = cornersToBox(corners)
                val x0 = pyRound(b[0]); val y0 = pyRound(b[1])
                return img.resizeRegion(x0, y0, max(0, pyRound(b[2]) - x0), max(0, pyRound(b[3]) - y0), BOARD_SIZE, BOARD_SIZE)
            }
            return warp(img, corners)
        }

        private fun warp(src: Img, cornersIn: List<FloatArray>): Img {
            var img = src
            var corners = cornersIn.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
            val sides = (0 until 4).map {
                val a = corners[it]; val b = corners[(it + 1) % 4]
                hypot(a[0] - b[0], a[1] - b[1])
            }
            val f = sides.min() / (1.5 * BOARD_SIZE)
            if (f > 1) {
                img = img.resize(max(1, (img.w / f).roundToInt()), max(1, (img.h / f).roundToInt()))
                corners = corners.map { doubleArrayOf(it[0] / f, it[1] / f) }
            }
            return img.perspective(BOARD_SIZE, perspectiveCoeffs(corners, BOARD_SIZE))
        }

        /** Coefficients mapping the output square to the quad (TL, TR, BR, BL). */
        fun perspectiveCoeffs(src: List<DoubleArray>, size: Int): DoubleArray {
            val dst = listOf(0.0 to 0.0, size.toDouble() to 0.0, size.toDouble() to size.toDouble(), 0.0 to size.toDouble())
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (u, v) = dst[i]; val x = src[i][0]; val y = src[i][1]
                a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
            }
            // Gaussian elimination with partial pivoting on the 8x9 augmented matrix.
            for (col in 0 until 8) {
                val p = (col until 8).maxBy { kotlin.math.abs(a[it][col]) }
                val t = a[col]; a[col] = a[p]; a[p] = t
                for (r in 0 until 8) if (r != col) {
                    val k = a[r][col] / a[col][col]
                    for (c in col..8) a[r][c] -= k * a[col][c]
                }
            }
            return DoubleArray(8) { a[it][8] / a[it][it] }
        }

        /** The classifier's input for a board image of BOARD_SIZE x BOARD_SIZE. */
        fun input(img: Img): FloatArray {
            val x = FloatArray(3 * BOARD_SIZE * BOARD_SIZE)
            img.toTensor(x)
            return x
        }

        /** What the board model says about its input: each square's class scores (64 x 13), and the orientation's. */
        class BoardOutput(val squares: Array<FloatArray>, val orientation: FloatArray)

        fun runBoard(env: OrtEnvironment, session: OrtSession, x: FloatArray): BoardOutput =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, BOARD_SIZE.toLong(), BOARD_SIZE.toLong())).use { t ->
                session.run(mapOf("board" to t)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val sq = (out[0].value as Array<Array<FloatArray>>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val ori = (out[1].value as Array<FloatArray>)[0]
                    BoardOutput(sq, ori)
                }
            }

        /** The reading from the board model's output: probabilities, the legality step, and which way round. */
        fun interpret(out: BoardOutput, corners: List<FloatArray>, legality: Boolean): Recognition {
            val probs = Array(64) { softmax(out.squares[it]) }
            val oriP = softmax(out.orientation)
            val (idx, changed) = if (legality) Legality.fix(probs) else Pair(IntArray(64) { Fen.argmax(probs[it]) }, 0)
            val drawn = Fen.fromIndices(idx)
            // Which way round: the model, checked against the position (see Orientation).
            val black = if (legality) Orientation.decide(drawn, oriP[1]) else oriP[1] > 0.5f
            var conf = 1f
            for (i in 0 until 64) conf = min(conf, probs[i][idx[i]])
            return Recognition(
                placement = Orientation.whiteView(drawn, black),
                blackAtBottom = black,
                confidence = conf,
                orientationConfidence = if (black) oriP[1] else oriP[0],
                corners = corners,
                legalityChanges = changed,
                drawn = drawn,
                drawnProbs = probs.toList(),
                blackBottomP = oriP[1],
            )
        }

        private fun ms(from: Long, to: Long) = (to - from) / 1_000_000
    }
}
