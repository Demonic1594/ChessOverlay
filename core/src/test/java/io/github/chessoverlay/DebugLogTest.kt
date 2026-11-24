package io.github.chessoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DebugLogTest {
    @Test fun aMessageThatKeepsComingIsWrittenOnceAndCounted() {
        val dir = createTempDirectory("log").toFile()
        try {
            DebugLog.init(dir)
            DebugLog.i("T", "board changed")
            repeat(40) { DebugLog.e("Overlay", "auto failed: boom") }
            DebugLog.i("T", "read")
            val dump = DebugLog.dump()
            assertEquals(1, Regex("boom").findAll(dump).count())
            assertTrue(dump, "(again, 39 times)" in dump)
            // The file holds the same lines.
            assertEquals(dump, File(dir, "debug.log").readText())
            // A message still being repeated is counted when the log is read.
            repeat(2) { DebugLog.w("T", "once more") }
            assertTrue(DebugLog.dump().endsWith("W/T: once more\n  (again, 1 times)\n"))
            DebugLog.clear()
            assertEquals("", DebugLog.dump())
            assertFalse(File(dir, "debug.log").exists())
            // Different messages are not held back.
            DebugLog.i("T", "a"); DebugLog.i("T", "b"); DebugLog.i("T", "a")
            assertEquals(listOf("I/T: a", "I/T: b", "I/T: a"),
                DebugLog.dump().lines().filter { it.isNotBlank() }.map { it.split(" ").drop(2).joinToString(" ") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
