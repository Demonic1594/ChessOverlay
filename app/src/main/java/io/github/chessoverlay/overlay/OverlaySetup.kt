package io.github.chessoverlay.overlay

import android.content.Context
import android.os.SystemClock
import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.Prefs
import io.github.chessoverlay.engine.EngineCheck
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.vision.ModelStore
import io.github.chessoverlay.vision.RecognizerMode
import io.github.chessoverlay.vision.Tuner

/** What the overlay decides once as it starts: how to run the models on this phone, and the engine's hash. */
object OverlaySetup {
    private const val TAG = "Overlay"

    /**
     * How the models are run on this phone (see Tuner): measured once, the
     * first time the overlay starts, with the engine searching as it will be
     * then, and kept. When measuring fails, the default is used.
     */
    fun howToRead(ctx: Context, prefs: Prefs, models: ModelStore, host: EngineHost, status: (String) -> Unit): RecognizerMode {
        var saved = RecognizerMode.parse(prefs.recognizerMode)
        prefs.recognizerInUse?.let { died ->
            // Set when the overlay starts with a way that is not the default, cleared when it stops: it is
            // still set, so the app died while using it. Not that way again, or it would die at every start.
            DebugLog.w(TAG, "the app went away while reading with $died; ${RecognizerMode.DEFAULT} is used from now on")
            saved = RecognizerMode.DEFAULT
            prefs.recognizerMode = null
        }
        prefs.recognizerInUse = null
        if (prefs.recognizerTuned) return saved
        status("Measuring how to read fastest (once)…")
        try {
            val sample = Tuner.sample(ctx.assets.open(Tuner.SAMPLE))
            host.start()
            host.analyse(EngineCheck.FEN, 3, 0)
            SystemClock.sleep(800)   // until the engine searches
            val result = Tuner.tune(models.board, models.locator, sample, progress = { i, n ->
                status("Measuring how to read fastest (once): $i of $n…")
            })
            prefs.recognizerMode = result.chosen.toString()
            prefs.recognizerTuned = true
            DebugLog.i(TAG, "recognition measured:\n${result.report()}")
            return result.chosen
        } catch (e: Throwable) {
            DebugLog.e(TAG, "could not measure recognition; $saved is used", e)
            return saved
        } finally {
            host.stop()
        }
    }

    /**
     * The engine's hash unless the user set one: 64 MB is plenty for short
     * searches, more helps long ones, and a big hash on a small phone gets
     * the engine killed when memory runs low.
     */
    fun hashForPhone(ctx: Context): Int {
        val mem = runCatching {
            android.app.ActivityManager.MemoryInfo().also {
                ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
            }.totalMem
        }.getOrDefault(0L)
        val gb = mem / (1L shl 30)
        return when {
            gb >= 6 -> 256
            gb >= 3 -> 128
            else -> 64
        }
    }
}
