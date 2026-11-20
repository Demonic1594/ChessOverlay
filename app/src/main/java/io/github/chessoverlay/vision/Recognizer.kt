package io.github.chessoverlay.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Chess board recognition: a Kotlin port of chessimg2pos v2 (MIT, Mattia
 * Dicio, github.com/mdicio/chessimg2pos), using its ONNX models:
 * locator.onnx finds the board's corners (two passes: whole image, then a
 * crop around the first guess); board.onnx classifies the 64 squares and the
 * orientation; a legality step fixes impossible boards on uncertain squares.
 */
class Recognizer(boardModel: File, locatorModel: File, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val board = env.createSession(boardModel.absolutePath, opts)
    private val locator = env.createSession(locatorModel.absolutePath, opts)

    override fun close() {
        board.close(); locator.close(); opts.close()
    }

    /** Find the board and read it. With `knownCorners` (the board has not
     *  moved on screen) the locator is skipped: one model run instead of three. */
    fun recognise(img: Img, legality: Boolean = true, knownCorners: List<FloatArray>? = null): Recognition {
        val corners = knownCorners ?: locate(img)
        val input = boardInput(img, corners)
        val x = FloatArray(3 * BOARD_SIZE * BOARD_SIZE)
        input.toTensor(x)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, BOARD_SIZE.toLong(), BOARD_SIZE.toLong())).use { t ->
            board.run(mapOf("board" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val sq = (out[0].value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val ori = (out[1].value as Array<FloatArray>)[0]
                val probs = Array(64) { softmax(sq[it]) }
                val oriP = softmax(ori)
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
        }
    }

    /** Board corners (TL, TR, BR, BL) in image pixels. */
    fun locate(img: Img): List<FloatArray> {
        val rough = locateOnce(img)
        val (crop, ox, oy) = refineCrop(img, cornersToBox(rough))
        return locateOnce(crop).map { floatArrayOf(it[0] + ox, it[1] + oy) }
    }

    private fun locateOnce(img: Img): List<FloatArray> {
        val side = max(img.w, img.h)
        val x = FloatArray(3 * LOC_SIZE * LOC_SIZE)
        img.padSquare(side).resize(LOC_SIZE, LOC_SIZE).toTensor(x)
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

        fun cornersToBox(c: List<FloatArray>) =
            floatArrayOf(c.minOf { it[0] }, c.minOf { it[1] }, c.maxOf { it[0] }, c.maxOf { it[1] })

        /** Region around a rough box for the locator's second pass. */
        fun refineCrop(img: Img, box: FloatArray, margin: Float = 0.1f): Triple<Img, Int, Int> {
            val bw = box[2] - box[0]; val bh = box[3] - box[1]
            var x0 = max(0, (box[0] - margin * bw).toInt()); var y0 = max(0, (box[1] - margin * bh).toInt())
            var x1 = min(img.w, (box[2] + margin * bw).toInt()); var y1 = min(img.h, (box[3] + margin * bh).toInt())
            if (x1 - x0 < 32 || y1 - y0 < 32) { x0 = 0; y0 = 0; x1 = img.w; y1 = img.h }
            return Triple(img.crop(x0, y0, x1, y1), x0, y0)
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
                return img.crop(pyRound(b[0]), pyRound(b[1]), pyRound(b[2]), pyRound(b[3])).resize(BOARD_SIZE, BOARD_SIZE)
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
    }
}
