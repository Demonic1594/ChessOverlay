package io.github.chessoverlay

import io.github.chessoverlay.engine.AnalysisUpdate
import io.github.chessoverlay.engine.EngineHost
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineHostTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"))

    /** A fake UCI engine (shell script) that exits whenever it is asked to search. */
    private fun dyingEngine(): File {
        val f = File.createTempFile("dying-engine", ".sh")
        f.writeText(
            """
            #!/bin/sh
            while read line; do
              case "${'$'}line" in
                uci) echo "id name Dying"; echo "option name MultiPV type spin default 1 min 1 max 5"; echo uciok ;;
                isready) echo readyok ;;
                go*) exit 3 ;;
                quit) exit 0 ;;
              esac
            done
            """.trimIndent() + "\n"
        )
        f.setExecutable(true)
        return f
    }

    @Test fun positionThatKillsTheEngineIsSkipped() {
        val statuses = CopyOnWriteArrayList<String>()
        val skipped = CountDownLatch(1)
        EngineHost(dyingEngine(), tmp, emptyMap(), onUpdate = {}, onStatus = { s ->
            statuses.add(s)
            if ("skipped" in s) skipped.countDown()
        }).use { host ->
            host.analyse("4k3/8/8/8/8/8/8/R3K3 w - - 0 1", 1, 0)
            assertTrue("statuses: $statuses", skipped.await(20, TimeUnit.SECONDS))
            assertTrue(statuses.any { "restarting" in it })
        }
        DebugLog.dump().let { assertTrue("crash must be logged", "engine exited (code 3)" in it) }
    }

    /** With a real engine (ENGINE env): kill it mid-analysis; it restarts and the analysis finishes. */
    @Test fun realEngineRestartsAfterKill() {
        val path = System.getenv("ENGINE")
        assumeTrue("set ENGINE to run this test", path != null && File(path).canExecute())
        val finished = CountDownLatch(1)
        val updates = CopyOnWriteArrayList<AnalysisUpdate>()
        val statuses = CopyOnWriteArrayList<String>()
        EngineHost(File(path!!), tmp, mapOf("Threads" to "1"), onUpdate = { u ->
            updates.add(u)
            if (u.finished && u.lines.isNotEmpty()) finished.countDown()
        }, onStatus = { statuses.add(it) }).use { host ->
            host.analyse("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", 2, 1500)
            // Let it start searching, then kill the engine process like Android's
            // low-memory killer would.
            val deadline = System.currentTimeMillis() + 5000
            while (updates.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val killed = ProcessBuilder("pkill", "-9", "-f", File(path).absolutePath).start().waitFor()
            assumeTrue("could not kill the engine process (pkill exit $killed)", killed == 0)
            assertTrue("analysis must finish after a restart; statuses: $statuses", finished.await(20, TimeUnit.SECONDS))
            assertTrue(statuses.any { "restarting" in it })
        }
    }
}
