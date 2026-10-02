package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/**
 * A plugin's top level runs exactly ONCE per [PluginRuntime.open], on the real QuickJS engine, whatever
 * the load path (from source, a bytecode-cache miss that compiles and keeps, a cache hit) and for a
 * converted Nuvio scraper too: a second evaluation doubles every load-time side effect (logs, storage
 * writes, fetches) and the load time itself.
 */
class PluginTopLevelOnceTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The install probe's host (no network), counting what the top level logs. */
    private class CountingHost(secretNames: Set<String> = emptySet()) : ProbeHost(secretNames) {
        val logs = mutableListOf<String>()
        override fun log(level: String, message: String) { synchronized(logs) { logs += message } }
        fun runs() = synchronized(logs) { logs.count { it.contains("top-level run") } }
    }

    private val env = PluginEnv(appVersion = "1.0")

    private val plain = """
        globalThis.__topLevelRuns = (globalThis.__topLevelRuns || 0) + 1;
        kino.log('top-level run');
        export async function home() { return [globalThis.__topLevelRuns] }
    """.trimIndent()

    private val nuvioScript: String = NuvioPluginConverter.convert(
        NuvioScraperEntry(
            id = "oncesrc", name = "OnceSrc", filename = "providers/oncesrc.js", enabled = true,
            contentLanguage = listOf("es"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
        ),
        """
            console.log('top-level run');
            function getStreams() { return Promise.resolve([]); }
            module.exports = { getStreams: getStreams };
        """.trimIndent(),
        repoSlug = "owner/repo",
    ).script

    private fun cache() = PluginBytecodeCache(tmp.newFolder(), "engine-once", PluginBytecodeCache.DEFAULT_MAX_BYTES, { it.run() })

    /** Opens [script] (through [store] as owner "once" when given), calls [export] if any; top-level runs + the answer. */
    private fun openAndCount(script: String, store: CompiledCodeStore? = null, export: String? = "home"): Pair<Int, String?> = runBlocking {
        val host = CountingHost()
        val rt = PluginRuntime.open("once", script, host, env, compiled = store, owner = store?.let { "once" })
        try {
            val answer = export?.let { rt.call(it, "null", 5_000) }
            host.runs() to answer
        } finally {
            rt.close()
        }
    }

    @Test fun `from source the top level runs once`() {
        assertEquals(1 to "[1]", openAndCount(plain))
    }

    @Test fun `a cache miss runs the top level once and keeps the bytecode`() {
        val store = cache()
        assertEquals(1 to "[1]", openAndCount(plain, store))
        assertNotNull("the miss kept the script's bytecode", store.read("once", "script", plain))
    }

    @Test fun `a cache hit runs the top level once`() {
        val store = cache()
        openAndCount(plain, store)
        assertEquals(1 to "[1]", openAndCount(plain, store))
    }

    @Test fun `a top level that awaits finishes before open returns, once, from source, miss and hit`() {
        val script = """
            globalThis.__topLevelRuns = (globalThis.__topLevelRuns || 0) + 1;
            kino.log('top-level run');
            const ready = await Promise.resolve('ready');
            export async function home() { return [globalThis.__topLevelRuns, ready] }
        """.trimIndent()
        assertEquals(1 to "[1,\"ready\"]", openAndCount(script))
        val store = cache()
        assertEquals("miss", 1 to "[1,\"ready\"]", openAndCount(script, store))
        assertEquals("hit", 1 to "[1,\"ready\"]", openAndCount(script, store))
    }

    @Test fun `a top level that throws after an await still fails open, and is never cached`() {
        val script = "await Promise.resolve(0); throw new Error('late boom'); export async function home() { return [] }"
        val store = cache()
        for (s in listOf(null, store)) {
            val e = assertThrows(PluginException::class.java) {
                runBlocking { PluginRuntime.open("once", script, CountingHost(), env, compiled = s, owner = s?.let { "once" }).close() }
            }
            assertTrue(e.message, e.message!!.contains("late boom"))
        }
        assertEquals(null, store.read("once", "script", script))
    }

    @Test fun `a converted Nuvio scraper runs its top level once, from source, miss and hit`() {
        assertEquals(1, openAndCount(nuvioScript, export = null).first)
        val store = cache()
        assertEquals("miss", 1, openAndCount(nuvioScript, store, export = null).first)
        assertNotNull(store.read("once", "script", nuvioScript))
        assertEquals("hit", 1, openAndCount(nuvioScript, store, export = null).first)
    }

    @Test fun `preview plus install loads the script once, in the probe`() = runBlocking {
        val base = "https://raw.githubusercontent.com/o/r/HEAD/"
        val script = "kino.log('top-level run');\nexport async function search(){}\nexport async function resolve(){}"
        val files = mapOf(
            base + "kino-plugin.json" to JSONObject()
                .put("id", "demo").put("name", "Demo").put("version", "1.0.0").put("apiVersion", 1)
                .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
                .put("capabilities", JSONArray(listOf("search", "resolve"))).toString().toByteArray(),
            base + "plugin.js" to script.toByteArray(),
        )
        val hosts = mutableListOf<CountingHost>()
        val installer = PluginInstaller(
            PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data")),
            { url, _ -> files[url] ?: throw FileNotFoundException(url) },
            probe = { s, names ->
                val host = CountingHost(names).also { hosts += it }
                val rt = PluginRuntime.open("probe", s, host, env)
                try { rt.exports } finally { rt.close() }
            },
        )
        val preview = installer.preview("o/r")
        assertEquals("preview loads nothing", 0, hosts.size)
        installer.install(preview)
        assertEquals("one probe open", 1, hosts.size)
        assertEquals("one top-level run in it", 1, hosts.single().runs())
    }
}
