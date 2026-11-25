package io.github.chessoverlay.engine

import java.io.File
import java.security.MessageDigest

/**
 * The options an engine declares, kept after it was started once to read
 * them: the options screen then opens at once instead of starting the
 * engine (seconds for a big network). Kept per engine file, keyed by its
 * path, size and time, so a new version of the file is read afresh.
 */
class OptionsCache(private val dir: File) {
    class Entry(val name: String, val options: List<UciOption>)

    private fun file(engine: File): File {
        val key = "${engine.absolutePath}|${engine.length()}|${engine.lastModified()}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.txt")
    }

    fun get(engine: File): Entry? = try {
        val lines = file(engine).takeIf { it.exists() }?.readLines() ?: return null
        val name = lines.firstOrNull()?.removePrefix("id name ") ?: return null
        Entry(name, lines.drop(1).mapNotNull { UciOption.parse(it) })
    } catch (_: Exception) {
        null
    }

    fun put(engine: File, name: String, options: List<UciOption>) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${file(engine).name}.tmp")
            tmp.writeText((listOf("id name $name") + options.map { it.toLine() }).joinToString("\n"))
            tmp.renameTo(file(engine))
        } catch (_: Exception) {
        }
    }
}
