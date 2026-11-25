package io.github.chessoverlay

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * New versions, from the project's GitHub Releases: at most once a day the
 * latest release is looked up (nothing about the user or the phone is
 * sent), and when it is newer than this app the user is offered it. The
 * APK is downloaded, checked against the release's own .sha256 file, and
 * only then handed to Android's installer.
 */
object Updates {
    const val LATEST = "https://api.github.com/repos/Demonic1594/ChessOverlay/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    class Release(val tag: String, val page: String, val apkUrl: String, val apkSize: Long, val shaUrl: String?)

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    /** Due: the user allows it and the last look was a day ago or more. */
    fun due(prefs: Prefs, now: Long = System.currentTimeMillis()) = prefs.updateCheck && now - prefs.lastUpdateCheck >= DAY_MS

    /** The latest release, if newer than this app; null otherwise. Blocking, network. */
    fun latestNewer(ctx: Context): Release? {
        val r = latest() ?: return null
        return r.takeIf { Versions.newer(it.tag, currentVersion(ctx)) }
    }

    fun latest(): Release? {
        val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ChessOverlay-update-check")
        }
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.getJSONArray("assets")
            var apk: JSONObject? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (name.endsWith(".apk")) apk = a
                if (name.endsWith(".apk.sha256")) sha = a.getString("browser_download_url")
            }
            val a = apk ?: return null
            return Release(j.getString("tag_name"), j.getString("html_url"), a.getString("browser_download_url"), a.optLong("size", -1), sha)
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads the release's APK into the app's cache and checks it against
     * its .sha256; returns the file. progress(done, total). Blocking.
     */
    fun download(ctx: Context, r: Release, progress: (Long, Long) -> Unit): File {
        val shaUrl = requireNotNull(r.shaUrl) { "the release has no checksum file" }
        val want = URL(shaUrl).openStream().bufferedReader().use { it.readText() }.trim().substringBefore(' ').lowercase()
        require(want.length == 64) { "the release's checksum file is not a SHA-256" }
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val tmp = File(dir, "update.apk.tmp")
        val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
        }
        val sha = MessageDigest.getInstance("SHA-256")
        try {
            check(c.responseCode == 200) { "download failed: HTTP ${c.responseCode}" }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sha.update(buf, 0, n); out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (got != want) {
            tmp.delete()
            error("the download is not the release's file (SHA-256 $got, expected $want); nothing was installed")
        }
        val apk = File(dir, "ChessOverlay-${r.tag}.apk")
        check(tmp.renameTo(apk)) { "could not keep the download" }
        return apk
    }

    /** Hands a checked APK to Android's installer (it asks the user). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Android lets this app hand APKs to the installer (else the user is sent to allow it). */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()
}
