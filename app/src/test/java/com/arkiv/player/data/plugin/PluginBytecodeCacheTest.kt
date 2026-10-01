package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

class PluginBytecodeCacheTest {
    private val dir: File = Files.createTempDirectory("qjsc").toFile()
    private val direct = Executor { it.run() }
    private var now = 1_000_000L
    private fun cache(tag: String = "engine-a", max: Long = PluginBytecodeCache.DEFAULT_MAX_BYTES) =
        PluginBytecodeCache(dir, tag, max, direct, clock = { now })

    private val opened = mutableListOf<PluginRuntime>()

    @After fun cleanUp() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    private val bytes = ByteArray(1000) { (it * 7).toByte() }

    // --- the store on its own ---------------------------------------------------------------

    @Test fun `answers what was written for the same source`() {
        val c = cache()
        c.write("plug", "script", "source A", bytes)
        assertArrayEquals(bytes, c.read("plug", "script", "source A"))
    }

    @Test fun `another source, kind or engine misses and the stale file goes`() {
        cache().write("plug", "script", "source A", bytes)
        assertNull(cache().read("plug", "prelude", "source A"))
        assertNull(cache(tag = "engine-b").read("plug", "script", "source A"))
        assertFalse(File(dir, "plug.script.qjsc").exists())
        cache().write("plug", "script", "source A", bytes)
        assertNull(cache().read("plug", "script", "source B (an update)"))
        assertFalse(File(dir, "plug.script.qjsc").exists())
    }

