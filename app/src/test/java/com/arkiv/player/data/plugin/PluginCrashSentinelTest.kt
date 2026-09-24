package com.arkiv.player.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The crash sentinel: a plugin call that never returns to Kotlin (native crash, OOM kill) leaves
 * its in-flight marker behind; two such exits in a row switch the plugin off, so a plugin that
 * kills the app can't crash-loop it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginCrashSentinelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dataRoot by lazy { File(tmp.root, "plugin-data") }
    private fun marker(id: String) = File(dataRoot, "$id/${PluginCrashSentinel.INFLIGHT_FILE}")
    private fun counter(id: String) = File(dataRoot, "$id/${PluginCrashSentinel.UNCLEAN_FILE}")

    private class FakeRuntime(val answer: suspend () -> String) : ScriptRuntime {
        override val exports = setOf("home")
        override var isDiscarded = false
        override suspend fun call(function: String, argJson: String, timeoutMs: Long): String = answer()
        override fun close() { isDiscarded = true }
    }

    /** A pool on a fresh "process": same data dir, new sentinel (what a relaunch looks like). */
    private fun pool(
        flagged: MutableList<String> = mutableListOf(),
        seenMarker: MutableList<Boolean> = mutableListOf(),
        answer: suspend () -> String = { "[]" },
    ) = PluginRuntimePool(
        open = { id -> FakeRuntime { seenMarker += marker(id).exists(); answer() } },
        onUnresponsive = { flagged += it },
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        sentinel = PluginCrashSentinel(dataRoot),
        io = Dispatchers.Unconfined,
    )

    @Test fun `the marker exists while a call runs and is gone after success, failure or timeout`() = runTest {
        val seen = mutableListOf<Boolean>()
        pool(seenMarker = seen).call("pa", "home", "null", 1_000)
        assertFalse(marker("pa").exists())
        runCatching { pool(seenMarker = seen) { throw PluginScriptException("boom") }.call("pa", "home", "null", 1_000) }
        assertFalse(marker("pa").exists())
        runCatching { pool(seenMarker = seen) { throw PluginTimeoutException("home", 10) }.call("pa", "home", "null", 10) }
        assertFalse(marker("pa").exists())
        assertEquals(listOf(true, true, true), seen)
    }

    @Test fun `a leftover marker counts one unclean exit, a second in a row switches the plugin off`() = runTest {
        marker("pa").apply { parentFile!!.mkdirs(); writeText("") }
        val flagged = mutableListOf<String>()
        pool(flagged).call("pb", "home", "null", 1_000) // any first call runs the recovery
        assertEquals(emptyList<String>(), flagged)
        assertFalse(marker("pa").exists())
        assertEquals("1", counter("pa").readText())

        marker("pa").writeText("") // it crashed the app again
        val refused = runCatching { pool(flagged).call("pa", "home", "null", 1_000) }.exceptionOrNull()
        assertEquals(listOf("pa"), flagged)
        assertTrue(refused is PluginScriptException)
        assertFalse(marker("pa").exists())
        assertFalse(counter("pa").exists())
    }

    @Test fun `a clean call in between resets the count`() = runTest {
        marker("pa").apply { parentFile!!.mkdirs(); writeText("") }
        pool().call("pa", "home", "null", 1_000)
        assertFalse(counter("pa").exists())
        marker("pa").writeText("")
        val flagged = mutableListOf<String>()
        pool(flagged).call("pa", "home", "null", 1_000)
        assertEquals(emptyList<String>(), flagged)
        // Counted afresh after the reset, and the clean call that followed reset it again.
        assertFalse(counter("pa").exists())
    }

    @Test fun `a failed call returns to Kotlin but does not reset the count`() = runTest {
        marker("pa").apply { parentFile!!.mkdirs(); writeText("") }
        runCatching { pool { throw PluginScriptException("boom") }.call("pa", "home", "null", 1_000) }
        assertEquals("1", counter("pa").readText())
    }

    @Test fun `a plugin that was never called has no marker and no counter`() = runTest {
        pool().call("pa", "home", "null", 1_000)
        assertFalse(File(dataRoot, "pb").exists())
        assertFalse(counter("pa").exists())
    }

    @Test fun `uninstall removes the marker and the counter with the rest of the plugin data`() = runTest {
        val store = PluginStore(File(tmp.root, "plugins"), dataRoot)
        marker("pa").apply { parentFile!!.mkdirs(); writeText("") }
        counter("pa").writeText("1")
        store.remove("pa", "A")
        assertFalse(File(dataRoot, "pa").exists())
    }

    @Test fun `the recovery runs once per process, on the IO dispatcher given`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        marker("pa").apply { parentFile!!.mkdirs(); writeText("") }
        val p = PluginRuntimePool(
            open = { FakeRuntime { "[]" } }, onUnresponsive = {}, scope = backgroundScope,
            sentinel = PluginCrashSentinel(dataRoot), io = io,
        )
        p.call("pb", "home", "null", 1_000)
        assertEquals("1", counter("pa").readText())
        marker("pa").writeText("") // appears after recovery ran: not counted until the next process
        p.call("pb", "home", "null", 1_000)
        assertEquals("1", counter("pa").readText())
        assertTrue(marker("pa").exists())
    }

    @Test fun `the install probe is not counted - it never goes through the pool`() = kotlinx.coroutines.runBlocking {
        val store = PluginStore(File(tmp.root, "plugins"), dataRoot)
        val base = "https://raw.githubusercontent.com/o/r/HEAD/"
        val files = mapOf(
            base + "kino-plugin.json" to org.json.JSONObject()
                .put("id", "pa").put("name", "A").put("version", "1.0.0").put("apiVersion", 1)
                .put("entry", "plugin.js").put("hosts", org.json.JSONArray(listOf("example.com")))
                .put("capabilities", org.json.JSONArray(listOf("search", "resolve"))).toString().toByteArray(),
            base + "plugin.js" to "export async function search(){}\nexport async function resolve(){}".toByteArray(),
        )
        val installer = PluginInstaller(
            store,
            PluginFetcher { url, _ -> files.getValue(url) },
            probe = { script -> PluginRuntime.open("probe", script, ProbePluginHost, PluginEnv(appVersion = "1")).let { rt -> rt.exports.also { rt.close() } } },
        )
        installer.install(installer.preview("o/r"))
        assertFalse(marker("pa").exists())
        assertFalse(counter("pa").exists())
    }
}
