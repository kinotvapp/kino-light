package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quickjs-kt instantiates the Java class a JS error's `name` names (JNI FindClass). Before the
 * EngineFailure guard these made `call` throw a real Error or a fake cancellation (measured).
 */
class PluginEngineErrorNameTest {
    private val host = object : PluginHost {
        override suspend fun fetch(requestJson: String) = "{}"
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }
    private val opened = mutableListOf<PluginRuntime>()
    @After fun close() = opened.forEach { it.close() }

    // A throw before the helper's first await: the engine hands THIS error, name and all, to quickjs-kt.
    private fun helperThrowing(name: String) =
        "async function h() { const e = new Error('boom'); Object.defineProperty(e, 'name', { value: '$name' }); throw e }\n" +
            "export async function home() { try { await h() } catch (x) { return 'caught' } }"

    @Test fun `an error named after a Java class never escapes as that class`() {
        val names = listOf(
            "java.lang.OutOfMemoryError", "java.lang.StackOverflowError", "java.util.concurrent.CancellationException",
            "kotlinx.coroutines.TimeoutCancellationException", "java.lang.IllegalStateException",
        )
        for (name in names) {
            val rt = runBlocking { PluginRuntime.open("p", helperThrowing(name), host, PluginEnv(appVersion = "x")) }.also { opened += it }
            val e = assertThrows(name, PluginScriptException::class.java) { runBlocking { rt.call("home", "null", 5_000) } }
            assertTrue(name, e !is PluginTimeoutException)
        }
    }

    @Test fun `a module that throws such an error at top level fails to load, nothing more`() {
        val e = assertThrows(PluginScriptException::class.java) {
            runBlocking { PluginRuntime.open("p", "const e = new Error('x'); Object.defineProperty(e, 'name', { value: 'java.lang.OutOfMemoryError' }); throw e", host, PluginEnv(appVersion = "x")) }
        }
        assertTrue(e.message!!.contains("x"))
    }

    @Test fun `the typed error name is a legal class name that names nothing`() {
        runBlocking {
            val rt = PluginRuntime.open("p", "async function h() { throw kino.error('not_found', 'no') }\nexport async function home() { try { await h() } catch (x) { return 'caught' } }", host, PluginEnv(appVersion = "x")).also { opened += it }
            val e = assertThrows(PluginErrorException::class.java) { runBlocking { rt.call("home", "null", 5_000) } }
            assertEquals("not_found", e.code)
        }
    }
}
