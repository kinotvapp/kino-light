package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Node's `require('crypto')` for converted Nuvio scrapers (EntrePeliculasySeries and MegaDede solve
 * Embed69's proof of work with `createHash('sha256')` and decrypt its links with
 * `createDecipheriv('aes-256-cbc')`). Every test runs a converted scraper through the REAL QuickJS
 * sandbox; the expected values were computed with Node's own `crypto` from the same inputs.
 */
class NuvioNodeCryptoTest {
    private val scraper = NuvioScraperEntry(
        id = "nodecrypto", name = "NodeCrypto", filename = "providers/nodecrypto.js", enabled = true,
        contentLanguage = listOf("es"), supportedTypes = listOf("movie"), logo = null, disabledPlatforms = emptyList(),
    )

    private fun convert(source: String) = NuvioPluginConverter.convert(scraper, source, repoSlug = "owner/repo", tmdbApiKey = "k")

    /** Runs [body] (the inside of an async function) as the scraper's `getStreams`; a throw becomes `{"error": name, "message": ...}`. */
    private fun run(body: String): Any = runBlocking {
        val source = """
            const crypto = require("crypto");
            var seq = function (start, n) { var out = Buffer.alloc(n); for (var i = 0; i < n; i++) out[i] = (start + i) & 255; return out; };
            var fox = "The quick brown fox jumps over the lazy dog";
            async function body() { $body }
            async function getStreams() {
              var value;
              try { value = await body(); } catch (e) { value = { error: e && e.name, message: e && e.message }; }
              return [{ name: "x", title: "t", url: "https://cdn.example/" + encodeURIComponent(JSON.stringify(value)) }];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val runtime = PluginRuntime.open("nodecrypto", convert(source).script, NuvioRoutingHost(emptyMap()), PluginEnv(appVersion = "1.0"))
        try {
            val url = JSONObject(runtime.call("resolve", JSONObject.quote("""{"tmdbId":603,"type":"movie","season":0,"episode":0}"""), 20_000)).getString("url")
            val json = java.net.URLDecoder.decode(url.removePrefix("https://cdn.example/"), "UTF-8")
            if (json.startsWith("{")) JSONObject(json) else if (json.startsWith("[")) JSONArray(json) else json
        } finally {
            runtime.close()
        }
    }

    private fun strings(out: Any) = (out as JSONArray).let { a -> (0 until a.length()).map { a.get(it).toString() } }

    @Test fun `createHash digests md5, sha1, sha256, sha384 and sha512 like Node`() {
        val out = strings(run("""return ["md5", "sha1", "sha256", "sha384", "sha512"].map(function (a) { return crypto.createHash(a).update("abc").digest("hex"); });"""))
        assertEquals(
            listOf(
                "900150983cd24fb0d6963f7d28e17f72",
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            ),
            out,
        )
    }

    @Test fun `createHash chains updates of strings and Buffers, reads input encodings, and digests to every output encoding`() {
        val out = strings(
            run(
                """
                var d = crypto.createHash("sha256").update("ab").update(Buffer.from("c")).digest();
                return [
                  crypto.createHash("sha256").update("ab").update(Buffer.from("c")).digest("base64"),
                  crypto.createHash("sha256").update("abc").digest("base64url"),
                  crypto.createHash("md5").update("é", "latin1").digest("hex"),
                  crypto.createHash("sha1").update("616263", "hex").digest("hex"),
                  Buffer.isBuffer(d) + ":" + d.length,
                  crypto.createHash("SHA256").update(new Uint8Array([97, 98, 99])).digest("hex"),
                ];
                """,
            ),
        )
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", out[0])
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", out[1])
        assertEquals("3406877694691ddd1dfb0aca54681407", out[2])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", out[3])
        assertEquals("true:32", out[4])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", out[5])
    }

    @Test fun `createHmac signs like Node with md5, sha1, sha256, sha384 and sha512`() {
        val out = strings(run("""return ["md5", "sha1", "sha256", "sha384", "sha512"].map(function (a) { return crypto.createHmac(a, "key").update(fox).digest("hex"); });"""))
        assertEquals(
            listOf(
                "80070713463e7749b90c2dc24911e275",
                "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9",
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                "d7f4727e2c0b39ae0f1e40cc96f60242d5b7801841cea6fc592c5d3e1ae50700582a96cf35e1e554995fe4e03381c237",
                "b42af09057bac1e2d41708e48a902e09b5ff7f12ab428a4fe86653c73dd248fb82f948a549f7b791a5b41915ee4d1ec3935357e4e2317250d0372afa2ebeeb3a",
            ),
            out,
        )
    }

    /** Encrypts [data] in two updates (first 20 bytes, then the rest) + final, and decrypts it back the same way. */
    private fun cipherCase(alg: String, key: String, iv: String, data: String = "Buffer.from(fox)", setup: String = "", decSetup: String = "") = run(
        """
        var data = $data;
        var c = crypto.createCipheriv("$alg", $key, $iv$setup);
        var first = c.update(data.subarray(0, 20));
        var all = Buffer.concat([first, c.update(data.subarray(20)), c.final()]);
        var tag = typeof c.getAuthTag === "function" && /gcm/.test("$alg") ? c.getAuthTag().toString("hex") : "";
        var d = crypto.createDecipheriv("$alg", $key, $iv$decSetup);
        if (tag) d.setAuthTag(Buffer.from(tag, "hex"));
        var back = d.update(all.toString("base64"), "base64", "hex") + d.final("hex");
        return [first.toString("hex"), all.toString("hex"), tag, back === data.toString("hex")];
        """,
    ) as JSONArray

    @Test fun `aes-cbc streams whole blocks from update and pads in final, like Node, for 128 and 256-bit keys`() {
        val c256 = cipherCase("aes-256-cbc", "seq(0, 32)", "seq(0xa0, 16)")
        assertEquals("64bac78bf92341822f9cb229d5199006", c256.getString(0))
        assertEquals("64bac78bf92341822f9cb229d5199006d90c3bab4f7eaa663a51e8c75086e82cabdf122926fa3b24d4f3c9bafa5236b6", c256.getString(1))
        assertTrue(c256.getBoolean(3))
        val c128 = cipherCase("aes-128-cbc", "seq(0, 16)", "seq(0xa0, 16)")
        assertEquals("66c79eeb9465863ede43c216978135de45de5a6a4c003c3a2acf0dc47b07b0da59766c539cdf0ae0ff6c02eddeb1b61a", c128.getString(1))
        assertTrue(c128.getBoolean(3))
    }

    @Test fun `setAutoPadding(false) encrypts exact blocks with no padding`() {
        val out = cipherCase(
            "aes-192-cbc", "seq(0, 24)", "seq(0xa0, 16)", data = "Buffer.concat([Buffer.from(fox), Buffer.alloc(5)])",
            setup = "); c.setAutoPadding(false", decSetup = "); d.setAutoPadding(false",
        )
        assertEquals("2c7e989df3c67fdb9a87927ed5aca4ef1c6185fd243a2cb2cef94eacb55594a704722766aae083dfa2f0cc20b08c3953", out.getString(1))
        assertTrue(out.getBoolean(3))
    }

    @Test fun `aes-ctr and des-ede3-cbc match Node`() {
        val ctr = cipherCase("aes-128-ctr", "seq(0, 16)", "seq(0xa0, 16)")
        assertEquals("0a70b4de8768611dab835ca55bd0ffaf7cadc7fc", ctr.getString(0))
        assertEquals("0a70b4de8768611dab835ca55bd0ffaf7cadc7fcfd8014e122c1d7c5509e0aafcc2147271bb7ccde6d6da8", ctr.getString(1))
        assertTrue(ctr.getBoolean(3))
        val des = cipherCase("des-ede3-cbc", "seq(0, 24)", "seq(0xa0, 8)")
        assertEquals("4265ad9561fbb8c2a303cd00be29562bd1f948e4d06668081b7f4760697f045054bf547f979f0cc12a09eb9dd721145f", des.getString(1))
        assertTrue(des.getBoolean(3))
    }

    @Test fun `aes-gcm streams, takes AAD, gives and checks the auth tag, including a 12-byte tag`() {
        val g = cipherCase("aes-256-gcm", "seq(0, 32)", "seq(0xb0, 12)", setup = "); c.setAAD(Buffer.from(\"header\")", decSetup = "); d.setAAD(Buffer.from(\"header\")")
        assertEquals("cd3d3f8b9db8d23c2cd8f5d0a22ae6e2e25331f2", g.getString(0))
        assertEquals("cd3d3f8b9db8d23c2cd8f5d0a22ae6e2e25331f27f5be2452deeed8739f0d2626d2e035602a4df98c134c7", g.getString(1))
        assertEquals("6fc0761bc333d08854318ca599fae757", g.getString(2))
        assertTrue(g.getBoolean(3))
        val short = cipherCase("aes-128-gcm", "seq(0, 16)", "seq(0xb0, 12)", setup = ", { authTagLength: 12 }", decSetup = ", { authTagLength: 12 }")
        assertEquals("5812c23d694b9b01efcbdd59", short.getString(2))
        assertTrue(short.getBoolean(3))
    }

    @Test fun `a wrong GCM tag and a bad CBC padding throw from final, like Node`() {
        val out = strings(
            run(
                """
                var errs = [];
                var c = crypto.createCipheriv("aes-128-gcm", seq(0, 16), seq(0xb0, 12));
                var ct = Buffer.concat([c.update(fox, "utf8"), c.final()]);
                var tag = c.getAuthTag(); tag[0] ^= 1;
                var d = crypto.createDecipheriv("aes-128-gcm", seq(0, 16), seq(0xb0, 12));
                d.setAuthTag(tag); d.update(ct);
                try { d.final(); errs.push("no error"); } catch (e) { errs.push(e.message); }
                var e1 = crypto.createCipheriv("aes-128-cbc", seq(0, 16), seq(0, 16));
                var ct2 = Buffer.concat([e1.update(fox, "utf8"), e1.final()]);
                var d2 = crypto.createDecipheriv("aes-128-cbc", seq(1, 16), seq(0, 16));
                d2.update(ct2);
                try { d2.final(); errs.push("no error"); } catch (e) { errs.push(e.message); }
                return errs;
                """,
            ),
        )
        assertEquals("Unsupported state or unable to authenticate data", out[0])
        assertTrue(out[1], out[1].contains("bad decrypt"))
    }

    @Test fun `pbkdf2Sync derives like Node for sha1, sha256, sha384 and md5`() {
        val out = strings(
            run(
                """
                return [
                  crypto.pbkdf2Sync("password", "salt", 1000, 32, "sha256").toString("hex"),
                  crypto.pbkdf2Sync("password", "salt", 2, 20, "sha1").toString("hex"),
                  crypto.pbkdf2Sync("password", "salt", 10, 48, "sha384").toString("hex"),
                  crypto.pbkdf2Sync("password", "salt", 10, 16, "md5").toString("hex"),
                ];
                """,
            ),
        )
        assertEquals("632c2812e46d4604102ba7618e9d6d7d2f8128f6266b4a03264d2a0460b7dcb3", out[0])
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", out[1])
        assertEquals("e03f8ca570b98475a9bcd7f73442f3990c3ec87f8815478954ceb62ac2f3d709891aadcb5f5c9485c13e79e20a46a146", out[2])
        assertEquals("d77d26f3ce166f9dc607a5de3a00718c", out[3])
    }

    @Test fun `randomBytes, randomUUID, timingSafeEqual, webcrypto and node-crypto alias`() {
        val out = strings(
            run(
                """
                var r = crypto.randomBytes(2000);
                var unequal;
                try { crypto.timingSafeEqual(Buffer.from("ab"), Buffer.from("abc")); } catch (e) { unequal = e.name; }
                var viaCb = await new Promise(function (ok) { crypto.randomBytes(8, function (err, b) { ok(!err && b.length); }); });
                return [
                  Buffer.isBuffer(r) && r.length === 2000 && r.some(function (v) { return v !== 0; }),
                  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(crypto.randomUUID()),
                  crypto.timingSafeEqual(Buffer.from("abc"), Buffer.from("abc")),
                  crypto.timingSafeEqual(Buffer.from("abc"), Buffer.from("abd")),
                  unequal,
                  crypto.webcrypto === globalThis.crypto && crypto.subtle === globalThis.crypto.subtle,
                  require("node:crypto") === crypto,
                  viaCb,
                ];
                """,
            ),
        )
        assertEquals(listOf("true", "true", "true", "false", "RangeError", "true", "true", "8"), out)
    }

    @Test fun `the Embed69 proof of work as EntrePeliculasySeries writes it solves and decrypts`() {
        val out = strings(
            run(
                """
                var challenge = "kino-challenge", salt = "salt", nonce = 0;
                while (!crypto.createHash("sha256").update(challenge + nonce).digest("hex").startsWith("000")) nonce++;
                var aesKeyBuffer = crypto.createHash("sha256").update(challenge + nonce + salt).digest();
                var iv = seq(0x10, 16);
                var enc = crypto.createCipheriv("aes-256-cbc", aesKeyBuffer, iv);
                var sealed = Buffer.concat([iv, enc.update("https://video.example/master.m3u8", "utf8"), enc.final()]).toString("base64");
                var raw = Buffer.from(sealed, "base64");
                var decipher = crypto.createDecipheriv("aes-256-cbc", aesKeyBuffer, raw.slice(0, 16));
                return [nonce, Buffer.concat([decipher.update(raw.slice(16)), decipher.final()]).toString("utf8")];
                """,
            ),
        )
        assertEquals("https://video.example/master.m3u8", out[1])
    }

    @Test fun `an unsupported algorithm throws a clear Error`() {
        // Caught inside body: a rejection born before anything awaits it fails the whole call in this sandbox.
        val out = run("""try { crypto.createHash("whirlpool"); return "no error"; } catch (e) { return e.message; }""") as String
        assertTrue(out, out.contains("whirlpool"))
    }
}
