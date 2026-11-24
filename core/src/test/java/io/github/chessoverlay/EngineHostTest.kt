package io.github.chessoverlay

import io.github.chessoverlay.engine.AnalysisUpdate
import io.github.chessoverlay.engine.EngineHost
import io.github.chessoverlay.engine.LineStabilizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue

/**
 * The app's engine code (EngineHost, UciEngine, PvCollector, LineStabilizer)
 * against a real engine, the way the overlay runs it: default options,
 * three lines, "go infinite", a new position while the last one is still
 * being analysed. Runs only with ENGINE_BIN set to an engine executable
 * (or to a script that starts one, e.g. under qemu).
 */
class EngineHostTest {
    private val bin = System.getenv("ENGINE_BIN")?.let(::File)

    @Test
    fun linesArriveForEachPositionAndKeepComing() {
        assumeTrue("ENGINE_BIN not set", bin != null && bin.canExecute())
        val updates = CopyOnWriteArrayList<Pair<Long, AnalysisUpdate>>()
        val status = CopyOnWriteArrayList<String>()
        // ENGINE_THREADS: the phone's thread count (the default is half the cores).
        val opts = System.getenv("ENGINE_THREADS")?.let { mapOf("Threads" to it) } ?: emptyMap()
        val host = EngineHost(bin!!, bin.parentFile, opts,
            onUpdate = { updates.add(System.currentTimeMillis() to it) },
            onStatus = { status.add(it) },
            startTimeoutMs = 60_000)
        host.use {
            val fens = listOf(
                "2kr1r2/ppp3R1/6p1/3Pp1Bp/2P1N3/8/PP4PP/R5K1 w - - 0 1",
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
            )
            host.start()
            // Wait for the engine to be up (slow under emulation), so each
            // position gets the same time.
            val until = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < until && "EngineHost: started" !in DebugLog.dump() &&
                "could not start" !in DebugLog.dump()) Thread.sleep(200)
            for (fen in fens) {
                val t0 = System.currentTimeMillis()
                val id = host.analyse(fen, 3, 0)
                val secs = (System.getenv("ENGINE_SECS") ?: "4").toLong()
                Thread.sleep(secs * 1000)
                val mine = updates.filter { it.second.id == id }
                val first = mine.firstOrNull { it.second.lines.isNotEmpty() }
                val last = mine.lastOrNull()?.second
                val stab = LineStabilizer(2, 2)
                mine.forEach { stab.offer(it.second.lines, it.second.finished) }
                println("$fen: ${mine.size} updates, first lines after ${first?.let { it.first - t0 }} ms, " +
                    "last depth ${last?.lines?.firstOrNull()?.depth}, lines ${last?.lines?.size}, " +
                    "best ${last?.lines?.firstOrNull()?.pv?.take(4)}")
                assertTrue("no lines for $fen; status $status; log:\n${DebugLog.dump().takeLast(3000)}", first != null)
                assertTrue("3 lines for $fen", last!!.lines.size == 3)
                assertTrue("an infinite search must not end by itself ($fen)", !last.finished)
            }
        }
        println(DebugLog.dump().lines().takeLast(30).joinToString("\n"))
    }

    /**
     * An engine that answers "uci" and "isready" but never searches unless
     * it runs on one thread: the host notices, logs what it said, restarts
     * it with safer settings and gets lines in the end.
     */
    @Test
    fun aSilentEngineIsRestartedWithSaferSettings() {
        val dir = createTempDir("mute")
        val bin = File(dir, "mute.sh")
        bin.writeText("""
            #!/bin/sh
            threads=4
            while read -r line; do
              case "${'$'}line" in
                uci) echo "id name Mute"; echo "option name Threads type spin default 4 min 1 max 8"
                     echo "option name MultiPV type spin default 1 min 1 max 5"; echo uciok ;;
                "setoption name Threads value "*) threads=${'$'}{line##* } ;;
                isready) echo readyok ;;
                go*) if [ "${'$'}threads" = 1 ]; then
                       echo "info depth 1 seldepth 1 multipv 1 score cp 20 nodes 30 pv e2e4 e7e5"
                       echo "info depth 1 seldepth 1 multipv 2 score cp 10 nodes 60 pv d2d4 d7d5"
                     else echo "info string Using ${'$'}threads threads"; fi ;;
                stop) echo "bestmove e2e4" ;;
                quit) exit 0 ;;
              esac
            done
        """.trimIndent() + "\n")
        bin.setExecutable(true)
        val updates = CopyOnWriteArrayList<AnalysisUpdate>()
        val status = CopyOnWriteArrayList<String>()
        EngineHost(bin, dir, emptyMap(), onUpdate = { updates.add(it) }, onStatus = { status.add(it) },
            silentMs = 700).use { host ->
            host.analyse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 2, 0)
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until && updates.none { it.lines.isNotEmpty() }) Thread.sleep(100)
            val log = DebugLog.dump()
            assertTrue("lines after the fallback; status $status\n$log", updates.any { it.lines.size == 2 })
            assertTrue("one thread in the end", host.fallback == 2)
            assertTrue("the user is told: $status", status.any { "restarting" in it })
            assertTrue("what the engine said is logged", "ms info string Using" in log)
            println(log.lines().takeLast(12).joinToString("\n"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun engineCheckReportsWhatHappened() {
        assumeTrue("ENGINE_BIN not set", bin != null && bin.canExecute())
        val r = io.github.chessoverlay.engine.EngineCheck.run(bin!!, bin.parentFile, emptyMap(), seconds = 2, giveUpMs = 90_000)
        println(r.text)
        assertTrue(r.text, r.ok)
    }

    /** Checkmate or stalemate: "bestmove (none)" is an answer, not a silent engine. */
    @Test
    fun noLegalMoveIsNotAFailure() {
        val dir = createTempDir("mated")
        val bin = File(dir, "mated.sh")
        bin.writeText("""
            #!/bin/sh
            while read -r line; do
              case "${'$'}line" in
                uci) echo "id name Mated"; echo uciok ;;
                isready) echo readyok ;;
                go*) echo "info depth 0 score mate 0"; echo "bestmove (none)" ;;
                quit) exit 0 ;;
              esac
            done
        """.trimIndent() + "\n")
        bin.setExecutable(true)
        val updates = CopyOnWriteArrayList<AnalysisUpdate>()
        val status = CopyOnWriteArrayList<String>()
        EngineHost(bin, dir, emptyMap(), onUpdate = { updates.add(it) }, onStatus = { status.add(it) },
            silentMs = 500).use { host ->
            host.analyse("8/8/6R1/3P4/1kP1N3/pP5P/P2B2P1/5K2 b - - 0 1", 3, 0)
            Thread.sleep(1500)
            assertTrue("finished", updates.any { it.finished })
            assertTrue("and the overlay is told that there is no move", updates.any { it.noLegalMove && it.lines.isEmpty() })
            assertTrue("no restart: fallback ${host.fallback}, status $status", host.fallback == 0 && status.isEmpty())
        }
        dir.deleteRecursively()
    }

    /**
     * From a phone: after 23...Qg3+ White had two legal moves, three lines
     * were asked for, and a strength limit stopped the engine at depth 1:
     * two lines, then nothing until "stop". They must still be shown.
     */
    @Test
    fun fewerLinesThanAskedAreShown() {
        val dir = createTempDir("two")
        val bin = File(dir, "two.sh")
        bin.writeText("""
            #!/bin/sh
            while read -r line; do
              case "${'$'}line" in
                uci) echo "id name Two"; echo "option name MultiPV type spin default 1 min 1 max 5"; echo uciok ;;
                isready) echo readyok ;;
                go*) echo "info depth 1 seldepth 13 multipv 1 score cp 5 nodes 10969 pv f1f2 g4e2"
                     echo "info depth 1 seldepth 6 multipv 2 score cp -10 nodes 10969 pv e1d1" ;;
                stop) echo "bestmove f1f2" ;;
                quit) exit 0 ;;
              esac
            done
        """.trimIndent() + "\n")
        bin.setExecutable(true)
        val updates = CopyOnWriteArrayList<Pair<Long, AnalysisUpdate>>()
        EngineHost(bin, dir, emptyMap(), onUpdate = { updates.add(System.currentTimeMillis() to it) }, onStatus = {},
            silentMs = 5_000).use { host ->
            val t0 = System.currentTimeMillis()
            host.analyse("r4rk1/p4p2/2p2p1p/Q7/2P1p1b1/6q1/PB1PB1P1/1R2KR2 w - - 0 1", 3, 0)
            val until = t0 + 3_000
            while (System.currentTimeMillis() < until && updates.none { it.second.lines.isNotEmpty() }) Thread.sleep(20)
            val first = updates.firstOrNull { it.second.lines.isNotEmpty() }
            assertTrue("the two lines arrive though three were asked", first != null && first.second.lines.size == 2)
            assertTrue("not finished: the search goes on", !first!!.second.finished)
            println("two lines after ${first.first - t0} ms (engine start included)")
        }
        dir.deleteRecursively()
    }
}
