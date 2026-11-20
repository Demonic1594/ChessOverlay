package io.github.chessoverlay.vision

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The recognition models (about 170 MB) are downloaded once, on first use,
 * from the chessimg2pos release, the same file the Python package uses.
 */
class ModelStore(filesDir: File) {
    val dir = File(filesDir, "models/chessimg2pos-$VERSION")
    val board = File(dir, "board.onnx")
    val locator = File(dir, "locator.onnx")

    val ready: Boolean get() = board.length() > 0 && locator.length() > 0

    /** Download and unpack; progress(bytesDone, bytesTotal or -1). Blocking. */
    fun download(progress: (Long, Long) -> Unit) {
        val tmp = File(dir.parentFile, "download.tmp").apply { parentFile?.mkdirs(); deleteRecursively(); mkdirs() }
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
            dir.deleteRecursively()
            check(tmp.renameTo(dir)) { "could not move the models into place" }
        } finally {
            tmp.deleteRecursively()
        }
    }

    companion object {
        const val VERSION = "0.2.0"
        const val URL_ZIP = "https://github.com/mdicio/chessimg2pos/releases/download/v$VERSION/chessimg2pos-v2-onnx.zip"
    }
}
