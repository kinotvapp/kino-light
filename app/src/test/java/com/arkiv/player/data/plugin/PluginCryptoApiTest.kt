package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `kino.crypto` inside the real QuickJS runtime: the JS half, its caps and its errors. */
class PluginCryptoApiTest {
    private class Host : PluginHost {
        val cryptoRequests = mutableListOf<Int>()
        override suspend fun fetch(requestJson: String) = "{}"
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
        override fun crypto(opJson: String): String { cryptoRequests += opJson.length; return PluginCrypto.run(opJson) }
    }

    private val opened = mutableListOf<PluginRuntime>()
    private suspend fun open(script: String, host: PluginHost = Host()) =
        PluginRuntime.open("api", script, host, PluginEnv(appVersion = "9.9.9")).also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    private fun home(body: String, host: PluginHost = Host()): String = runBlocking {
        open("export async function home() { $body }", host).call("home", "null", 10_000)
    }

    @Test fun `every crypto function is frozen and can't be renamed`() {
        val out = home(
            """
            const fns = [kino.crypto.hash, kino.crypto.encrypt, kino.crypto.decrypt, kino.crypto.hmac, kino.crypto.pbkdf2, kino.crypto.randomBytes, kino.crypto.uuid];
            const renamed = fns.map((f) => { try { Object.defineProperty(f, 'name', { value: 'x' }); return 'renamed'; } catch (e) { return 'refused'; } });
            return [renamed.every((r) => r === 'refused'), Object.isFrozen(kino.crypto), fns.every((f) => Object.isFrozen(f))];
            """,
        )
        assertEquals("[true,true,true]", out)
    }

    @Test fun `kino crypto hashes, encrypts and decrypts inside the runtime`() {
        val out = home(
            """
            const key = '2b7e151628aed2a6abf7158809cf4f3c', iv = '000102030405060708090a0b0c0d0e0f';
            const enc = kino.crypto.encrypt('aes-128-cbc', { key, iv, keyEncoding: 'hex', ivEncoding: 'hex', data: 'hola mundo' });
            const dec = kino.crypto.decrypt('aes-128-cbc', { key, iv, keyEncoding: 'hex', ivEncoding: 'hex', data: enc });
            return [kino.crypto.hash('md5', 'abc'), kino.crypto.hmac('sha256', 'Jefe', 'what do ya want for nothing?'), enc, dec,
              kino.crypto.pbkdf2('sha1', 'password', 'salt', 2, 20), kino.crypto.randomBytes(8).length, kino.crypto.uuid().length];
            """,
        )
        assertEquals(
            """["900150983cd24fb0d6963f7d28e17f72","5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843","kdPxu1qmZnGL3NhRRXFjKg==","hola mundo","ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957",16,36]""",
            out,
        )
    }

    @Test fun `a crypto failure carries crypto_error and is catchable in the same function before any await`() {
        val out = home("try { kino.crypto.hash('sha3', 'x'); return ['no'] } catch (e) { return [e.code, e.name, e.message] }")
        assertEquals("""["crypto_error","KinoError_crypto_error","algoritmo de hash desconocido: sha3"]""", out)
        assertEquals("""["crypto_error"]""", home("try { kino.crypto.hash('md5', 'x', { inputEncoding: 'latin1' }) } catch (e) { return [e.code] }"))
    }

    @Test fun `crypto input over 5 MB never crosses into Kotlin`() {
        val host = Host()
        val out = home("try { kino.crypto.hash('md5', 'x'.repeat(5 * 1024 * 1024 + 1)) } catch (e) { return [e.code, e.message] }", host)
        assertEquals("""["crypto_error","\"data\" pasa de 5 MB"]""", out)
        assertEquals(emptyList<Int>(), host.cryptoRequests)
    }

    @Test fun `the crypto cap holds even when the plugin breaks the array iterator`() {
        val host = Host()
        val out = home(
            """
            Array.prototype[Symbol.iterator] = function* () {};
            try { kino.crypto.hash('md5', 'ab'.repeat(6 * 1024 * 1024), { inputEncoding: 'hex' }) } catch (e) { return [e.code] }
            return ['crossed']
            """,
            host,
        )
        assertEquals("""["crypto_error"]""", out)
        assertTrue(host.cryptoRequests.all { it <= PluginRuntime.MAX_CRYPTO_REQUEST_CHARS })
    }

    @Test fun `crypto limits handed to the prelude come from their Kotlin owners`() {
        val l = JSONObject(PluginRuntime.limits())
        assertEquals(PluginCrypto.MAX_DATA_BYTES, l.getInt("cryptoMaxDataBytes"))
        assertEquals(PluginRuntime.MAX_CRYPTO_REQUEST_CHARS, l.getInt("cryptoMaxRequestChars"))
    }
}
