package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.vision.Img
import io.github.chessoverlay.vision.LastMoveHighlight
import io.github.chessoverlay.vision.Recognizer
import io.github.chessoverlay.vision.meanConfidence
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Real screenshots from phones, read as the app reads them. The pictures
 * are private and stay out of the repository: put them in a directory (PNG,
 * or this project's .rgb.gz) and set SHOTS to it, with MODELS. Next to a
 * picture, `name.txt` can say what it shows, one line:
 *
 *     <placement from White's side> <white|black at the bottom> [<last move, e.g. h2g3>]
 *
 * Pictures with such a line must be read that way (the test fails
 * otherwise); the others are only printed, so a new screenshot can be
 * looked at first and its line written once it is checked.
 */
class ShotsTest {
    private fun load(f: File): Img =
        if (f.name.endsWith(".png")) Png.read(f.readBytes()) else Img.fromRgb(GZIPInputStream(f.inputStream()).readBytes())

    @Test fun realScreenshots() {
        val models = System.getenv("MODELS")?.let(::File)
        val shots = System.getenv("SHOTS")?.let(::File)
        assumeTrue("set SHOTS (a directory of screenshots) and MODELS", models != null && shots != null && shots.isDirectory)
        val pictures = shots!!.listFiles()!!.filter { it.name.endsWith(".png") || it.name.endsWith(".rgb.gz") }.sorted()
        val wrong = ArrayList<String>()
        Recognizer(File(models!!, "board.onnx"), File(models, "locator.onnx")).use { rec ->
            for (f in pictures) {
                val img = load(f)
                val r = rec.recognise(img)
                val seen = runCatching { LastMoveHighlight.find(img, r.corners, Board.fromPlacement(r.placement), r.blackAtBottom) }.getOrNull()
                val got = "${r.placement} ${if (r.blackAtBottom) "black" else "white"}"
                println("${f.name}: $got, sure ${"%.2f".format(r.confidence)} (mean ${"%.3f".format(r.meanConfidence())}), " +
                    "corners ${r.corners.joinToString(" ") { "${it[0].toInt()},${it[1].toInt()}" }}" +
                    (rec.lastAlignment?.let { "; alignments: $it" } ?: "") + "; last move ${seen?.move ?: "not seen"}")
                val note = File(f.parentFile, f.name.removeSuffix(".png").removeSuffix(".rgb.gz") + ".txt")
                if (!note.exists()) continue
                val want = note.readText().trim().split(Regex("\\s+"))
                if (want.size < 2) continue
                if (r.placement != want[0] || r.blackAtBottom != (want[1] == "black"))
                    wrong += "${f.name}: read $got, it shows ${want[0]} ${want[1]}"
                else if (want.size > 2 && seen?.move != want[2])
                    wrong += "${f.name}: last move ${seen?.move}, it is ${want[2]}"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}
