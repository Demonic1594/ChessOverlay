package io.github.chessoverlay.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * An engine the app can start. `id` is stable across launches.
 * `alternative`: another build of it to start if `file` cannot run here.
 */
data class EngineInfo(val id: String, val title: String, val file: File, val kind: Kind, val alternative: File? = null) {
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

    /**
     * The engines in the APK. Smotherfish comes in two builds: the faster
     * one (Armv8.2 dot product, most phones from about 2019 on) where the CPU
     * has those instructions, the plain one elsewhere. Both are one engine
     * to the user, with one id and one set of options; the plain build is
     * the alternative if the faster one does not run after all.
     */
    fun bundled(): List<EngineInfo> {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.associateBy { it.name }
        val plain = files[SMOTHERFISH]
        val fast = files[SMOTHERFISH_DOTPROD]?.takeIf { CpuFeatures.hasDotProd() }
        val out = ArrayList<EngineInfo>()
        if (fast != null) out += EngineInfo("bundled:$SMOTHERFISH", "Smotherfish (built in)", fast, EngineInfo.Kind.BUNDLED, plain)
        else if (plain != null) out += EngineInfo("bundled:$SMOTHERFISH", "Smotherfish (built in)", plain, EngineInfo.Kind.BUNDLED)
        return out
    }

    fun imported(): List<EngineInfo> {
        val dir = File(engineDir, "user")
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.name }
            .map { EngineInfo("user:${it.name}", it.name, it, EngineInfo.Kind.IMPORTED) }
    }

    /** Copy a user-picked binary into the app and mark it executable. */
    fun import(uri: Uri, displayName: String): EngineInfo {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._+-]"), "_").ifEmpty { "engine" }
        val dest = File(File(engineDir, "user").apply { mkdirs() }, safe)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot read $uri" }
            copyAtomically(input, dest)
        }
        return EngineInfo("user:${dest.name}", dest.name, dest, EngineInfo.Kind.IMPORTED)
    }

    fun delete(e: EngineInfo) {
        if (e.kind == EngineInfo.Kind.IMPORTED || e.kind == EngineInfo.Kind.OPEN_EXCHANGE) {
            e.file.delete()
            File(e.file.path + ".stamp").delete()
        }
    }

    /**
     * Copies to a temporary file and renames it into place: an interrupted
     * copy (the app killed, the disk full) never leaves half an engine
     * that would then be started.
     */
    private fun copyAtomically(input: java.io.InputStream, dest: File) {
        val tmp = File(dest.path + ".tmp")
        try {
            tmp.outputStream().use { input.copyTo(it) }
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw java.io.IOException("could not move ${tmp.name} into place")
            }
        } finally {
            tmp.delete()
        }
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
                // Copied again when the engine app was updated (a new engine version), not only once.
                @Suppress("DEPRECATION")
                val stamp = runCatching { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                for ((title, fileName, targets) in parseEngineList(res.getXml(id))) {
                    if (abi !in targets) continue
                    val dest = File(File(engineDir, "oex").apply { mkdirs() }, "${ai.packageName}_$fileName")
                    val stampFile = File(dest.path + ".stamp")
                    val copied = runCatching { stampFile.readText().toLong() }.getOrNull()
                    if (!dest.exists() || copied != stamp) {
                        val uri = Uri.parse("content://$authority/$fileName")
                        ctx.contentResolver.openInputStream(uri)?.use { input -> copyAtomically(input, dest) } ?: continue
                        stampFile.writeText(stamp.toString())
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
        /** The bundled builds: plain Armv8 and Armv8.2 with dot product (jniLibs, so installed executable). */
        const val SMOTHERFISH = "libsmotherfish.so"
        const val SMOTHERFISH_DOTPROD = "libsmotherfish_dotprod.so"
    }
}
