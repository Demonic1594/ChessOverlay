package io.github.chessoverlay.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** An engine the app can start. `id` is stable across launches. */
data class EngineInfo(val id: String, val title: String, val file: File, val kind: Kind) {
    enum class Kind { BUNDLED, OPEN_EXCHANGE, IMPORTED }
}

/**
 * Finds engines:
 *  - bundled: lib*.so engines shipped in the APK (installed executable in
 *    nativeLibraryDir), e.g. libsmotherfish.so;
 *  - Open Exchange: engine apps that declare the "intent.chess.provider.ENGINE"
 *    activity (the protocol DroidFish and other chess GUIs use); their binary
 *    is copied in through their content provider;
 *  - imported: binaries the user picked, copied into the app's files.
 * Running copied binaries needs targetSdk <= 28 (see app/build.gradle.kts).
 */
class EngineRegistry(private val ctx: Context) {

    private val engineDir = File(ctx.filesDir, "engines").apply { mkdirs() }
    val workDir: File = File(ctx.filesDir, "enginework").apply { mkdirs() }

    fun all(): List<EngineInfo> = bundled() + openExchange() + imported()

    fun bundled(): List<EngineInfo> {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        return (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("lib") && it.name.endsWith(".so") && it.name in BUNDLED_TITLES }
            .map { EngineInfo("bundled:${it.name}", BUNDLED_TITLES.getValue(it.name), it, EngineInfo.Kind.BUNDLED) }
    }

    fun imported(): List<EngineInfo> {
        val dir = File(engineDir, "user")
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }
            .map { EngineInfo("user:${it.name}", it.name, it, EngineInfo.Kind.IMPORTED) }
    }

    /** Copy a user-picked binary into the app and mark it executable. */
    fun import(uri: Uri, displayName: String): EngineInfo {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._+-]"), "_").ifEmpty { "engine" }
        val dest = File(File(engineDir, "user").apply { mkdirs() }, safe)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot read $uri" }
            dest.outputStream().use { input.copyTo(it) }
        }
        dest.setExecutable(true, false)
        return EngineInfo("user:${dest.name}", dest.name, dest, EngineInfo.Kind.IMPORTED)
    }

    fun delete(e: EngineInfo) {
        if (e.kind == EngineInfo.Kind.IMPORTED || e.kind == EngineInfo.Kind.OPEN_EXCHANGE) e.file.delete()
    }

    /** Engines offered by installed Open Exchange engine apps, copied in. */
    fun openExchange(): List<EngineInfo> {
        val result = mutableListOf<EngineInfo>()
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI
        val infos = try {
            pm.queryIntentActivities(Intent(OEX_ACTION), PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            val authority = ai.metaData?.getString("chess.provider.engine.authority") ?: continue
            try {
                val res = pm.getResourcesForApplication(ai.applicationInfo)
                // The Open Exchange protocol names the engine list's resource: it can only be looked up by name.
                @Suppress("DiscouragedApi")
                val id = res.getIdentifier("enginelist", "xml", ai.packageName)
                if (id == 0) continue
                for ((title, fileName, targets) in parseEngineList(res.getXml(id))) {
                    if (abi !in targets) continue
                    val dest = File(File(engineDir, "oex").apply { mkdirs() }, "${ai.packageName}_$fileName")
                    if (!dest.exists()) {
                        val uri = Uri.parse("content://$authority/$fileName")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        } ?: continue
                        dest.setExecutable(true, false)
                    }
                    result.add(EngineInfo("oex:${ai.packageName}/$fileName", title, dest, EngineInfo.Kind.OPEN_EXCHANGE))
                }
            } catch (_: Exception) {
                // A broken engine app should not hide the others.
            }
        }
        return result
    }

    private fun parseEngineList(p: XmlResourceParser): List<Triple<String, String, List<String>>> {
        val out = mutableListOf<Triple<String, String, List<String>>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name.equals("engine", ignoreCase = true)) {
                val name = p.getAttributeValue(null, "name") ?: "engine"
                val file = p.getAttributeValue(null, "filename")
                val target = p.getAttributeValue(null, "target") ?: ""
                if (file != null) out.add(Triple(name, file, target.split('|')))
            }
            ev = p.next()
        }
        return out
    }

    companion object {
        const val OEX_ACTION = "intent.chess.provider.ENGINE"
        /** Engines shipped in the APK (file name in jniLibs -> title). */
        val BUNDLED_TITLES = mapOf("libsmotherfish.so" to "Smotherfish (built in)")
    }
}
