package com.arkiv.player.data.plugin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
