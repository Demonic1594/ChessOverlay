package io.github.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import io.github.chessoverlay.vision.Img
import java.io.File

/**
 * The last screen on which a board was read wrongly (twice in a row: a
 * misread, not a piece in flight), kept so it can be shared from the app
 * (the tools: Share the last misread screen) and the problem seen as the app
 * saw it. A screen without a board is never kept: with the overlay on, that
 * is whatever else the user opened. At most one save every 20 seconds.
 */
object DebugCapture {
    private const val MIN_INTERVAL_MS = 20_000L
    @Volatile private var lastSave = 0L

    fun file(ctx: Context) = File(File(ctx.filesDir, "debug"), "misread.jpg")

    /**
     * Keeps a picture of a failed read, at most every MIN_INTERVAL_MS. The
     * reading thread only takes a small copy (`img` is reused by the next
     * capture); scaling and the JPEG are made on a thread of their own, so a
     * fast game's reads do not wait for it.
     */
    fun save(ctx: Context, img: Img, why: String) {
        val now = System.currentTimeMillis()
        if (now - lastSave < MIN_INTERVAL_MS) return
        lastSave = now
        val step = maxOf(1, (img.w + MAX_W - 1) / MAX_W)
        val w = img.w / step
        val h = img.h / step
        if (w < 1 || h < 1) return
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * step * img.w
            for (x in 0 until w) argb[y * w + x] = img.px[row + x * step] or (0xFF shl 24)
        }
        val app = ctx.applicationContext
        Thread({
            try {
                val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
                val f = file(app)
                f.parentFile?.mkdirs()
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                DebugLog.i("DebugCapture", "saved the screen of a failed read ($why), ${img.w}x${img.h} as ${w}x$h")
            } catch (e: Throwable) {
                DebugLog.e("DebugCapture", "could not save the screen", e)
            }
        }, "debug-capture").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** The saved picture's width at most (about a phone's; step-sampled). */
    private const val MAX_W = 1080
}
