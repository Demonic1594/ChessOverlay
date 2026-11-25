package io.github.chessoverlay

/**
 * Release versions: "v0.2.0" (a tag) or "0.2.0" (an app's versionName,
 * "0.2.0+57" for a CI build of run 57 that is not a release).
 */
object Versions {
    /** The numbers of a version, "v" and any "+build" left off; null if it is not one. */
    fun parse(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").substringBefore('+').substringBefore('-')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        return parts.map { it.toInt() }
    }

    /** Whether release `tag` is newer than the app's `current` version (false when either is not a version). */
    fun newer(tag: String, current: String): Boolean {
        val a = parse(tag) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
