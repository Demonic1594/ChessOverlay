package io.github.chessoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.AnalysisUpdate
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.EngineRegistry
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.Analyst
import io.github.chessoverlay.overlay.DrawView
import io.github.chessoverlay.overlay.MoveMark
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Runs on a device or emulator (CI: an x86_64 emulator with an x86_64 build
 * of Smotherfish bundled). Recognition tests need the models pushed to
 * /data/local/tmp/models (the workflow does that).
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val models = File("/data/local/tmp/models")
    private val out = File(ctx.getExternalFilesDir(null), "test-out").apply { mkdirs() }

    private fun image(name: String): Img {
        val bytes = GZIPInputStream(ctx.assets.open("vision/$name.rgb.gz")).readBytes()
        val nl = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, nl).split(' ').map { it.toInt() }
        val img = Img(w, h)
        for (i in 0 until w * h) {
            val o = nl + 1 + 3 * i
            img.px[i] = ((bytes[o].toInt() and 0xff) shl 16) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() and 0xff)
        }
        return img
    }

    @Test fun mainScreenStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> assertTrue("main screen finished at once", !a.isFinishing) }
        }
    }

    @Test fun bundledEngineAnalysesOnAndroid() {
        val reg = EngineRegistry(ctx)
        val engine = reg.bundled().firstOrNull()
        assumeTrue("no bundled engine for this ABI", engine != null)
        val done = CountDownLatch(1)
        var last: AnalysisUpdate? = null
        val statuses = mutableListOf<String>()
        EngineHost(engine!!.file, reg.workDir, emptyMap(), onUpdate = { u ->
            last = u
            if (u.finished) done.countDown()
        }, onStatus = { statuses.add(it) }).use { host ->
            host.analyse("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", 3, 2000)
            assertTrue("no result; statuses $statuses; log:\n${DebugLog.dump().takeLast(2000)}", done.await(30, TimeUnit.SECONDS))
        }
        val lines = last!!.lines
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.depth > 5 && it.pv.isNotEmpty() })
    }

    @Test fun recognitionAndAnalysisOnAndroid() {
        assumeTrue("models not pushed to $models", File(models, "board.onnx").exists())
        val reg = EngineRegistry(ctx)
        val engine = reg.bundled().firstOrNull()
        assumeTrue("no bundled engine for this ABI", engine != null)
        Recognizer(File(models, "board.onnx"), File(models, "locator.onnx")).use { rec ->
            val t0 = System.currentTimeMillis()
            val r = rec.recognise(image("screen1"))
            val ms = System.currentTimeMillis() - t0
            DebugLog.i("DeviceTest", "recognition took $ms ms")
            assertEquals("8/8/8/4p1K1/2k1P3/8/8/8", r.placement)

            // The whole pipeline: screen image -> Analyst -> engine lines.
            val got = CountDownLatch(1)
            var state: OverlayState? = null
            lateinit var a: Analyst
            EngineHost(engine!!.file, reg.workDir, emptyMap(), onUpdate = { a.onEngine(it) }, onStatus = {}).use { host ->
                a = Analyst(rec, host) { s ->
                    state = s
                    if (s.lines.isNotEmpty() && s.lines[0].depth >= 10) got.countDown()
                }.apply { thinkMs = 3000 }
                assertTrue(a.look(image("screen1"), auto = false, forceLocate = true))
                assertTrue("no engine lines; state $state", got.await(30, TimeUnit.SECONDS))
            }
            File(out, "timing.txt").writeText("recognition ${ms} ms\n")
        }
    }

    /** Renders the overlay for a known position into a PNG (pulled by CI for review). */
    @Test fun overlayRenders() {
        val b = Board.fromPlacement("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R")
        val size = 800
        val corners = listOf(floatArrayOf(40f, 300f), floatArrayOf(40f + size, 300f),
            floatArrayOf(40f + size, 300f + size), floatArrayOf(40f, 300f + size))
        val lines = listOf(
            PvLine(1, 22, 35, null, listOf("f1b5", "a7a6", "b5a4", "g8f6", "e1g1", "f8e7")),
            PvLine(2, 22, 28, null, listOf("d2d4", "e5d4", "f3d4", "g8f6")),
            PvLine(3, 22, 20, null, listOf("f1c4", "f8c5", "c2c3", "g8f6")),
        )
        val state = OverlayState(status = "Depth 22", corners = corners, board = b, whiteToMove = true,
            lines = lines, playedMove = "b8c6", lastMove = MoveMark(Board.squareOf("c6"), "✓", 0xFF2E9E44.toInt()))
        val bmp = Bitmap.createBitmap(880, 1800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // A plain board underneath so the drawing can be judged.
        val sq = size / 8f
        val paint = android.graphics.Paint()
        for (r in 0 until 8) for (f in 0 until 8) {
            paint.color = if ((r + f) % 2 == 0) Color.rgb(238, 216, 181) else Color.rgb(181, 136, 99)
            c.drawRect(40 + f * sq, 300 + r * sq, 40 + (f + 1) * sq, 300 + (r + 1) * sq, paint)
            val p = b[(7 - r) * 8 + f]
            if (p != '.') {
                paint.color = if (p.isUpperCase()) Color.WHITE else Color.BLACK
                paint.textSize = sq * 0.6f
                c.drawText(p.toString(), 40 + f * sq + sq * 0.3f, 300 + r * sq + sq * 0.7f, paint)
            }
        }
        val prefs = Prefs(ctx)
        val v = DrawView(ctx, prefs)
        v.state = state
        v.measure(View.MeasureSpec.makeMeasureSpec(880, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 880, 1800)
        v.draw(c)
        File(out, "overlay.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
