package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing one plugin runtime must never break another live one.
 *
 * quickjs-kt 1.0.0-alpha13's native `releaseGlobals` (run by `QuickJs.close`) cleared the
 * process-wide JavaVM pointer and JNI class/method caches that every other live QuickJs instance
 * uses to call back into Kotlin. After any close, a binding call from another runtime got no
 * JNIEnv: `kino.storage.get` answered null and the call died with a raw NPE, and a prelude loading
 * at that moment aborted before defining `__kinoCall`. The pool closes runtimes all the time (idle,
 * timeout, update, uninstall, the install probe), so this happened in production. The fix is in
 * the native library (see app/src/main/jniLibs/README.md and app/src/test/resources/jni/README.md).
 */
class PluginRuntimeIsolationTest {
    private class Host(private val onConfig: () -> Unit = {}) : PluginHost {
        val storage = mutableMapOf("k" to "v")
        override suspend fun fetch(requestJson: String) =
            "{\"ok\":false,\"status\":503,\"url\":\"https://x.example/\",\"headers\":{},\"text\":\"\"}"
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String) = storage[key]
        override fun storageSet(key: String, value: String, ttlMs: Long?) { storage[key] = value }
        override fun storageRemove(key: String) { storage.remove(key) }
        override fun log(level: String, message: String) = Unit
        override fun config(): String { onConfig(); return "{}" }
    }

    private val env = PluginEnv(appVersion = "9.9.9")
    private val opened = mutableListOf<PluginRuntime>()

    @After fun closeAll() = opened.forEach { it.close() }

    private fun open(label: String, script: String, host: PluginHost = Host()): PluginRuntime =
        runBlocking { PluginRuntime.open(label, script, host, env) }.also { opened += it }

    private fun uniqueLabel(name: String) = "$name-${System.nanoTime()}"

    /**
     * Closes [rt] and blocks until its native close has really run: `close()` only schedules it on
     * the runtime's own thread, which is shut down right after `QuickJs.close()` returns.
     */
    private fun closeAndWait(rt: PluginRuntime, label: String) {
        rt.close()
        // Prefix match: kotlinx-coroutines' debug mode suffixes live thread names with " @coroutine#N".
        fun alive() = Thread.getAllStackTraces().keys.any { it.name.startsWith("plugin-$label") }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && alive()) Thread.sleep(10)
        assertFalse("runtime $label never finished closing", alive())
    }

    private val readsStorage = "export async function home() { return [kino.storage.get('k'), kino.storage.get('missing')] }"

    @Test fun `closing one runtime leaves another live runtime's bindings working`() {
        val b = open(uniqueLabel("b"), readsStorage)
        val aLabel = uniqueLabel("a")
        val a = open(aLabel, "export async function home() { return [] }")
        closeAndWait(a, aLabel)
        assertEquals("[\"v\",null]", runBlocking { b.call("home", "null", 5_000) })
    }

    @Test fun `closing several runtimes in a row never breaks the survivor, sync or async bindings`() {
        val b = open(
            uniqueLabel("b"),
            "export async function home() { const r = await kino.fetch('https://x.example/'); return [kino.storage.get('k'), r.ok] }",
        )
        repeat(3) {
            val label = uniqueLabel("a$it")
            closeAndWait(open(label, "export async function home() { return [] }"), label)
            assertEquals("[\"v\",false]", runBlocking { b.call("home", "null", 5_000) })
        }
    }

    @Test fun `a runtime opened after another closed defines __kinoCall and calls back into Kotlin`() {
        val aLabel = uniqueLabel("a")
        closeAndWait(open(aLabel, "export async function home() { return [] }"), aLabel)
        val c = open(uniqueLabel("c"), readsStorage)
        assertEquals("[\"v\",null]", runBlocking { c.call("home", "null", 5_000) })
    }

    @Test fun `a runtime whose prelude load sees another close still gets a working __kinoCall`() {
        val aLabel = uniqueLabel("a")
        val a = open(aLabel, "export async function home() { return [] }")
        // prelude.js calls kino's native config() while loading: close A right there, mid-load.
        val c = open(uniqueLabel("c"), readsStorage, Host(onConfig = { closeAndWait(a, aLabel) }))
        assertEquals("[\"v\",null]", runBlocking { c.call("home", "null", 5_000) })
    }

    @Test fun `a prelude that aborts before defining __kinoCall fails open instead of succeeding silently`() {
        // What a failed JNI callback looks like from JS: the engine unwinds with a `null` exception,
        // which alpha13's evaluate() reports as a normal (null) result instead of throwing.
        for (prelude in listOf("throw null", "undefined")) {
            assertThrows(prelude, PluginScriptException::class.java) {
                runBlocking { PluginRuntime.open(uniqueLabel("broken"), readsStorage, Host(), env, prelude) }
            }
        }
    }

    @Test fun `a config binding that throws fails open`() {
        val host = Host(onConfig = { throw IllegalStateException("config unavailable") })
        assertThrows(PluginScriptException::class.java) {
            runBlocking { PluginRuntime.open(uniqueLabel("broken"), readsStorage, host, env) }
        }
    }
}
