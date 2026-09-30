package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser Web Crypto API (`crypto.subtle`, `crypto.getRandomValues`, `crypto.randomUUID`) the
 * Nuvio shim gives converted scrapers: PelisPlusHD's Embed69 resolver hashes a proof of work with
 * `crypto.subtle.digest` and decrypts its links with AES-CBC, and failed in Kino with
 * `'crypto' is not defined`. Every test runs a converted scraper through the REAL QuickJS sandbox;
 * the expected bytes were computed with Node's own `crypto.webcrypto` (same inputs, see each test).
 */
class NuvioWebCryptoTest {
    private val scraper = NuvioScraperEntry(
        id = "cryptosrc", name = "CryptoSrc", filename = "providers/cryptosrc.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    private fun convert(source: String) = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo")

    /**
     * Runs [body] (the inside of an async function that `return`s a JSON-serializable value) as the
     * scraper's `getStreams`, and answers that value. A rejection is reported as `{"error": name}`.
     */
    private fun run(body: String, timeoutMs: Long = 10_000): Any = runBlocking {
        val source = """
            var hex = function (buf) { return Array.prototype.map.call(new Uint8Array(buf), function (v) { return (v < 16 ? "0" : "") + v.toString(16); }).join(""); };
            var bytes = function (h) { var out = new Uint8Array(h.length / 2); for (var i = 0; i < out.length; i++) out[i] = parseInt(h.substr(i * 2, 2), 16); return out; };
            var seq = function (start, n) { var out = new Uint8Array(n); for (var i = 0; i < n; i++) out[i] = start + i; return out; };
            var utf8 = function (s) { return new TextEncoder().encode(s); };
            var fox = utf8("The quick brown fox jumps over the lazy dog");
            async function body() { $body }
            async function getStreams() {
              var value;
              try { value = await body(); } catch (e) { value = { error: e && e.name, message: e && e.message }; }
              return [{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent(JSON.stringify(value)) }];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val runtime = PluginRuntime.open("webcrypto", convert(source).script, NuvioRoutingHost(emptyMap()), PluginEnv(appVersion = "1.0"))
        try {
            val url = JSONObject(runtime.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie","season":0,"episode":0}"""), timeoutMs)).getString("url")
            val json = java.net.URLDecoder.decode(url.removePrefix("https://cdn.example/"), "UTF-8")
            if (json.startsWith("{")) JSONObject(json) else if (json.startsWith("[")) JSONArray(json) else json
        } finally {
            runtime.close()
        }
    }

    private fun runString(body: String) = run(body).toString()

    // ---- digest ----

    @Test fun `digest matches the SHA-1, SHA-256, SHA-384 and SHA-512 vectors of abc`() {
        val out = run(
            """
            var r = {};
            var names = ["SHA-1", "SHA-256", "SHA-384", "SHA-512"];
            for (var i = 0; i < names.length; i++) r[names[i]] = hex(await crypto.subtle.digest(names[i], utf8("abc")));
            return r;
            """,
        ) as JSONObject
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", out.getString("SHA-1"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", out.getString("SHA-256"))
        assertEquals("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7", out.getString("SHA-384"))
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            out.getString("SHA-512"),
        )
    }

    @Test fun `digest takes the algorithm as an object in any case, and ArrayBuffer, typed-array views and DataView data`() {
        val out = run(
            """
            var backing = new Uint8Array([120, 97, 98, 99, 120]);
            var view = new Uint8Array(backing.buffer, 1, 3);
            return [
              hex(await crypto.subtle.digest({ name: "sha-256" }, utf8("abc").buffer)),
              hex(await crypto.subtle.digest("SHA-256", view)),
              hex(await crypto.subtle.digest("SHA-256", new DataView(backing.buffer, 1, 3))),
              await crypto.subtle.digest("SHA-256", utf8("abc")) instanceof ArrayBuffer,
            ];
            """,
        ) as JSONArray
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(listOf(abc, abc, abc, true), (0 until out.length()).map { out.get(it) })
    }

    @Test fun `digest rejects an unknown algorithm and a string instead of bytes`() {
        assertEquals("NotSupportedError", (run("""return await crypto.subtle.digest("MD5", utf8("abc"));""") as JSONObject).getString("error"))
        assertEquals("TypeError", (run("""return await crypto.subtle.digest("SHA-256", "abc");""") as JSONObject).getString("error"))
    }

    // ---- AES ----

    @Test fun `AES-CBC encrypts like a browser and decrypts back`() {
        val out = run(
            """
            var key = await crypto.subtle.importKey("raw", seq(0, 32), { name: "AES-CBC" }, false, ["encrypt", "decrypt"]);
            var ct = await crypto.subtle.encrypt({ name: "AES-CBC", iv: seq(0xa0, 16) }, key, fox);
            var pt = await crypto.subtle.decrypt({ name: "AES-CBC", iv: seq(0xa0, 16) }, key, ct);
            return [hex(ct), new TextDecoder().decode(pt)];
            """,
        ) as JSONArray
        assertEquals("64bac78bf92341822f9cb229d5199006d90c3bab4f7eaa663a51e8c75086e82cabdf122926fa3b24d4f3c9bafa5236b6", out.getString(0))
        assertEquals("The quick brown fox jumps over the lazy dog", out.getString(1))
    }

    @Test fun `AES-CBC with the wrong key rejects with OperationError, not garbage`() {
        val out = run(
            """
            var good = await crypto.subtle.importKey("raw", seq(0, 32), { name: "AES-CBC" }, false, ["encrypt"]);
            var bad = await crypto.subtle.importKey("raw", seq(1, 32), { name: "AES-CBC" }, false, ["decrypt"]);
            var ct = await crypto.subtle.encrypt({ name: "AES-CBC", iv: seq(0xa0, 16) }, good, fox);
            return await crypto.subtle.decrypt({ name: "AES-CBC", iv: seq(0xa0, 16) }, bad, ct);
            """,
        ) as JSONObject
        assertEquals("OperationError", out.getString("error"))
    }

    @Test fun `a key is refused for a use it was not imported for, and a wrong key size is a DataError`() {
        val misuse = run(
            """
            var key = await crypto.subtle.importKey("raw", seq(0, 16), { name: "AES-CBC" }, false, ["decrypt"]);
            return await crypto.subtle.encrypt({ name: "AES-CBC", iv: seq(0, 16) }, key, fox);
            """,
        ) as JSONObject
        assertEquals("InvalidAccessError", misuse.getString("error"))
        val size = run("""return await crypto.subtle.importKey("raw", seq(0, 20), { name: "AES-CBC" }, false, ["decrypt"]);""") as JSONObject
        assertEquals("DataError", size.getString("error"))
    }

    @Test fun `AES-CTR matches the browser for a 128-bit counter and for an 8-bit counter that wraps`() {
        val out = run(
            """
            var key = await crypto.subtle.importKey("raw", seq(0, 16), "AES-CTR", false, ["encrypt", "decrypt"]);
            var counter = seq(0xf0, 16); counter[15] = 0xfe;
            var full = await crypto.subtle.encrypt({ name: "AES-CTR", counter: counter, length: 128 }, key, fox);
            var wrap = await crypto.subtle.encrypt({ name: "AES-CTR", counter: counter, length: 8 }, key, fox);
            var back = await crypto.subtle.decrypt({ name: "AES-CTR", counter: counter, length: 8 }, key, wrap);
            return [hex(full), hex(wrap), new TextDecoder().decode(back)];
            """,
        ) as JSONArray
        assertEquals("fe0038a4c7c032c0c69c9dc3509a4bfd00c8bfc85e275c38e471b17156648dd9dae4f76cd6e4458dc0c214", out.getString(0))
        assertEquals("fe0038a4c7c032c0c69c9dc3509a4bfd00c8bfc85e275c38e471b17156648dd91eb5cba7c6d946d1a7ad85", out.getString(1))
        assertEquals("The quick brown fox jumps over the lazy dog", out.getString(2))
    }

    @Test fun `AES-GCM appends the tag like a browser, with additionalData, a 96-bit tag and a 16-byte iv`() {
        val out = run(
            """
            var key = await crypto.subtle.importKey("raw", seq(0, 16), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
            var aad = utf8("header");
            var full = await crypto.subtle.encrypt({ name: "AES-GCM", iv: seq(0xb0, 12), additionalData: aad }, key, fox);
            var short = await crypto.subtle.encrypt({ name: "AES-GCM", iv: seq(0xb0, 12), additionalData: aad, tagLength: 96 }, key, fox);
            var iv16 = await crypto.subtle.encrypt({ name: "AES-GCM", iv: seq(0xa0, 16) }, key, fox);
            var dec = new TextDecoder();
            return [hex(full), hex(short), hex(iv16),
              dec.decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: seq(0xb0, 12), additionalData: aad }, key, full)),
              dec.decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: seq(0xb0, 12), additionalData: aad, tagLength: 96 }, key, short)),
              dec.decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: seq(0xa0, 16) }, key, iv16))];
            """,
        ) as JSONArray
        assertEquals("0aa1d5bdb6e2425cd50a3974ba0d71c6bf3c2f57a3977a96d2d668cfdf6102b3f39a6d47c318fac21661c9c571480381ab31575d67a057dfae05ee", out.getString(0))
        assertEquals("0aa1d5bdb6e2425cd50a3974ba0d71c6bf3c2f57a3977a96d2d668cfdf6102b3f39a6d47c318fac21661c9c571480381ab31575d67a057", out.getString(1))
        assertEquals("6a31336dc670bf36784457a76a260e34b70d3816b4d8d2593f3056ade661a92ed3f67b2a4324e54cdb4762fcba278d77610f608b84ff2a228d85df", out.getString(2))
        for (i in 3..5) assertEquals("The quick brown fox jumps over the lazy dog", out.getString(i))
    }

    @Test fun `a tampered AES-GCM tag rejects with OperationError, with the full and with a short tag`() {
        for (tagLength in listOf(128, 96)) {
            val out = run(
                """
                var key = await crypto.subtle.importKey("raw", seq(0, 16), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
                var ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: seq(0xb0, 12), tagLength: $tagLength }, key, fox));
                ct[ct.length - 1] ^= 1;
                return await crypto.subtle.decrypt({ name: "AES-GCM", iv: seq(0xb0, 12), tagLength: $tagLength }, key, ct);
                """,
            ) as JSONObject
            assertEquals("tagLength $tagLength", "OperationError", out.getString("error"))
        }
    }

    // ---- HMAC ----

    @Test fun `HMAC signs like a browser with SHA-1, SHA-256, SHA-384 and SHA-512, and verifies`() {
        val out = run(
            """
            var r = {};
            var names = ["SHA-1", "SHA-256", "SHA-384", "SHA-512"];
            for (var i = 0; i < names.length; i++) {
              var key = await crypto.subtle.importKey("raw", utf8("key"), { name: "HMAC", hash: { name: names[i] } }, false, ["sign", "verify"]);
              var sig = await crypto.subtle.sign("HMAC", key, fox);
              var bent = new Uint8Array(sig.slice(0)); bent[0] ^= 1;
              r[names[i]] = [hex(sig), await crypto.subtle.verify("HMAC", key, sig, fox), await crypto.subtle.verify({ name: "HMAC" }, key, bent, fox)];
            }
            return r;
            """,
        ) as JSONObject
        val expected = mapOf(
            "SHA-1" to "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9",
            "SHA-256" to "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            "SHA-384" to "d7f4727e2c0b39ae0f1e40cc96f60242d5b7801841cea6fc592c5d3e1ae50700582a96cf35e1e554995fe4e03381c237",
            "SHA-512" to "b42af09057bac1e2d41708e48a902e09b5ff7f12ab428a4fe86653c73dd248fb82f948a549f7b791a5b41915ee4d1ec3935357e4e2317250d0372afa2ebeeb3a",
        )
        for ((name, sig) in expected) {
            val row = out.getJSONArray(name)
            assertEquals(name, sig, row.getString(0))
            assertEquals(name, true, row.getBoolean(1))
            assertEquals(name, false, row.getBoolean(2))
        }
    }

    // ---- keys ----

    @Test fun `exportKey gives an extractable key's raw bytes back and refuses a non-extractable one`() {
        val out = run(
            """
            var open = await crypto.subtle.importKey("raw", seq(0, 16), { name: "AES-GCM" }, true, ["encrypt"]);
            var shut = await crypto.subtle.importKey("raw", seq(0, 16), { name: "AES-GCM" }, false, ["encrypt"]);
            var refused;
            try { await crypto.subtle.exportKey("raw", shut); } catch (e) { refused = e.name; }
            return [hex(await crypto.subtle.exportKey("raw", open)), refused, open.type, open.algorithm.name, open.algorithm.length, open.extractable];
            """,
        ) as JSONArray
        assertEquals("000102030405060708090a0b0c0d0e0f", out.getString(0))
        assertEquals("InvalidAccessError", out.getString(1))
        assertEquals("secret", out.getString(2))
        assertEquals("AES-GCM", out.getString(3))
        assertEquals(128, out.getInt(4))
        assertEquals(true, out.getBoolean(5))
    }

    // ---- random ----

    @Test fun `getRandomValues fills integer arrays in place, refuses more than 65536 bytes, and randomUUID is a v4 UUID`() {
        val out = run(
            """
            var a = new Uint8Array(64);
            var same = crypto.getRandomValues(a) === a;
            var nonZero = Array.prototype.some.call(a, function (v) { return v !== 0; });
            var words = crypto.getRandomValues(new Uint32Array(16384));
            var wordsNonZero = Array.prototype.some.call(words, function (v) { return v !== 0; });
            var tooBig, notInt;
            try { crypto.getRandomValues(new Uint8Array(65537)); } catch (e) { tooBig = e.name; }
            try { crypto.getRandomValues(new Float64Array(4)); } catch (e) { notInt = e.name; }
            return [same, nonZero, wordsNonZero, tooBig, notInt, crypto.randomUUID(), crypto.getRandomValues(new Uint8Array(0)).length];
            """,
        ) as JSONArray
        assertEquals(true, out.getBoolean(0))
        assertEquals(true, out.getBoolean(1))
        assertEquals(true, out.getBoolean(2))
        assertEquals("QuotaExceededError", out.getString(3))
        assertEquals("TypeMismatchError", out.getString(4))
        assertTrue(out.getString(5), Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(out.getString(5)))
        assertEquals(0, out.getInt(6))
    }

    @Test fun `the same crypto object is on globalThis, where browser-style code looks for it`() {
        assertEquals("true", runString("""return globalThis.crypto === crypto && typeof crypto.subtle.digest === "function";"""))
    }

    // ---- PelisPlusHD's own proof of work (src/pelisplushd/pow.js + crypto.js, verbatim logic) ----

    @Test fun `PelisPlusHD's Embed69 proof of work solves at difficulty 4 and its key decrypts an AES-CBC link`() {
        val started = System.nanoTime()
        val out = run(
            """
            async function sha256Hex(text) {
              var hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
              return [...new Uint8Array(hash)].map(function (v) { return v.toString(16).padStart(2, "0"); }).join("");
            }
            async function sha256Bytes(text) {
              return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
            }
            async function solvePow(challenge, difficulty, salt) {
              var prefix = "0".repeat(difficulty);
              var nonce = 0;
              while (true) {
                var h = await sha256Hex(challenge + nonce);
                if (h.startsWith(prefix)) return { nonce: nonce, aesKey: await sha256Bytes(challenge + nonce + salt) };
                nonce++;
              }
            }
            var pow = await solvePow("kino-challenge", 4, "salt");
            // The site's side: encrypt a link with the same key, iv prepended, base64 -- then the scraper's decryptAES.
            var iv = seq(0x10, 16);
            var sealed = await crypto.subtle.importKey("raw", pow.aesKey.slice(0, 32), { name: "AES-CBC" }, false, ["encrypt"]);
            var ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-CBC", iv: iv }, sealed, utf8("https://video.example/master.m3u8")));
            var raw = new Uint8Array(16 + ct.length); raw.set(iv, 0); raw.set(ct, 16);
            var b64 = btoa(String.fromCharCode.apply(null, raw));
            var bin = atob(b64); var all = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) all[i] = bin.charCodeAt(i);
            var key = await crypto.subtle.importKey("raw", pow.aesKey.slice(0, 32), { name: "AES-CBC" }, false, ["decrypt"]);
            var link = new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-CBC", iv: all.slice(0, 16) }, key, all.slice(16)));
            return [pow.nonce, await sha256Hex("kino-challenge" + pow.nonce), link];
            """,
            timeoutMs = 60_000,
        ) as JSONArray
        val ms = (System.nanoTime() - started) / 1_000_000
        println("NuvioWebCryptoTest PoW difficulty 4: nonce=${out.getInt(0)} in $ms ms")
        assertTrue(out.getString(1), out.getString(1).startsWith("0000"))
        assertEquals("https://video.example/master.m3u8", out.getString(2))
    }

    // ---- bundling ----

    @Test fun `a scraper that uses crypto subtle gets crypto-js bundled even without requiring it, and one that does not stays lean`() {
        val withSubtle = convert("async function getStreams() { await crypto.subtle.digest('SHA-384', new Uint8Array(0)); return []; }\nmodule.exports = { getStreams };")
        val plain = convert("async function getStreams() { return []; }\nmodule.exports = { getStreams };")
        assertTrue(withSubtle.script.contains("var __nuvioLibCryptoJs="))
        assertFalse(plain.script.contains("var __nuvioLibCryptoJs="))
    }
}
