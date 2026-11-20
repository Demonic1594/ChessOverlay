package io.github.chessoverlay.overlay

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import io.github.chessoverlay.vision.Img

/**
 * The screen as images, from a MediaProjection. Android delivers a frame
 * only when something on screen changes; the newest one is kept, with its
 * arrival time, for grab() and pixels().
 */
class ScreenSource(
    projection: MediaProjection,
    val width: Int,
    val height: Int,
    densityDpi: Int,
    private val handler: Handler,
) : AutoCloseable {
    private val lock = Object()
    private var latest: Image? = null
    @Volatile var latestTime = 0L
        private set

    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    private val display: VirtualDisplay

    init {
        reader.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            val t = SystemClock.uptimeMillis()
            synchronized(lock) { latest?.close(); latest = img; latestTime = t }
        }, handler)
        display = projection.createVirtualDisplay("chessoverlay", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
    }

    /** The newest frame as an RGB image, or null if none has arrived yet. */
    fun grab(): Img? = crop(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)?.img

    /**
     * RGB of the newest frame at the given screen points (-1 off screen), or
     * null before the first frame. Reads only those pixels, so polling a
     * board's 1600 points is cheap.
     */
    fun pixels(xs: IntArray, ys: IntArray): IntArray? = synchronized(lock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride; val rs = plane.rowStride
        IntArray(xs.size) { i ->
            val x = xs[i]; val y = ys[i]
            if (x in 0 until image.width && y in 0 until image.height) {
                val o = y * rs + x * ps
                ((buf.get(o).toInt() and 0xff) shl 16) or ((buf.get(o + 1).toInt() and 0xff) shl 8) or
                    (buf.get(o + 2).toInt() and 0xff)
            } else -1
        }
    }

    /** Part of the screen: `img`'s (0, 0) is screen pixel (ox, oy). */
    class Crop(val img: Img, val ox: Int, val oy: Int)

    /** The rectangle (x0, y0)-(x1, y1) of the newest frame, clipped to the screen. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Crop? = synchronized(lock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val cx0 = x0.coerceIn(0, image.width); val cy0 = y0.coerceIn(0, image.height)
        val cx1 = x1.coerceIn(cx0, image.width); val cy1 = y1.coerceIn(cy0, image.height)
        val w = cx1 - cx0; val h = cy1 - cy0
        if (w == 0 || h == 0) return null
        val ps = plane.pixelStride; val rs = plane.rowStride
        val out = Img(w, h)
        val row = ByteArray(w * ps)
        for (y in 0 until h) {
            buf.position((cy0 + y) * rs + cx0 * ps)
            buf.get(row, 0, minOf(row.size, buf.remaining()))
            var o = 0
            val base = y * w
            for (x in 0 until w) {
                out.px[base + x] = ((row[o].toInt() and 0xff) shl 16) or
                    ((row[o + 1].toInt() and 0xff) shl 8) or (row[o + 2].toInt() and 0xff)
                o += ps
            }
        }
        Crop(out, cx0, cy0)
    }

    override fun close() {
        display.release()
        synchronized(lock) { latest?.close(); latest = null }
        reader.close()
    }
}
