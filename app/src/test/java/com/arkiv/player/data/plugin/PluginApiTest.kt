package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK v1 `kino.*` additions that need no network: typed errors, sleep, config, storage keys. */
class PluginApiTest {
    private class Host(private val configJson: String = "{}") : PluginHost {
        val storage = LinkedHashMap<String, String>()
        override suspend fun fetch(requestJson: String) = "{}"
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String) = storage[key]
        override fun storageSet(key: String, value: String) { storage[key] = value }
        override fun storageRemove(key: String) { storage.remove(key) }
        override fun storageKeys(): String = JSONArray(storage.keys.toList()).toString()
        override fun log(level: String, message: String) = Unit
        override fun config(): String = configJson
    }

    private val opened = mutableListOf<PluginRuntime>()
    private suspend fun open(script: String, host: PluginHost = Host()) =
        PluginRuntime.open("api", script, host, PluginEnv(appVersion = "9.9.9")).also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    private fun home(body: String, host: PluginHost = Host()): String = runBlocking {
        open("export async function home() { $body }", host).call("home", "null", 10_000)
    }

    @Test fun `every new function is frozen and can't be renamed`() {
        val out = home(
            """
            const fns = [kino.sleep, kino.error, kino.config.get, kino.config.all, kino.storage.get, kino.storage.set, kino.storage.remove, kino.storage.keys];
            const renamed = fns.map((f) => { try { Object.defineProperty(f, 'name', { value: 'x' }); return 'renamed'; } catch (e) { return 'refused'; } });
            return [renamed.every((r) => r === 'refused'), Object.isFrozen(kino.config), Object.isFrozen(kino.storage), fns.every((f) => Object.isFrozen(f))];
            """,
        )
        assertEquals("[true,true,true,true]", out)
    }

    @Test fun `kino error builds a typed error the app reads, before or after the first await`() {
        runBlocking {
            val rt = open(
                """
                export async function search(q) { throw kino.error('auth_required', 'Falta la clave') }
                export async function home() { await null; throw kino.error('not_found', 'x'.repeat(5000)) }
                export async function resolve(r) { await null; throw kino.error('NOT A CODE', 'y') }
                """,
            )
            val pre = assertThrows(PluginErrorException::class.java) { runBlocking { rt.call("search", "{}", 5_000) } }
            assertEquals("auth_required", pre.code)
            assertEquals("Falta la clave", pre.message)
            val post = assertThrows(PluginErrorException::class.java) { runBlocking { rt.call("home", "null", 5_000) } }
            assertEquals("not_found", post.code)
            assertEquals(PluginErrors.MAX_MESSAGE_CHARS, post.message!!.length)
            assertEquals("unknown", assertThrows(PluginErrorException::class.java) { runBlocking { rt.call("resolve", "\"r\"", 5_000) } }.code)
        }
    }

    @Test fun `a plain error stays a plain script error`() {
        runBlocking {
            val rt = open("export async function home() { await null; throw new Error('boom') }")
            val e = assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("home", "null", 5_000) } }
            assertTrue(e !is PluginErrorException)
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test fun `a hostile code getter or a forged huge code never reaches an error name`() {
        runBlocking {
            val rt = open(
                """
                export async function home() { await null; const e = new Error('m'); Object.defineProperty(e, 'code', { get() { throw 1 } }); throw e }
                export async function search() { await null; const e = new Error('m'); e.code = 'a'.repeat(30 * 1024 * 1024); throw e }
                """,
            )
            val a = assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("home", "null", 10_000) } }
            assertTrue(a !is PluginErrorException)
            val b = assertThrows(PluginScriptException::class.java) { runBlocking { rt.call("search", "{}", 10_000) } }
            assertTrue(b !is PluginErrorException)
            assertTrue((b.message?.length ?: 0) <= PluginRuntime.MAX_ERROR_CHARS)
        }
    }

    @Test fun `kino sleep waits inside the call and refuses more than 5 s`() {
        val t0 = System.nanoTime()
        assertEquals("[\"ok\"]", home("await kino.sleep(300); return ['ok']"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 300)
        assertEquals("""["invalid_request"]""", home("try { await kino.sleep(5001) } catch (e) { return [e.code] }"))
        assertEquals("""["invalid_request"]""", home("try { await kino.sleep(1.5) } catch (e) { return [e.code] }"))
        assertEquals("""["invalid_request"]""", home("try { await kino.sleep('10') } catch (e) { return [e.code] }"))
    }

    @Test fun `a sleeping call still times out on its own limit`() {
        runBlocking {
            val rt = open("export async function home() { for (;;) await kino.sleep(5000) }")
            assertThrows(PluginTimeoutException::class.java) { runBlocking { rt.call("home", "null", 500) } }
        }
    }

    @Test fun `kino config is read-only and returns the stored values`() {
        val host = Host("""{"server":"http://10.0.0.2:8096","hd":true}""")
        val out = home(
            """
            const all = kino.config.all(); all.server = 'changed';
            let wrote = 'no';
            try { kino.config.server = 'x'; wrote = 'yes' } catch (e) { wrote = 'refused' }
            return [kino.config.get('server'), kino.config.get('hd'), kino.config.get('missing') === undefined, kino.config.all().server, wrote];
            """,
            host,
        )
        assertEquals("""["http://10.0.0.2:8096",true,true,"http://10.0.0.2:8096","refused"]""", out)
    }

    @Test fun `kino storage keys lists what is stored and holds 256 KB`() {
        val host = Host()
        assertEquals("""["a","b"]""", home("kino.storage.set('a', '1'); kino.storage.set('b', 'x'.repeat(200000)); return kino.storage.keys()", host))
    }

    @Test fun `limits handed to the prelude come from their Kotlin owners`() {
        val l = JSONObject(PluginRuntime.limits())
        assertEquals(PluginStorage.MAX_BYTES, l.getInt("storageMaxBytes"))
        assertEquals(PluginRuntime.MAX_SLEEP_MS, l.getInt("sleepMaxMs"))
        assertEquals(PluginErrors.MAX_MESSAGE_CHARS, l.getInt("maxErrorMessageChars"))
        assertEquals(PluginRuntime.MAX_LOG_CHARS, l.getInt("maxLogChars"))
        assertEquals(PluginRuntime.MAX_ERROR_CHARS, l.getInt("maxErrorChars"))
    }
}
