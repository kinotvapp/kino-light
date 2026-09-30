package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `kino.crypto` inside the real QuickJS runtime: the JS half, its caps and its errors. */
class PluginCryptoApiTest {
    private class Host : PluginHost {
        val cryptoRequests = mutableListOf<Int>()
        override suspend fun fetch(requestJson: String) = "{}"
        override fun select(html: String, css: String) = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
        override fun crypto(opJson: String): String { cryptoRequests += opJson.length; return PluginCrypto.run(opJson) }
    }

    private val opened = mutableListOf<PluginRuntime>()
    private suspend fun open(script: String, host: PluginHost = Host()) =
        PluginRuntime.open("api", script, host, PluginEnv(appVersion = "9.9.9")).also { opened += it }

    @After fun closeAll() = opened.forEach { it.close() }

    private fun home(body: String, host: PluginHost = Host(), timeoutMs: Long = 10_000): String = runBlocking {
        open("export async function home() { $body }", host).call("home", "null", timeoutMs)
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

    @Test fun `a 4 MB encrypt-decrypt round trip with default options succeeds`() {
        // Regression for the JS-side cap using the WRONG default encoding for decrypt's "data": the
        // Kotlin default is base64 in (encrypt gives base64 out), not utf8 -- so a plaintext this
        // size, once base64-encoded as ciphertext, is well past the old utf8-sized char cap even
        // though it is under the real 5 MB byte cap both ways.
        val out = home(
            """
            const key = '2b7e151628aed2a6abf7158809cf4f3c', iv = '000102030405060708090a0b0c0d0e0f';
            const plain = 'a'.repeat(4 * 1024 * 1024);
            const enc = kino.crypto.encrypt('aes-128-cbc', { key, iv, keyEncoding: 'hex', ivEncoding: 'hex', data: plain });
            const dec = kino.crypto.decrypt('aes-128-cbc', { key, iv, keyEncoding: 'hex', ivEncoding: 'hex', data: enc });
            return dec === plain;
            """,
            timeoutMs = 30_000,
        )
        assertEquals("true", out)
    }

    @Test fun `a null option value is treated as absent, not as the encoding null`() {
        val out = home(
            """
            return [kino.crypto.hash('md5', 'abc', { outputEncoding: null }), kino.crypto.encrypt('aes-128-cbc', {
              key: '2b7e151628aed2a6abf7158809cf4f3c', iv: '000102030405060708090a0b0c0d0e0f',
              keyEncoding: 'hex', ivEncoding: 'hex', data: 'hola', padding: null,
            })];
            """,
        )
        assertEquals("""["900150983cd24fb0d6963f7d28e17f72","CchaOS4YYMPsG9lTevKrUA=="]""", out)
    }

    @Test fun `hostile input is caught, not crashed, by every crypto function that takes an object or a number`() {
        val out = home(
            """
            const evilKey = {}; Object.defineProperty(evilKey, 'key', { get() { throw new Error('evil getter'); } });
            const evilAlg = { toString() { throw new Error('evil toString'); } };
            const results = [];
            try { kino.crypto.hash(evilAlg, 'x'); results.push('hash:no-throw'); } catch (e) { results.push('hash:caught'); }
            try { kino.crypto.hmac(evilAlg, 'key', 'x'); results.push('hmac:no-throw'); } catch (e) { results.push('hmac:caught'); }
            try { kino.crypto.encrypt('aes-128-cbc', evilKey); results.push('encrypt:no-throw'); } catch (e) { results.push('encrypt:caught'); }
            try { kino.crypto.decrypt('aes-128-cbc', evilKey); results.push('decrypt:no-throw'); } catch (e) { results.push('decrypt:caught'); }
            try { kino.crypto.pbkdf2('sha1', 'p', 's', {}, 20); results.push('pbkdf2:no-throw'); } catch (e) { results.push('pbkdf2:caught'); }
            try { kino.crypto.randomBytes('nope'); results.push('random:no-throw'); } catch (e) { results.push('random:caught'); }
            return results;
            """,
        )
        assertEquals(
            """["hash:caught","hmac:caught","encrypt:caught","decrypt:caught","pbkdf2:caught","random:caught"]""",
            out,
        )
    }

    @Test fun `crypto still copies every field correctly when the plugin breaks the array iterator`() {
        val out = home(
            """
            Array.prototype[Symbol.iterator] = function* () {};
            return kino.crypto.hash('md5', 'abc');
            """,
        )
        assertEquals(""""900150983cd24fb0d6963f7d28e17f72"""", out)
    }

    // --- Sealed secrets (spec 2026-09-29-plugin-sealed-secrets §5): a marker is accepted in the
    // key-like parameters and replaced in Kotlin; in a data input, or in iv/aad under a plain key,
    // it is refused. The expected values are what PluginCrypto gives for the plain values. ---

    @get:Rule val tmp = TemporaryFolder()

    private val plain = mapOf(
        "aesKey" to "0123456789abcdef", "aesIv" to "fedcba9876543210", "desKey" to "0123456789abcdefghijklmn", "short" to "8bytes!!", "hmacKey" to "hm4c-k3y", "password" to "p4ss", "salt" to "s4lt-v4lue",
    )
    /** How many seals were opened: once each at most (the first redaction opens them all), however they are used. */
    private val opens = java.util.concurrent.atomic.AtomicInteger()
    private val secrets = PluginSecrets(
        plain.mapValues { (name, value) -> TestSealing.seal(value, "owner/repo", name) }, "owner/repo",
        { peer -> opens.incrementAndGet(); TestSealing.agreement.sharedSecret(peer) },
        sealedHosts = listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
    )
    private val refused = "no se puede usar un dato sellado aquí"

    private fun sealedHost() = DefaultPluginHost(
        "api", PluginHttp(OkHttpClient(), "api", EffectiveHosts(listOf("api.example.com")), "9.9.9"),
        PluginStorage(tmp.root.resolve("s-${System.nanoTime()}.json")), logger = {}, secrets = secrets,
    )

    private fun sealedHome(body: String): String = home("const s = (n) => kino.secret(n);\n$body", sealedHost())

    private fun expected(op: JSONObject): String = JSONObject(PluginCrypto.run(op.toString())).getString("ok")

    private val hexIv = "000102030405060708090a0b0c0d0e0f"

    @Test fun `a sealed key decrypts what that key encrypted`() {
        val ct = expected(JSONObject().put("op", "encrypt").put("alg", "aes-128-cbc").put("key", plain["aesKey"]).put("iv", hexIv).put("ivEnc", "hex").put("data", "hola mundo"))
        val out = sealedHome("return kino.crypto.decrypt('aes-128-cbc', { key: s('aesKey'), iv: '$hexIv', ivEncoding: 'hex', data: '$ct' })")
        assertEquals("\"hola mundo\"", out)
    }

    // A sealed value inside a longer cipher key leaves the rest of the key known: des-ede3 with
    // `m + 'A'.repeat(16)` peels down to single DES under the secret's first 8 bytes (2^56).
    @Test fun `a sealed cipher key must be exactly one marker`() {
        val out = sealedHome(
            """
            const m = s('short'), tries = [
              () => kino.crypto.encrypt('des-ede3-cbc', { key: m + 'A'.repeat(16), iv: '0001020304050607', ivEncoding: 'hex', data: 'hola' }),
              () => kino.crypto.encrypt('des-ede3-ecb', { key: m + m + m, data: 'hola' }),
              () => kino.crypto.encrypt('aes-128-ecb', { key: 'x' + s('aesKey'), data: 'hola' }),
              () => kino.crypto.decrypt('aes-128-ecb', { key: s('aesKey') + '', data: 'AAAAAAAAAAAAAAAAAAAAAA==', padding: 'none', outputEncoding: 'hex' }),
              () => kino.crypto.decrypt('aes-256-ecb', { key: s('aesKey') + s('aesIv'), data: 'AAAAAAAAAAAAAAAAAAAAAA==', padding: 'none', outputEncoding: 'hex' }),
            ];
            return tries.map((f) => { try { return ['no throw', f()] } catch (e) { return [e.code, e.message] } });
            """,
        )
        val refusal = "[\"crypto_error\",\"$refused\"]"
        val exact = "[\"no throw\",\"" + expected(
            JSONObject().put("op", "decrypt").put("alg", "aes-128-ecb").put("key", plain["aesKey"]).put("data", "AAAAAAAAAAAAAAAAAAAAAA==").put("padding", "none").put("out", "hex"),
        ) + "\"]"
        // `s('aesKey') + ''` is exactly the marker: it works.
        assertEquals(listOf(refusal, refusal, refusal, exact, refusal).joinToString(",", "[", "]"), out)
    }

    @Test fun `exactly one marker works as any cipher's key, and an hmac key may join two`() {
        val out = sealedHome(
            """
            return [kino.crypto.encrypt('des-ede3-ecb', { key: s('desKey'), data: 'hola' }),
              kino.crypto.hmac('sha1', s('hmacKey') + '&' + s('password'), 'base')];
            """,
        )
        val want = org.json.JSONArray()
            .put(expected(JSONObject().put("op", "encrypt").put("alg", "des-ede3-ecb").put("key", plain["desKey"]).put("data", "hola")))
            .put(expected(JSONObject().put("op", "hmac").put("alg", "sha1").put("key", plain["hmacKey"] + "&" + plain["password"]).put("data", "base")))
        assertEquals(want.toString(), out)
    }

    @Test fun `a marker in alg, an encoding or an unknown op stays literal`() {
        val host = sealedHost()
        val m = secrets.marker("aesKey")!!
        val plainKey = "2b7e151628aed2a6abf7158809cf4f3c"
        val answers = listOf(
            JSONObject().put("op", "hash").put("alg", m).put("data", "x"),
            JSONObject().put("op", "hmac").put("alg", "sha256").put("key", "k").put("keyEnc", m).put("data", "x"),
            JSONObject().put("op", "hash").put("alg", "md5").put("data", "x").put("in", m),
            JSONObject().put("op", "hash").put("alg", "md5").put("data", "x").put("out", m),
            JSONObject().put("op", "encrypt").put("alg", "aes-128-ecb").put("key", plainKey).put("keyEnc", "hex").put("data", "x").put("out", m),
            JSONObject().put("op", "nope").put("key", m).put("password", m).put("salt", m),
        ).map { JSONObject(host.crypto(it.toString())) }
        assertEquals(
            listOf(
                "algoritmo de hash desconocido: ${m.take(20)}", "codificación desconocida: ${m.take(20)}", "codificación desconocida: ${m.take(20)}",
                "codificación desconocida: ${m.take(20)}", "codificación desconocida: ${m.take(20)}", "operación desconocida",
            ),
            answers.map { it.optString("error") },
        )
        // Redacting each answer opens every seal once (so a value in it would come back as its
        // marker), never once per answer: nothing here opened one to substitute it.
        assertEquals(plain.size, opens.get())
        // And through the JS API: the prelude refuses an unknown encoding before anything crosses.
        assertEquals(
            "[\"crypto_error\",\"codificación desconocida: ${m.take(20)}\"]",
            sealedHome("try { kino.crypto.hash('md5', 'x', { outputEncoding: s('aesKey') }) } catch (e) { return [e.code, e.message] }"),
        )
        assertEquals(plain.size, opens.get())
    }

    @Test fun `pbkdf2 with a sealed salt under a plain password still refuses a marker in data`() {
        val m = secrets.marker("salt")!!
        val op = JSONObject().put("op", "pbkdf2").put("hash", "sha256").put("password", "pw").put("salt", m).put("data", m).put("iterations", 10).put("keyLength", 32)
        assertEquals(JSONObject().put("error", refused).toString(), sealedHost().crypto(op.toString()))
        assertEquals(0, opens.get())
    }

    @Test fun `hmac with a sealed key and pbkdf2 with a sealed password or salt work`() {
        val out = sealedHome(
            """
            return [kino.crypto.hmac('sha256', s('hmacKey'), 'datos'),
              kino.crypto.pbkdf2('sha256', s('password'), 'salt', 10, 32),
              kino.crypto.pbkdf2('sha256', 'pw', s('salt'), 10, 32),
              kino.crypto.pbkdf2('sha256', s('password'), s('salt'), 10, 32)];
            """,
        )
        fun pbkdf2(pw: String, salt: String) = expected(JSONObject().put("op", "pbkdf2").put("hash", "sha256").put("password", pw).put("salt", salt).put("iterations", 10).put("keyLength", 32))
        val want = org.json.JSONArray()
            .put(expected(JSONObject().put("op", "hmac").put("alg", "sha256").put("key", plain["hmacKey"]).put("data", "datos")))
            .put(pbkdf2(plain.getValue("password"), "salt")).put(pbkdf2("pw", plain.getValue("salt"))).put(pbkdf2(plain.getValue("password"), plain.getValue("salt")))
        assertEquals(want.toString(), out)
    }

    @Test fun `a sealed iv or aad is refused under a sealed key too`() {
        val out = sealedHome(
            """
            const tries = [
              () => kino.crypto.encrypt('aes-128-cbc', { key: s('aesKey'), iv: s('aesIv'), data: 'hola' }),
              () => kino.crypto.decrypt('aes-128-cbc', { key: s('aesKey'), iv: s('aesKey'), data: 'AAAAAAAAAAAAAAAAAAAAAA==' }),
              () => kino.crypto.encrypt('aes-128-gcm', { key: s('aesKey'), iv: '000102030405060708090a0b', ivEncoding: 'hex', aad: s('aesIv'), data: 'hola' }),
              () => kino.crypto.encrypt('aes-128-gcm', { key: s('aesKey'), iv: 'x' + s('aesIv'), data: 'hola' }),
            ];
            return tries.map((f) => { try { return ['no throw', f()] } catch (e) { return [e.code, e.message] } });
            """,
        )
        assertEquals(List(4) { "[\"crypto_error\",\"$refused\"]" }.joinToString(",", "[", "]"), out)
    }

    // Regression: when a sealed iv was allowed under a sealed key, the plugin got the key back in two
    // calls -- D_K(X) XOR K from CBC with the key's own marker as the iv, D_K(X) from ECB, XORed.
    @Test fun `a sealed key can't be computed back through the iv`() {
        val out = sealedHome(
            """
            const k = s('aesKey'), x = 'AAAAAAAAAAAAAAAAAAAAAA==';
            let a;
            try { a = kino.crypto.decrypt('aes-128-cbc', { key: k, iv: k, data: x, padding: 'none', outputEncoding: 'hex' }); } catch (e) { return [e.code, e.message] }
            const b = kino.crypto.decrypt('aes-128-ecb', { key: k, data: x, padding: 'none', outputEncoding: 'hex' });
            let recovered = '';
            for (let i = 0; i < 32; i += 2) recovered += String.fromCharCode(parseInt(a.substr(i, 2), 16) ^ parseInt(b.substr(i, 2), 16));
            return ['recovered', recovered];
            """,
        )
        assertEquals("[\"crypto_error\",\"$refused\"]", out)
        assertTrue(out, plain.getValue("aesKey") !in out)
    }

    @Test fun `sealed iv under a plain key is refused`() {
        val out = sealedHome(
            """
            const plainKey = { key: '2b7e151628aed2a6abf7158809cf4f3c', keyEncoding: 'hex' };
            const tries = [
              () => kino.crypto.encrypt('aes-128-cbc', { ...plainKey, iv: s('aesIv'), data: 'hola' }),
              () => kino.crypto.decrypt('aes-128-cbc', { ...plainKey, iv: s('aesIv'), data: 'AAAAAAAAAAAAAAAAAAAAAA==' }),
              () => kino.crypto.encrypt('aes-128-gcm', { ...plainKey, iv: '000102030405060708090a0b', ivEncoding: 'hex', aad: s('aesIv'), data: 'hola' }),
              () => kino.crypto.encrypt('aes-128-cbc', { key: s('aesKey').slice(1), iv: s('aesIv'), data: 'hola' }),
            ];
            return tries.map((f) => { try { return ['no throw', f()] } catch (e) { return [e.code, e.message] } });
            """,
        )
        assertEquals(List(4) { "[\"crypto_error\",\"$refused\"]" }.joinToString(",", "[", "]"), out)
    }

    @Test fun `a sealed value in any data input is refused, whole, concatenated or rebuilt from pieces`() {
        val out = sealedHome(
            """
            const k = s('aesKey'), plainKey = '2b7e151628aed2a6abf7158809cf4f3c';
            const inside = 'x' + k + 'y', rebuilt = k.slice(0, 7) + k.slice(7);
            const tries = [
              () => kino.crypto.hash('md5', k),
              () => kino.crypto.hash('sha256', inside),
              () => kino.crypto.hmac('sha256', 'key', rebuilt),
              () => kino.crypto.hmac('sha256', s('hmacKey'), k),
              () => kino.crypto.encrypt('aes-128-ecb', { key: plainKey, keyEncoding: 'hex', data: inside }),
              () => kino.crypto.encrypt('aes-128-ecb', { key: k, data: k }),
              () => kino.crypto.decrypt('aes-128-ecb', { key: k, data: rebuilt }),
            ];
            return tries.map((f) => { try { return ['no throw', f()] } catch (e) { return [e.code, e.message] } });
            """,
        )
        assertEquals(List(7) { "[\"crypto_error\",\"$refused\"]" }.joinToString(",", "[", "]"), out)
    }

    @Test fun `a piece of a marker is only text`() {
        val out = JSONObject(sealedHome("const k = s('aesKey'); return { piece: k.slice(0, -1), hash: kino.crypto.hash('md5', k.slice(0, -1)) }"))
        assertEquals(expected(JSONObject().put("op", "hash").put("alg", "md5").put("data", out.getString("piece"))), out.getString("hash"))
    }

    @Test fun `a crypto result that is an opened value comes back as its marker`() {
        val plainKey = "2b7e151628aed2a6abf7158809cf4f3c"
        val ct = expected(JSONObject().put("op", "encrypt").put("alg", "aes-128-ecb").put("key", plainKey).put("keyEnc", "hex").put("data", plain["hmacKey"]))
        val out = JSONObject(
            sealedHome(
                """
                kino.crypto.hmac('sha256', s('hmacKey'), 'x');
                return { m: s('hmacKey'), out: kino.crypto.decrypt('aes-128-ecb', { key: '$plainKey', keyEncoding: 'hex', data: '$ct' }) };
                """,
            ),
        )
        assertEquals(out.getString("m"), out.getString("out"))
    }

    @Test fun `a crypto result that is a declared value this runtime never used comes back as its marker`() {
        val plainKey = "2b7e151628aed2a6abf7158809cf4f3c"
        val ct = expected(JSONObject().put("op", "encrypt").put("alg", "aes-128-ecb").put("key", plainKey).put("keyEnc", "hex").put("data", plain["password"]))
        val out = JSONObject(
            sealedHome("return { m: s('password'), out: kino.crypto.decrypt('aes-128-ecb', { key: '$plainKey', keyEncoding: 'hex', data: '$ct' }) }"),
        )
        assertEquals(out.getString("m"), out.getString("out"))
    }

    @Test fun `a 5 MB hex encrypt-decrypt round trip (CTR, no padding growth) stays inside the call time and the 64 MB heap`() {
        val out = home(
            """
            const key = '2b7e151628aed2a6abf7158809cf4f3c', iv = 'f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff';
            const hex = 'ab'.repeat(5 * 1024 * 1024);
            const enc = kino.crypto.encrypt('aes-128-ctr', { key, iv, keyEncoding: 'hex', ivEncoding: 'hex', data: hex, inputEncoding: 'hex', outputEncoding: 'hex' });
            const dec = kino.crypto.decrypt('aes-128-ctr', { key, iv, keyEncoding: 'hex', ivEncoding: 'hex', data: enc, inputEncoding: 'hex', outputEncoding: 'hex' });
            return dec === hex;
            """,
            timeoutMs = 30_000,
        )
        assertEquals("true", out)
    }
}
