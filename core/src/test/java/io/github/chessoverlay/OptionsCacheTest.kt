package io.github.chessoverlay

import io.github.chessoverlay.engine.OptionsCache
import io.github.chessoverlay.engine.UciOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.path.createTempDirectory

class OptionsCacheTest {
    @Test fun optionsComeBackAsTheEngineGaveThem() {
        val lines = listOf(
            "option name Threads type spin default 1 min 1 max 1024",
            "option name Ponder type check default false",
            "option name Analysis Contempt type combo default Both var Off var White var Black var Both",
            "option name Clear Hash type button",
            "option name EvalFile type string default nn-1234.nnue",
            "option name SyzygyPath type string default <empty>",
        )
        val opts = lines.map { UciOption.parse(it)!! }
        assertEquals("each line round-trips", opts, opts.map { UciOption.parse(it.toLine()) })
        val dir = createTempDirectory("opts").toFile()
        val engine = java.io.File(dir, "engine").apply { writeText("v1") }
        val cache = OptionsCache(java.io.File(dir, "cache"))
        assertNull(cache.get(engine))
        cache.put(engine, "Smotherfish 1.5.1", opts)
        val e = cache.get(engine)!!
        assertEquals("Smotherfish 1.5.1", e.name)
        assertEquals(opts, e.options)
        // A new version of the file is read afresh.
        engine.writeText("version two"); engine.setLastModified(engine.lastModified() + 5000)
        assertNull(cache.get(engine))
        dir.deleteRecursively()
    }
}
