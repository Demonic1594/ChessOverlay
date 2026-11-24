package io.github.chessoverlay

import io.github.chessoverlay.vision.Img
import org.json.JSONObject
import io.github.chessoverlay.vision.Recognizer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The benchmark of board reading: boards as lichess shows them, in its themes
 * and piece sets, rendered by tools/bench/gen.py (the build workflow does,
 * and sets BENCH to the directory). It needs the models too (MODELS, and
 * QMODELS for the int8 ones the APK ships).
 *
 * It says how many boards are read right, by piece set, theme, orientation,
 * coordinates, and kind of picture; how far the located corners are off; how
 * long a read takes; and how much corners that are a little off cost (a
 * locator's are rarely exact). The table is printed and written to
 * report.txt in the benchmark's directory.
 */
class BenchTest {
    private class Board(c: JSONObject) {
        val file = c.getString("file")
        val fen = c.getString("fen")
        val black = c.getString("bottom") == "black"
        val box = c.getJSONArray("box").let { a -> IntArray(4) { a.getInt(it) } }
        val theme = c.getString("theme")
        val pieces = c.getString("pieces")
        val coords = c.getBoolean("coords")
        val phone = c.getBoolean("phone")
        val side = box[2] - box[0]
        fun corners(): List<FloatArray> = listOf(
            floatArrayOf(box[0].toFloat(), box[1].toFloat()), floatArrayOf(box[2].toFloat(), box[1].toFloat()),
            floatArrayOf(box[2].toFloat(), box[3].toFloat()), floatArrayOf(box[0].toFloat(), box[3].toFloat()))
    }

    private class Tally {
        var n = 0
        var right = 0
        val by = HashMap<String, IntArray>()   // key -> [right, total]
        fun add(keys: List<String>, ok: Boolean) {
            n++; if (ok) right++
            for (k in keys) { val t = by.getOrPut(k) { IntArray(2) }; t[1]++; if (ok) t[0]++ }
        }
    }

    @Test fun boardsAsLichessShowsThem() {
        val dir = System.getenv("BENCH")
        val models = System.getenv("MODELS")
        assumeTrue("set BENCH (tools/bench/gen.py) and MODELS to run this test", dir != null && File(dir, "expected.json").exists() && models != null)
        val qmodels = System.getenv("QMODELS")?.takeIf { File(it, "board.onnx").exists() } ?: models
        val list = org.json.JSONArray(File(dir, "expected.json").readText()).let { a -> (0 until a.length()).map { Board(a.getJSONObject(it)) } }
        assertTrue("no boards", list.isNotEmpty())
        val images = list.associate { it.file to load(File(dir, it.file)) }
        val report = StringBuilder("Board reading on ${list.size} boards as lichess shows them (x86 runner).\n")

        fun measure(label: String, modelDir: String, jitter: Double, locate: Boolean, sharpen: Boolean = true): Tally {
            val t = Tally()
            val times = ArrayList<Long>()
            val bad = ArrayList<String>()
            val offs = ArrayList<Float>()
            Recognizer(File(modelDir, "board.onnx"), File(modelDir, "locator.onnx")).use { rec ->
                for (b in list) {
                    val img = images[b.file] ?: continue
                    val rng = Random(b.file.hashCode().toLong())
                    val a = jitter * b.side
                    val corners = if (locate) null else b.corners().map { floatArrayOf(it[0] + ((rng.nextDouble() * 2 - 1) * a).toFloat(), it[1] + ((rng.nextDouble() * 2 - 1) * a).toFloat()) }
                    val t0 = System.nanoTime()
                    val r = rec.recognise(img, knownCorners = corners, sharpen = sharpen)
                    times += System.nanoTime() - t0
                    val off = r.corners.mapIndexed { i, c -> max(abs(c[0] - b.corners()[i][0]), abs(c[1] - b.corners()[i][1])) }.max()
                    if (locate) offs += off
                    val ok = r.placement == b.fen && r.blackAtBottom == b.black
                    t.add(listOf("all", "pieces ${b.pieces}", "theme ${b.theme}", if (b.black) "black at the bottom" else "white at the bottom",
                        if (b.coords) "with coordinates" else "without coordinates", if (b.phone) "phone screen" else "board alone",
                        "side ${if (b.side < 400) "<400" else if (b.side < 700) "400-699" else ">=700"}"), ok)
                    if (!ok && bad.size < 12) bad += "${b.file}: ${b.theme}/${b.pieces}${if (b.black) " black" else ""}: " +
                        "sure ${String.format(java.util.Locale.US, "%.2f", r.confidence)}; got ${r.placement}" + (if (r.blackAtBottom != b.black) " (orientation wrong)" else "")
                    if (locate) {
                        val c = t.by.getOrPut("located corners within 2 px") { IntArray(2) }
                        c[1]++
                        if (off <= 2f) c[0]++
                    }
                }
            }
            times.sort()
            val line = "$label: ${t.right}/${t.n} right, a read ${times[times.size / 2] / 1_000_000} ms (median)" +
                (if (offs.isEmpty()) "" else "; the corners off by ${String.format(Locale.US, "%.1f", offs.sorted()[offs.size / 2])} px at most (median), " +
                    "${String.format(Locale.US, "%.1f", offs.sorted()[offs.size * 9 / 10])} px at the 90th percentile")
            println(line)
            report.append(line).append('\n')
            for ((k, v) in t.by.toSortedMap()) if (k != "all") report.append("    $k: ${v[0]}/${v[1]}\n")
            if (bad.isNotEmpty()) report.append("    wrong: ").append(bad.joinToString("\n           ")).append('\n')
            return t
        }

        measure("int8 model, the board located as the locator gives it", qmodels, 0.0, locate = true, sharpen = false)
        val shipped = measure("int8 model, the board located and sharpened (as the first read does)", qmodels, 0.0, locate = true)
        measure("int8 model, corners exact", qmodels, 0.0, locate = false)
        measure("int8 model, corners off by up to 1 % of a side", qmodels, 0.01, locate = false)
        measure("int8 model, corners off by up to 2.5 % of a side", qmodels, 0.025, locate = false)
        measure("fp32 model, the board located", models, 0.0, locate = true)
        File(dir, "report.txt").writeText(report.toString())
        // The floor of what ships: below it, a change made reading worse. (Low until the first table says where it belongs.)
        assertTrue(report.toString(), shipped.right >= shipped.n * 50 / 100)
    }

    private fun load(f: File): Img = Png.read(f.readBytes())
}