    @Test fun `a flipped bit or a truncated file is never handed out`() {
        val c = cache()
        c.write("plug", "script", "src", bytes)
        val f = File(dir, "plug.script.qjsc")
        val good = f.readBytes()
        f.writeBytes(good.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() })
        assertNull(c.read("plug", "script", "src"))
        c.write("plug", "script", "src", bytes)
        f.writeBytes(good.copyOf(good.size - 10))
        assertNull(c.read("plug", "script", "src"))
        f.writeBytes(ByteArray(0))
        assertNull(c.read("plug", "script", "src"))
        f.writeBytes("not a cache file at all, just text placed there".toByteArray())
        assertNull(c.read("plug", "script", "src"))
    }

    @Test fun `owners and kinds outside the safe names are refused, never a path`() {
        val c = cache()
        c.write("../evil", "script", "src", bytes)
        c.write("plug", "../x", "src", bytes)
        c.write("Plug", "script", "src", bytes)
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
        assertNull(c.read("../evil", "script", "src"))
    }

    @Test fun `discard drops only that owner`() {
        val c = cache()
        c.write("one", "script", "a", bytes)
        c.write("one-two", "script", "b", bytes)
        c.write(PluginBytecodeCache.SHARED_OWNER, "prelude", "p", bytes)
        c.discard("one")
        assertNull(c.read("one", "script", "a"))
        assertNotNull(c.read("one-two", "script", "b"))
        assertNotNull(c.read(PluginBytecodeCache.SHARED_OWNER, "prelude", "p"))
    }

    @Test fun `stays under its size bound, least recently used first`() {
        val entry = 1000L + 76 // payload + header
        val c = cache(max = entry * 2)
        c.write("a", "script", "a", bytes); now += 1000
        c.write("b", "script", "b", bytes); now += 1000
        assertNotNull(c.read("a", "script", "a")); now += 1000 // a is now the most recent
        c.write("c", "script", "c", bytes)
        assertNull("b was the least recently used", c.read("b", "script", "b"))
        assertNotNull(c.read("a", "script", "a"))
        assertNotNull(c.read("c", "script", "c"))
    }

    @Test fun `engine tag carries the app build and the process architecture`() {
        val t = PluginBytecodeCache.engineTag(48, "aarch64")
        assertTrue(t, t.contains("app=48") && t.contains("arch=aarch64") && t.contains(PluginBytecodeCache.ENGINE))
        assertTrue(PluginBytecodeCache.engineTag(49, "aarch64") != t)
        assertTrue(PluginBytecodeCache.engineTag(48, "armv7l") != t)
    }

    // --- PluginRuntime.open with the store -------------------------------------------------

    /** Counts what [PluginRuntime.open] asked for, and can answer something else than the disk. */
    private class Recording(val inner: CompiledCodeStore) : CompiledCodeStore {
        val hits = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val discards = mutableListOf<String>()
        var override: ((String, String) -> ByteArray?)? = null
        override fun read(owner: String, kind: String, source: String): ByteArray? =
            (override?.invoke(owner, kind) ?: inner.read(owner, kind, source))?.also { hits += "$owner.$kind" }
        override fun write(owner: String, kind: String, source: String, bytecode: ByteArray) {
            writes += "$owner.$kind"; inner.write(owner, kind, source, bytecode)
        }
        override fun discard(owner: String) { discards += owner; inner.discard(owner) }
    }

    private val env = PluginEnv(appVersion = "9.9.9")

    private val script = """
        let opened = 0
        opened++
        export async function search(q) {
          const kept = await kino.storage.get('k')
          return [{ title: q.q.toUpperCase() + ':' + opened + ':' + (kept ?? 'none') }]
        }
        export function resolve() { return { url: 'https://x/' + typeof kino.fetch } }
    """.trimIndent()

    private suspend fun open(store: CompiledCodeStore?, src: String = script, owner: String = "plug") =
        PluginRuntime.open("bc", src, ProbePluginHost, env, compiled = store, owner = owner).also { opened += it }

    @Test fun `the second open runs the cached bytecode and behaves exactly like the first`() = runBlocking {
        val store = Recording(cache())
        val first = open(store)
        assertEquals(listOf("_shared.prelude", "plug.script"), store.writes)
        assertTrue(store.hits.isEmpty())
        val second = open(store)
        assertEquals(listOf("_shared.prelude", "plug.script"), store.hits)
        assertEquals("no new write on a hit", 2, store.writes.size)
        assertEquals(first.exports, second.exports)
        assertEquals(setOf("search", "resolve"), second.exports)
        val arg = "{\"q\":\"metropolis\"}"
        assertEquals(first.call("search", arg, 5_000), second.call("search", arg, 5_000))
        assertEquals("[{\"title\":\"METROPOLIS:1:none\"}]", second.call("search", arg, 5_000))
        assertEquals(first.call("resolve", "null", 5_000), second.call("resolve", "null", 5_000))
    }

    @Test fun `bytes the engine cannot read are dropped and the plugin loads from source`() = runBlocking {
        val store = Recording(cache())
        open(store)
        store.override = { owner, _ -> if (owner == "plug") byteArrayOf(0x7f, 1, 2, 3) else null }
        val rt = open(store)
        assertEquals(setOf("search", "resolve"), rt.exports)
        assertEquals(listOf("plug", PluginBytecodeCache.SHARED_OWNER), store.discards)
        store.override = null
        assertEquals("recompiled and kept again", 4, store.writes.size)
    }

    @Test fun `cached bytecode whose module throws at load falls back to the real source`() = runBlocking {
        val store = Recording(cache())
        open(store)
        // Valid bytecode, but of another module, one that throws at top level.
        val js = com.dokar.quickjs.QuickJs.create(kotlinx.coroutines.Dispatchers.Default)
        val throwing = try { js.compile("throw new Error('stale')", "plugin.js", true) } finally { js.close() }
        store.override = { owner, _ -> if (owner == "plug") throwing else null }
        val rt = open(store)
        assertEquals(setOf("search", "resolve"), rt.exports)
        assertEquals("[{\"title\":\"X:1:none\"}]", rt.call("search", "{\"q\":\"x\"}", 5_000))
        assertEquals(listOf("plug", PluginBytecodeCache.SHARED_OWNER), store.discards)
    }

    @Test fun `a script that fails to load is never cached`() = runBlocking {
        val store = Recording(cache())
        val failed = runCatching { open(store, src = "export function x() {}\nthrow new Error('boom')") }
        assertTrue(failed.isFailure)
        assertTrue(store.writes.isEmpty())
        assertNull(cache().read("plug", "script", "export function x() {}\nthrow new Error('boom')"))
    }

    @Test fun `without a store or owner nothing is read or written`() = runBlocking {
        val store = Recording(cache())
        PluginRuntime.open("bc", script, ProbePluginHost, env, compiled = store, owner = null).also { opened += it }
        open(null)
        assertTrue(store.writes.isEmpty() && store.hits.isEmpty())
    }

    /**
     * Not a pass/fail timing: prints what a cold open costs from source vs from cached bytecode on
     * this machine, for a large script shaped like a converted Nuvio plugin (vendored libraries).
     */
    @Test fun `prints open time from source vs cached bytecode`() = runBlocking {
        val vendor = PluginPrelude.nuvioVendor("cheerio.js") + "\n" + PluginPrelude.nuvioVendor("crypto-js.js")
        val sizes = listOf(500_000, 1_000_000, 2_000_000)
        val rows = mutableListOf("script bytes | open from source ms (median of 5) | open from cache ms | bytecode bytes")
        for (size in sizes) {
            val body = StringBuilder()
            var i = 0
            while (body.length < size) {
                body.append("export const lib$i = (function () { var module = { exports: {} }, exports = module.exports;\n")
                body.append(vendor).append("\nreturn module.exports })();\n")
                i++
            }
            body.append("export async function search() { return [] }\n")
            val src = body.toString()
            val c = cache()
            fun timeOpen(store: CompiledCodeStore?): Long {
                val t0 = System.nanoTime()
                val rt = runBlocking { PluginRuntime.open("bench", src, ProbePluginHost, env.copy(loadTimeoutMs = 60_000), compiled = store, owner = "bench$size") }
                val ms = (System.nanoTime() - t0) / 1_000_000
                rt.close()
                return ms
            }
            val cold = (1..5).map { timeOpen(null) }.sorted()[2]
            timeOpen(c) // fills the cache
            val warm = (1..5).map { timeOpen(c) }.sorted()[2]
            val stored = File(dir, "bench$size.script.qjsc").length()
            rows += "${src.length} | $cold | $warm | $stored"
            assertTrue(stored > 0)
        }
        println(rows.joinToString("\n"))
    }
}
