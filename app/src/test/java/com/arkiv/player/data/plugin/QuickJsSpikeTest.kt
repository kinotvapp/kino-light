package com.arkiv.player.data.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pins the quickjs-kt 1.0.0-alpha13 behaviour PluginRuntime is built on. Every quirk asserted here
 * was measured on 2026-09-24; if a library bump changes one, the failing test names it.
 */
class QuickJsSpikeTest {
    private lateinit var js: QuickJs
    private val logged = mutableListOf<String>()

    @Before fun setUp() {
        js = QuickJs.create(Dispatchers.Default)
        js.memoryLimit = 64L * 1024 * 1024
        js.maxStackSize = 1024L * 1024
        js.define("host") {
            asyncFunction("fetchText") { args -> delay(10); "fetched:" + args[0] }
            asyncFunction("fail") { throw IllegalStateException("host no permitido: x") }
            function("log") { args -> logged += args.joinToString(" ") }
        }
    }

    @After fun tearDown() = js.close()

    private suspend fun loadModule(code: String) {
        js.addModule("plugin.js", code)
        js.evaluate<Any?>(
            code = "import * as p from 'plugin.js'; globalThis.__exports = p;",
            filename = "loader.js",
            asModule = true,
        )
        // alpha13: the first global evaluate after a module evaluate throws; absorb it.
        runCatching { js.evaluate<Any?>("0") }
    }

    @Test fun `exported async function is callable with top-level await`() = runBlocking {
        loadModule("export async function search(q) { return [await host.fetchText(q.q)] }")
        assertEquals("search", js.evaluate<String>("Object.keys(__exports).join(',')"))
        assertEquals("[\"fetched:x\"]", js.evaluate<String>("JSON.stringify(await __exports.search({ q: 'x' }))"))
    }

    @Test fun `a returned promise is not awaited without top-level await`() = runBlocking {
        assertTrue(js.evaluate<Any?>("Promise.resolve(7)").toString().startsWith("Promise"))
    }

    @Test fun `first global evaluate after a module evaluate throws`() = runBlocking {
        js.addModule("m.js", "export const a = 1")
        js.evaluate<Any?>(code = "import * as m from 'm.js'; globalThis.m = m;", filename = "l.js", asModule = true)
        try {
            js.evaluate<Any?>("0")
            fail("alpha13 glitch is gone: drop the throwaway evaluate in PluginRuntime.open")
        } catch (e: QuickJsException) {
            assertTrue(e.message!!.contains("'value'"))
        }
        assertEquals(1L, js.evaluate<Long>("m.a"))
    }

    @Test fun `a binding failure is catchable in JS with its message`() = runBlocking {
        val out = js.evaluate<String>(
            "await (async () => { try { await host.fail(); return 'no' } catch (e) { return e.message } })()",
        )
        assertEquals("host no permitido: x", out)
    }

    @Test fun `known limitation - a throw before the first await aborts the call even if caught`() = runBlocking {
        try {
            js.evaluate<Any?>(
                "await (async () => { async function t() { throw new Error('sync') } " +
                    "try { await t() } catch (e) { return 'caught' } })()",
            )
            fail("alpha13 limitation is gone: update docs/plugins/README.md and Spec deviation 1")
        } catch (e: QuickJsException) {
            // The message carries the JS stack after the first line.
            assertTrue(e.message!!.startsWith("Error: sync"))
        }
    }

    @Test fun `a throw after the first await is caught normally`() = runBlocking {
        val out = js.evaluate<String>(
            "await (async () => { async function t() { await host.fetchText('a'); throw new Error('late') } " +
                "try { await t() } catch (e) { return 'caught ' + e.message } })()",
        )
        assertEquals("caught late", out)
    }

    @Test fun `memory limit stops a runaway allocation`() = runBlocking {
        try {
            js.evaluate<Any?>("let a = []; while (true) a.push(new Array(100000).fill(1));")
            fail("expected out of memory")
        } catch (e: QuickJsException) {
            // The message is sometimes null: the engine can run out of memory building it. And the
            // runtime is NOT reliably usable afterwards (measured: the next evaluate threw "Result
            // promise not found"), which is why PluginRuntime health-checks after a script error.
        }
    }

    @Test fun `stack limit stops infinite recursion`() = runBlocking {
        try {
            js.evaluate<Any?>("function f() { return f() + 1 }; f()")
            fail("expected stack overflow")
        } catch (e: QuickJsException) {
            assertTrue(e.message!!.contains("stack overflow"))
        }
    }

    @Test fun `no browser globals exist`() = runBlocking {
        assertEquals(
            "undefined undefined undefined undefined",
            js.evaluate<String>("[typeof console, typeof setTimeout, typeof URL, typeof atob].join(' ')"),
        )
    }

    @Test fun `a syntax error surfaces from addModule`() {
        try {
            js.addModule("bad.js", "export async function (")
            fail("expected a syntax error")
        } catch (e: QuickJsException) {
            assertTrue(e.message!!.startsWith("SyntaxError"))
        }
    }
}
