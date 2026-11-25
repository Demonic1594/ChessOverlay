package io.github.chessoverlay

import io.github.chessoverlay.engine.CpuFeatures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CpuFeaturesTest {
    private fun auxv(vararg pairs: Long): ByteArray =
        ByteBuffer.allocate(8 * pairs.size).order(ByteOrder.LITTLE_ENDIAN).apply { pairs.forEach { putLong(it) } }.array()

    @Test fun fromTheAuxiliaryVector() {
        assertTrue(CpuFeatures.hasDotProd(auxv(6, 4096, 16, (1L shl 20) or 0xff, 0, 0), null))
        assertFalse("HWCAP without asimddp", CpuFeatures.hasDotProd(auxv(16, 0xff, 0, 0), "Features : asimddp"))
    }

    @Test fun fromCpuinfo() {
        val a55 = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
        val a53 = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n"
        assertTrue(CpuFeatures.hasDotProd(null, a55 + a55))
        assertFalse(CpuFeatures.hasDotProd(null, a53))
        assertFalse("a core without it", CpuFeatures.hasDotProd(null, a55 + a53))
        assertFalse(CpuFeatures.hasDotProd(null, null))
        assertFalse("no Features line", CpuFeatures.hasDotProd(null, "processor : 0\n"))
    }
}
