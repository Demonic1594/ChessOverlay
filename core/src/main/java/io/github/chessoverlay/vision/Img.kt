package io.github.chessoverlay.vision

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * An RGB image as packed 0xRRGGBB ints, with the few operations the
 * recognizer needs. They follow Pillow (PIL), which the models were trained
 * with, closely: resize() is Pillow's BILINEAR resampling (a triangle filter
 * widened when shrinking, fixed-point arithmetic, two passes). Android's
 * Bitmap scaling does not blend when shrinking, which costs accuracy.
 * Plain Kotlin, so it runs in JVM unit tests.
 */
class Img(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {

    operator fun get(x: Int, y: Int) = px[y * w + x]

    /** Crop (x0, y0, x1, y1); parts outside the image are black, as in PIL. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Img {
        val out = Img(max(0, x1 - x0), max(0, y1 - y0))
        for (y in 0 until out.h) {
            val sy = y + y0
            if (sy < 0 || sy >= h) continue
            for (x in 0 until out.w) {
                val sx = x + x0
                if (sx in 0 until w) out.px[y * out.w + x] = px[sy * w + sx]
            }
        }
        return out
    }

    /** This image pasted at the top-left of a black side x side square. */
    fun padSquare(side: Int): Img {
        val out = Img(side, side)
        for (y in 0 until min(h, side)) System.arraycopy(px, y * w, out.px, y * side, min(w, side))
        return out
    }

    /** Pillow-compatible BILINEAR resize. */
    fun resize(nw: Int, nh: Int): Img {
        var img = this
        if (nw != w) img = img.resampleH(nw)
        if (nh != h) img = img.transpose().resampleH(nh).transpose()
        return img
    }

    /**
     * The same as crop(x0, y0, x0 + vw, y0 + vh).resize(nw, nh), to the
     * pixel, without making the crop: the region is read where it lies,
     * black outside the image (so padSquare(side).resize(n, n) is
     * region(0, 0, side, side, n, n)). For the locator a phone screen padded
     * to a square is about 40 MB; this needs a few.
     *
     * Only the region's first `cw` columns and `ch` rows are read, the rest
     * is black: crop(x0, y0, x0 + cw, y0 + ch).padSquare(side).resize(n, n)
     * is region(x0, y0, side, side, n, n, cw, ch).
     */
    fun resizeRegion(x0: Int, y0: Int, vw: Int, vh: Int, nw: Int, nh: Int, cw: Int = vw, ch: Int = vh): Img {
        // The horizontal pass, over the region's rows that lie in the image (the others stay black).
        val mid = Img(nw, vh)
        val xEnd = min(w, x0 + min(cw, vw))
        val rows = max(0, y0)..min(h - 1, y0 + min(ch, vh) - 1)
        if (nw == vw) {
            for (sy in rows) for (x in 0 until vw) {
                val sx = x0 + x
                if (sx in 0 until xEnd) mid.px[(sy - y0) * nw + x] = px[sy * w + sx]
            }
        } else {
            val (bounds, coeffs, ksize) = precompute(vw, nw)
            val half = 1 shl (PRECISION_BITS - 1)
            for (sy in rows) {
                val row = sy * w
                val out = (sy - y0) * nw
                for (xx in 0 until nw) {
                    val xmin = bounds[2 * xx]
                    val xmax = bounds[2 * xx + 1]
                    var r = half; var g = half; var b = half
                    for (x in 0 until xmax) {
                        val sx = x0 + xmin + x
                        if (sx < 0 || sx >= xEnd) continue
                        val k = coeffs[xx * ksize + x]
                        val p = px[row + sx]
                        r += ((p shr 16) and 0xff) * k
                        g += ((p shr 8) and 0xff) * k
                        b += (p and 0xff) * k
                    }
                    mid.px[out + xx] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
                }
            }
        }
        if (nh == vh) return mid
        // The vertical pass, straight down the columns (no transposes).
        val (bounds, coeffs, ksize) = precompute(vh, nh)
        val half = 1 shl (PRECISION_BITS - 1)
        val out = Img(nw, nh)
        for (yy in 0 until nh) {
            val ymin = bounds[2 * yy]
            val ymax = bounds[2 * yy + 1]
            for (x in 0 until nw) {
                var r = half; var g = half; var b = half
                for (y in 0 until ymax) {
                    val k = coeffs[yy * ksize + y]
                    val p = mid.px[(ymin + y) * nw + x]
                    r += ((p shr 16) and 0xff) * k
                    g += ((p shr 8) and 0xff) * k
                    b += (p and 0xff) * k
                }
                out.px[yy * nw + x] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }

    private fun transpose(): Img {
        val out = Img(h, w)
        for (y in 0 until h) for (x in 0 until w) out.px[x * h + y] = px[y * w + x]
        return out
    }

    /** One horizontal pass of Pillow's ImagingResample (BILINEAR). */
    private fun resampleH(nw: Int): Img {
        val (bounds, coeffs, ksize) = precompute(w, nw)
        val out = Img(nw, h)
        val half = 1 shl (PRECISION_BITS - 1)
        for (y in 0 until h) {
            val row = y * w
            for (xx in 0 until nw) {
                val xmin = bounds[2 * xx]
                val xmax = bounds[2 * xx + 1]
                var r = half; var g = half; var b = half
                for (x in 0 until xmax) {
                    val k = coeffs[xx * ksize + x]
                    val p = px[row + xmin + x]
                    r += ((p shr 16) and 0xff) * k
                    g += ((p shr 8) and 0xff) * k
                    b += (p and 0xff) * k
                }
                out.px[y * nw + xx] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }

    /** Pillow's perspective transform (BILINEAR) onto a size x size square.
     *  coeffs map output (x, y) to input coordinates. */
    fun perspective(size: Int, c: DoubleArray): Img {
        val out = Img(size, size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val xo = x + 0.5; val yo = y + 0.5
                val d = c[6] * xo + c[7] * yo + 1
                val xi = (c[0] * xo + c[1] * yo + c[2]) / d - 0.5
                val yi = (c[3] * xo + c[4] * yo + c[5]) / d - 0.5
                out.px[y * size + x] = sampleBilinear(xi, yi)
            }
        }
        return out
    }

    private fun sampleBilinear(xi: Double, yi: Double): Int {
        val x0 = floor(xi).toInt(); val y0 = floor(yi).toInt()
        val dx = xi - x0; val dy = yi - y0
        if (x0 < -1 || y0 < -1 || x0 >= w || y0 >= h) return 0
        fun p(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) px[y * w + x] else 0
        val a = p(x0, y0); val b = p(x0 + 1, y0); val c = p(x0, y0 + 1); val d = p(x0 + 1, y0 + 1)
        var res = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val va = (a shr shift) and 0xff; val vb = (b shr shift) and 0xff
            val vc = (c shr shift) and 0xff; val vd = (d shr shift) and 0xff
            val top = va + (vb - va) * dx
            val bot = vc + (vd - vc) * dx
            val v = (top + (bot - top) * dy + 0.5).toInt().coerceIn(0, 255)
            res = res or (v shl shift)
        }
        return res
    }

    /** CHW float32, scaled to 0..1 and ImageNet-normalised (writes into `out` at `offset`). */
    fun toTensor(out: FloatArray, offset: Int = 0) {
        val n = w * h
        for (i in 0 until n) {
            val p = px[i]
            out[offset + i] = (((p shr 16) and 0xff) / 255f - MEAN[0]) / STD[0]
            out[offset + n + i] = (((p shr 8) and 0xff) / 255f - MEAN[1]) / STD[1]
            out[offset + 2 * n + i] = ((p and 0xff) / 255f - MEAN[2]) / STD[2]
        }
    }

    companion object {
        private const val PRECISION_BITS = 32 - 8 - 2
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** An image from "w h" and a newline, then w x h pixels as RGB bytes (the test images' format). */
        fun fromRgb(bytes: ByteArray): Img {
            val end = bytes.indexOf('\n'.code.toByte())
            require(end > 0) { "no size line" }
            val (w, h) = String(bytes, 0, end).trim().split(Regex("\\s+")).map { it.toInt() }
            require(w > 0 && h > 0 && bytes.size >= end + 1 + 3 * w * h) { "the image is shorter than $w x $h" }
            val img = Img(w, h)
            for (i in 0 until w * h) {
                val o = end + 1 + 3 * i
                img.px[i] = ((bytes[o].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
            }
            return img
        }

        private fun clip8(v: Int): Int {
            val s = v shr PRECISION_BITS
            return if (s < 0) 0 else if (s > 255) 255 else s
        }

        /** Pillow's precompute_coeffs + normalize_coeffs_8bpc for BILINEAR. */
        private fun precompute(inSize: Int, outSize: Int): Triple<IntArray, IntArray, Int> {
            val scale = inSize.toDouble() / outSize
            val filterscale = max(scale, 1.0)
            val support = 1.0 * filterscale
            val ksize = ceil(support).toInt() * 2 + 1
            val bounds = IntArray(outSize * 2)
            val kk = IntArray(outSize * ksize)
            val pre = DoubleArray(ksize)
            for (xx in 0 until outSize) {
                val center = (xx + 0.5) * scale
                val ss = 1.0 / filterscale
                var xmin = (center - support + 0.5).toInt()
                if (xmin < 0) xmin = 0
                var xmax = (center + support + 0.5).toInt()
                if (xmax > inSize) xmax = inSize
                xmax -= xmin
                var ww = 0.0
                for (x in 0 until xmax) {
                    val t = (x + xmin - center + 0.5) * ss
                    val v = if (t > -1.0 && t < 1.0) 1.0 - kotlin.math.abs(t) else 0.0
                    pre[x] = v
                    ww += v
                }
                for (x in 0 until xmax) {
                    val v = if (ww != 0.0) pre[x] / ww else pre[x]
                    kk[xx * ksize + x] = (v * (1 shl PRECISION_BITS) + if (v < 0) -0.5 else 0.5).toInt()
                }
                bounds[2 * xx] = xmin
                bounds[2 * xx + 1] = xmax
            }
            return Triple(bounds, kk, ksize)
        }
    }
}
