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

    fun save(ctx: Context, img: Img, why: String) {
        val now = System.currentTimeMillis()
        if (now - lastSave < MIN_INTERVAL_MS) return
        lastSave = now
        try {
            val scale = minOf(1f, 1080f / img.w)
            val w = (img.w * scale).toInt().coerceAtLeast(1)
            val h = (img.h * scale).toInt().coerceAtLeast(1)
            val argb = IntArray(img.px.size) { img.px[it] or (0xFF shl 24) }
            val full = Bitmap.createBitmap(argb, img.w, img.h, Bitmap.Config.ARGB_8888)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(full, w, h, true).also { full.recycle() } else full
            val f = file(ctx)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            DebugLog.i("DebugCapture", "saved the screen of a failed read ($why), ${img.w}x${img.h}")
        } catch (e: Throwable) {
            DebugLog.e("DebugCapture", "could not save the screen", e)
        }
    }
}
