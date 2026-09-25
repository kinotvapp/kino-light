package com.arkiv.player.data.plugin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginHomeRowsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun plugin(id: String, caps: Set<String> = setOf("home", "resolve")) = InstalledPlugin(
        PluginManifest(id, id.uppercase(), "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), caps, null, null),
        InstalledRecord("o/$id", "1.0.0", "x", listOf("example.com"), 0L),
        null,
    )

    private val rowJson = """[{"id":"top","title":"Lo más visto","items":[{"id":"m1","ref":"R1","title":"Uno","kind":"movie"}]}]"""

    private class CountingCaller(val answer: (String) -> String) : PluginCaller {
        var calls = 0
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls++
            return answer(pluginId)
        }
    }

    private var now = 0L
    private fun home(plugins: List<InstalledPlugin>, caller: PluginCaller) =
        PluginHomeRows({ plugins }, caller, cacheFileFor = { File(tmp.root, "$it/home.json") }, clock = { now }, log = {})

    @Test fun `rows come per plugin, in registry order, as plugin results`() = runTest {
        val rows = home(listOf(plugin("a"), plugin("b")), CountingCaller { rowJson }).rows().toList().last()
        assertEquals(listOf("a", "b"), rows.map { it.pluginId })
        assertEquals("Lo más visto", rows[0].title)
        assertEquals("A", rows[0].pluginName)
        assertEquals("plugin:a", rows[0].items.single().source)
    }

    @Test fun `plugins without the home capability are not asked`() = runTest {
        val caller = CountingCaller { rowJson }
        assertEquals(emptyList<PluginHomeRow>(), home(listOf(plugin("c", setOf("search", "resolve"))), caller).rows().toList().last())
        assertEquals(0, caller.calls)
    }

    @Test fun `a failing plugin contributes no rows and does not block the others`() = runTest {
        val caller = CountingCaller { id -> if (id == "a") throw PluginTimeoutException("home", 20_000) else rowJson }
        assertEquals(listOf("b"), home(listOf(plugin("a"), plugin("b")), caller).rows().toList().last().map { it.pluginId })
    }

    @Test fun `cached rows show first and are reused for six hours`() = runTest {
        val caller = CountingCaller { rowJson }
        home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(1, caller.calls)
        now += 5 * 3_600_000L
        val emissions = home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(1, emissions.first().size)
        assertEquals(1, caller.calls)
        now += 2 * 3_600_000L
        home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(2, caller.calls)
    }

    @Test fun `an oversized cache file is ignored and removed, never parsed`() = runTest {
        val file = File(tmp.root, "a/home.json").apply { parentFile!!.mkdirs() }
        // Fresh (fetchedAt = now) and valid, just too big: it must not be read at all.
        file.writeText("{\"fetchedAt\":0,\"json\":" + org.json.JSONObject.quote(rowJson + " ".repeat(PluginHomeRows.MAX_CACHE_BYTES)) + "}")
        val caller = CountingCaller { rowJson }
        val emissions = home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(emptyList<PluginHomeRow>(), emissions.first())
        assertEquals(1, caller.calls)
        assertEquals(listOf("top"), emissions.last().map { it.id })
        assertTrue(file.length() <= PluginHomeRows.MAX_CACHE_BYTES)
    }

    @Test fun `an answer too big to cache is shown but not written`() = runTest {
        val big = rowJson.replace("\"title\":\"Lo más visto\"", "\"title\":\"Lo más visto\",\"pad\":\"" + "x".repeat(PluginHomeRows.MAX_CACHE_BYTES) + "\"")
        val emissions = home(listOf(plugin("a")), CountingCaller { big }).rows().toList()
        assertEquals(listOf("top"), emissions.last().map { it.id })
        assertFalse(File(tmp.root, "a/home.json").exists())
    }

    @Test fun `an answer with no valid rows is not cached, so the next Home asks again`() = runTest {
        val caller = CountingCaller { "not json" }
        home(listOf(plugin("a")), caller).rows().toList()
        assertFalse(File(tmp.root, "a/home.json").exists())
        home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(2, caller.calls)
    }

    /**
     * Fix round 1, finding 3: a settings change bumps the session revision (AppGraph's
     * `forgetPluginSession`). A `home()` call that was already in flight for the OLD session must
     * not have its answer shown or cached once it lands — otherwise the old account's rows could be
     * written to `home.json` right after a settings change deleted it, and served as "fresh" cache
     * to the NEW account for up to six hours.
     */
    @Test fun `a home answer is discarded, not shown or cached, when the session changed mid-flight`() = runTest {
        var revision = 0
        val caller = object : PluginCaller {
            override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
                // Simulates a settings change landing while this very call is still running.
                revision++
                return rowJson
            }
        }
        val rows = PluginHomeRows(
            { listOf(plugin("a")) }, caller,
            cacheFileFor = { File(tmp.root, "$it/home.json") },
            clock = { now }, sessionRevision = { revision }, log = {},
        ).rows().toList().last()
        assertEquals(emptyList<PluginHomeRow>(), rows)
        assertFalse("the old session's rows must never reach the cache file", File(tmp.root, "a/home.json").exists())
    }

    @Test fun `an unchanged session revision caches and shows the answer normally`() = runTest {
        val caller = CountingCaller { rowJson }
        val rows = PluginHomeRows(
            { listOf(plugin("a")) }, caller,
            cacheFileFor = { File(tmp.root, "$it/home.json") },
            clock = { now }, sessionRevision = { 7 }, log = {},
        ).rows().toList().last()
        assertEquals(listOf("top"), rows.map { it.id })
        assertTrue(File(tmp.root, "a/home.json").exists())
    }
}
