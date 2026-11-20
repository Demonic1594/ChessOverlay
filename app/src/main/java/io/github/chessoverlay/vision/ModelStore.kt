package io.github.chessoverlay.vision

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The recognition models.
 *
 * Release builds carry them in the APK (assets/models: the board model
 * quantized to int8, see tools/quantize_models.py); they are copied out
 * once per installed version, since ONNX Runtime loads files. A build
 * without them (a local build) downloads the originals once, about 170 MB,
 * from the chessimg2pos release the Python package uses.
 */
class ModelStore(private val ctx: Context) {
    private val root = File(ctx.filesDir, "models")
    private val downloaded = File(root, "chessimg2pos-$VERSION")
    private val bundled: File by lazy {
        val stamp = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        } catch (_: Exception) { 0L }
        File(root, "bundled-$stamp")
    }

    /** The APK carries the models. */
    val isBundled: Boolean by lazy {
        try { ctx.assets.list("models")?.toSet()?.containsAll(listOf("board.onnx", "locator.onnx")) == true } catch (_: Exception) { false }
    }

    private val dir: File get() = if (isBundled) bundled else downloaded
    val board: File get() = File(dir, "board.onnx")
    val locator: File get() = File(dir, "locator.onnx")

    /** Usable now, or after prepare() (bundled models only need copying). */
    val ready: Boolean get() = isBundled || (board.length() > 0 && locator.length() > 0)

    /** Copy bundled models out of the APK if not done for this version; blocking. */
    fun prepare() {
        if (!isBundled || (board.length() > 0 && locator.length() > 0)) return
        val tmp = File(root, "bundled.tmp").apply { deleteRecursively(); mkdirs() }
        for (name in listOf("board.onnx", "locator.onnx")) {
            ctx.assets.open("models/$name").use { input -> File(tmp, name).outputStream().use { input.copyTo(it) } }
        }
        // Older copies (earlier versions of the app) go.
        root.listFiles()?.filter { it.name.startsWith("bundled-") }?.forEach { it.deleteRecursively() }
        check(tmp.renameTo(bundled)) { "could not move the models into place" }
    }

    /** Download and unpack; progress(bytesDone, bytesTotal or -1). Blocking. */
    fun download(progress: (Long, Long) -> Unit) {
        val tmp = File(root, "download.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val conn = (URL(URL_ZIP).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            conn.connect()
            check(conn.responseCode == 200) { "download failed: HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            var done = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { done += n; progress(done, total) }
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = File(e.name).name
                    if (!e.isDirectory && name.endsWith(".onnx")) {
                        File(tmp, name).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            check(File(tmp, "board.onnx").length() > 0 && File(tmp, "locator.onnx").length() > 0) {
                "board.onnx / locator.onnx not found in the download"
            }
            downloaded.deleteRecursively()
            check(tmp.renameTo(downloaded)) { "could not move the models into place" }
        } finally {
            tmp.deleteRecursively()
        }
    }

    companion object {
        const val VERSION = "0.2.0"
        const val URL_ZIP = "https://github.com/mdicio/chessimg2pos/releases/download/v$VERSION/chessimg2pos-v2-onnx.zip"
    }
}
