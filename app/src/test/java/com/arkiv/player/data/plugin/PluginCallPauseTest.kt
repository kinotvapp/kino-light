package com.arkiv.player.data.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin call's clock stops while the person is being asked about a host on its behalf, and only
 * a call that is still running may ask (real QuickJS, real time: short budgets).
 */
class PluginCallPauseTest {
    // A broken pause shows up as a wait that never ends: fail it instead of hanging the suite.
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(20)

    private class Host(val onFetch: suspend (String) -> String) : PluginHost {
        override suspend fun fetch(requestJson: String) = onFetch(requestJson)
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }

    private fun ok(req: String) = JSONObject().put("ok", true).put("status", 200).put("url", JSONObject(req).getString("url"))
        .put("headers", JSONObject()).put("text", "1").toString()

    private val env = PluginEnv(appVersion = "9.9.9")
    private val opened = mutableListOf<PluginRuntime>()
    @After fun closeAll() = opened.forEach { it.close() }

    private suspend fun open(script: String, calls: PluginCallTracker, onFetch: suspend (String) -> String) =
        PluginRuntime.open("pause", script, Host(onFetch), env, calls).also { opened += it }

    private val fetchOnce = "export async function resolve(r) { await kino.fetch('https://a.example/x'); return {} }"

    @Test fun `a fetch waiting on the person does not use up the call's time`() = runBlocking {
        val calls = PluginCallTracker()
        val rt = open(fetchOnce, calls) { req -> calls.current!!.clock.pausedWhile { delay(1_200) }; ok(req) }
        // 1.2 s "on the dialog" inside a 400 ms call: it still answers.
        assertEquals("{}", rt.call("resolve", "null", 400))
    }

    @Test fun `without a pause the same wait times out`() {
        val calls = PluginCallTracker()
        val rt = runBlocking { open(fetchOnce, calls) { req -> delay(1_200); ok(req) } }
        assertThrows(PluginTimeoutException::class.java) { runBlocking { rt.call("resolve", "null", 400) } }
    }

    @Test fun `a plugin that hangs after the person answered is still stopped`() {
        val calls = PluginCallTracker()
        val script = "export async function resolve(r) { await kino.fetch('https://a.example/x'); let i = 0; while (i < 400000000) i++; return {} }"
        val rt = runBlocking { open(script, calls) { req -> calls.current!!.clock.pausedWhile { delay(600) }; ok(req) } }
        val t0 = System.currentTimeMillis()
        assertThrows(PluginTimeoutException::class.java) { runBlocking { rt.call("resolve", "null", 400) } }
        val took = System.currentTimeMillis() - t0
        assertTrue("stopped after the pause plus the budget, took $took ms", took in 900..3_000)
        assertTrue(rt.isDiscarded)
    }

    @Test fun `the running call is known while it runs and forgotten once it ends`() = runBlocking {
        val calls = PluginCallTracker()
        var seen: PluginCall? = null
        val rt = open(fetchOnce, calls) { req -> seen = calls.current; ok(req) }
        assertNull(calls.current)
        rt.call("resolve", "null", 5_000)
        assertEquals("resolve", seen?.function)
        assertEquals(true, seen?.interactive)
        assertFalse("ended once the call returned", seen!!.isAlive)
        assertNull(calls.current)
    }

    @Test fun `a background call is not interactive`() = runBlocking {
        val calls = PluginCallTracker()
        var seen: PluginCall? = null
        val rt = open(fetchOnce, calls) { req -> seen = calls.current; ok(req) }
        withContext(BackgroundPluginCall) { rt.call("resolve", "null", 5_000) }
        assertEquals(false, seen?.interactive)
    }

    @Test fun `a call that timed out is over even though its script keeps running`() {
        val calls = PluginCallTracker()
        val later = CompletableDeferred<PluginCall?>()
        // The script outlives its call: its second fetch starts long after the 300 ms limit.
        val script = "export async function resolve(r) { await kino.fetch('https://a.example/slow'); await kino.fetch('https://a.example/late'); return {} }"
        val rt = runBlocking {
            open(script, calls) { req ->
                if (JSONObject(req).getString("url").endsWith("slow")) delay(800) else later.complete(calls.current)
                ok(req)
            }
        }
        assertThrows(PluginTimeoutException::class.java) { runBlocking { rt.call("resolve", "null", 300) } }
        val call = runBlocking { kotlinx.coroutines.withTimeout(5_000) { later.await() } }
        assertNull("a stray fetch after the call is over finds no call to ask for", call)
    }

    // The person leaving the screen cancels the caller: a question still on screen for that call goes.
    @Test fun `asking while alive stops when the caller gives up, and answers nothing`() = runBlocking {
        val calls = PluginCallTracker()
        val asking = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Any?>()
        val rt = open(fetchOnce, calls) { req ->
            val call = calls.current!!
            outcome.complete(call.askWhileAlive { asking.complete(Unit); awaitCancellation() })
            ok(req)
        }
        val caller = launch { runCatching { rt.call("resolve", "null", 5_000) } }
        kotlinx.coroutines.withTimeout(5_000) { asking.await() }
        caller.cancelAndJoin()
        assertNull(kotlinx.coroutines.withTimeout(5_000) { outcome.await() })
    }

    @Test fun `a call that is over asks nothing at all`() = runBlocking {
        val calls = PluginCallTracker()
        var seen: PluginCall? = null
        val rt = open(fetchOnce, calls) { req -> seen = calls.current; ok(req) }
        rt.call("resolve", "null", 5_000)
        var ran = false
        assertNull(seen!!.askWhileAlive { ran = true; true })
        assertFalse(ran)
    }
}
