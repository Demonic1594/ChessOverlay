package io.github.chessoverlay.engine

import java.io.File

/**
 * Whether this phone's CPU has the Armv8.2 dot-product instructions that
 * the faster engine build uses (asimddp). That build stops with "illegal
 * instruction" on a CPU without them, so it is only started where they are
 * known to be there. The kernel says so in the auxiliary vector (HWCAP)
 * and in /proc/cpuinfo; either is enough.
 */
object CpuFeatures {
    private const val AT_HWCAP = 16L
    private const val HWCAP_ASIMDDP = 1L shl 20

    fun hasDotProd(): Boolean = isArm64() && hasDotProd(
        runCatching { File("/proc/self/auxv").readBytes() }.getOrNull(),
        runCatching { File("/proc/cpuinfo").readText() }.getOrNull(),
    )

    /** HWCAP bits mean other things on other CPUs (bit 20 is not the dot product on x86). */
    private fun isArm64(): Boolean = System.getProperty("os.arch")?.lowercase() in setOf("aarch64", "arm64")

    /** From the raw auxiliary vector (64-bit pairs, little-endian) and the cpuinfo text. */
    fun hasDotProd(auxv: ByteArray?, cpuinfo: String?): Boolean {
        if (auxv != null) {
            val b = java.nio.ByteBuffer.wrap(auxv).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= 16) {
                val type = b.long
                val value = b.long
                if (type == 0L) break
                if (type == AT_HWCAP) return value and HWCAP_ASIMDDP != 0L
            }
        }
        // "Features : fp asimd ... asimddp ..." for every core; all of them must have it.
        val lines = cpuinfo?.lines()?.filter { it.trimStart().startsWith("Features") } ?: return false
        return lines.isNotEmpty() && lines.all { l -> "asimddp" in l.substringAfter(':').trim().split(Regex("\\s+")) }
    }
}
