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
 * only when something on screen changes; `onFrame` is called (on `handler`)
 * with the frame's arrival time, and the newest frame is kept for grab().
 */
class ScreenSource(
    projection: MediaProjection,
    val width: Int,
    val height: Int,
    densityDpi: Int,
    private val handler: Handler,
) : AutoCloseable {
    var onFrame: ((Long) -> Unit)? = null

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
            onFrame?.invoke(t)
        }, handler)
        display = projection.createVirtualDisplay("chessoverlay", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
    }

    /** The newest frame as an RGB image, or null if none has arrived yet. */
    fun grab(): Img? = synchronized(lock) {
        val image = latest ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width; val h = image.height
        val ps = plane.pixelStride; val rs = plane.rowStride
        val out = Img(w, h)
        val row = ByteArray(rs)
        for (y in 0 until h) {
            buf.position(y * rs)
            buf.get(row, 0, minOf(rs, buf.remaining()))
            var o = 0
            val base = y * w
            for (x in 0 until w) {
                out.px[base + x] = ((row[o].toInt() and 0xff) shl 16) or
                    ((row[o + 1].toInt() and 0xff) shl 8) or (row[o + 2].toInt() and 0xff)
                o += ps
            }
        }
        out
    }

    override fun close() {
        onFrame = null
        display.release()
        synchronized(lock) { latest?.close(); latest = null }
        reader.close()
    }
}
