package com.arkiv.player.data.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PluginRuntime.openPending]: the engine starts while a sealed entry is still being opened. */
class PluginRuntimePendingScriptTest {
    private val env = PluginEnv(appVersion = "1.0")
    private val source = "export async function search(){ return [] }\nexport async function resolve(){ return null }\n"

    @Test fun `the engine is set up before the script is needed, and the module loads once it arrives`() = runBlocking<Unit> {
        val ready = CompletableDeferred<String>()
        var askedOn: String? = null
        val opening = async {
            PluginRuntime.openPending("pending", { askedOn = Thread.currentThread().name; ready.await() }, ProbePluginHost, env)
        }
        // The script is asked for on the runtime's own thread, after the engine and prelude exist.
        while (askedOn == null) kotlinx.coroutines.delay(5)
        assertTrue(askedOn, askedOn!!.startsWith("plugin-pending"))
        ready.complete(source)
        val rt = opening.await()
        try { assertEquals(setOf("search", "resolve"), rt.exports) } finally { rt.close() }
    }

    @Test fun `a damaged sealed entry comes out as itself, marked as a load failure`() {
        val damaged = PluginDamagedException()
        val e = assertThrows(PluginDamagedException::class.java) {
            runBlocking { PluginRuntime.openPending("pending", { throw damaged }, ProbePluginHost, env) }
        }
        assertSame(damaged, e)
        assertTrue(e.atLoad)
    }

    @Test fun `any other failure producing the script is a script error, never a crash`() {
        val e = assertThrows(PluginScriptException::class.java) {
            runBlocking { PluginRuntime.openPending("pending", { throw IllegalStateException("boom") }, ProbePluginHost, env) }
        }
        assertTrue(e.message!!, e.message!!.contains("boom"))
    }

    @Test fun `a plain open is unchanged`() = runBlocking<Unit> {
        val rt = PluginRuntime.open("plain", source, ProbePluginHost, env)
        try { assertEquals(setOf("search", "resolve"), rt.exports) } finally { rt.close() }
    }
}
