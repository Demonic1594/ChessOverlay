package io.github.chessoverlay

import io.github.chessoverlay.vision.ModelZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class ModelZipTest {
    /** The SHA-256 covers every byte of the zip (its directory too), and the models are unpacked. */
    @Test fun theChecksumIsTheWholeFiles() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for (n in listOf("board.onnx", "locator.onnx", "README.txt")) {
                    z.putNextEntry(ZipEntry(n)); z.write(ByteArray(100_000) { (it * 7 + n.length).toByte() }); z.closeEntry()
                }
            }
        }.toByteArray()
        val dir = createTempDirectory("zip").toFile()
        val got = ModelZip.unpack(bytes.inputStream(), dir)
        val want = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(want, got)
        assertTrue(File(dir, "board.onnx").length() == 100_000L && File(dir, "locator.onnx").length() == 100_000L)
        assertTrue("only the models", !File(dir, "README.txt").exists())
        // The real release file, when given (MODEL_ZIP): its pinned checksum.
        System.getenv("MODEL_ZIP")?.let { f ->
            val d2 = createTempDirectory("zip2").toFile()
            assertEquals("920df90f3192cdcb8cd2e167ae1c54124d8cf54263ddf4b894b43534420fdb05", ModelZip.unpack(File(f).inputStream(), d2))
            d2.deleteRecursively()
        }
        dir.deleteRecursively()
    }
}
