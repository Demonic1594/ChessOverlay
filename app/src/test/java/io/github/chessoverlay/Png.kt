package io.github.chessoverlay

import io.github.chessoverlay.vision.Img
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Reads the PNG files of the board benchmark (written by Chrome): 8-bit RGB
 * or RGBA, not interlaced. Written out here because an Android unit test
 * cannot use java.awt's image reader.
 */
object Png {
    fun read(bytes: ByteArray): Img {
        var p = 8
        var w = 0
        var h = 0
        var bpp = 0
        val idat = ByteArrayOutputStream()
        while (p + 12 <= bytes.size) {
            val len = be(bytes, p)
            val kind = String(bytes, p + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(p + 8, p + 8 + len)
            when (kind) {
                "IHDR" -> {
                    w = be(data, 0)
                    h = be(data, 4)
                    require(data[8].toInt() == 8 && data[12].toInt() == 0) { "8 bits a sample, not interlaced" }
                    bpp = when (data[9].toInt()) { 2 -> 3; 6 -> 4; else -> error("PNG colour type ${data[9]}") }
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
            p += 12 + len
        }
        val raw = Inflater().let { inf ->
            inf.setInput(idat.toByteArray())
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        val stride = w * bpp
        val img = Img(w, h)
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        var q = 0
        for (y in 0 until h) {
            val filter = raw[q++].toInt() and 0xff
            System.arraycopy(raw, q, cur, 0, stride)
            q += stride
            for (i in 0 until stride) {
                // The predictors work on bytes as 0..255, not as signed bytes.
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xff else 0
                val b = prev[i].toInt() and 0xff
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xff else 0
                val v = cur[i].toInt() and 0xff
                cur[i] = when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + ((a + b) shr 1)
                    4 -> v + paeth(a, b, c)
                    else -> error("PNG filter $filter")
                }.toByte()
            }
            for (x in 0 until w) {
                val o = x * bpp
                img.px[y * w + x] = ((cur[o].toInt() and 0xff) shl 16) or ((cur[o + 1].toInt() and 0xff) shl 8) or (cur[o + 2].toInt() and 0xff)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return img
    }

    private fun be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
        ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val (pa, pb, pc) = Triple(abs(p - a), abs(p - b), abs(p - c))
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
