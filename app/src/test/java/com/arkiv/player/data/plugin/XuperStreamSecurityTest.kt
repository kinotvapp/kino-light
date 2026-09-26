package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two narrow exceptions [XuperStreams] makes for the one plugin [XuperPrivilege.grants]: its
 * natively-resolved `http://` CDN stream is accepted, and plays with headers the script never saw.
 * Everything here also checks the other side: no other plugin, and no URL the bridge didn't
 * resolve, gets either exception.
 */
class XuperStreamSecurityTest {
    private val cdn = "http://cdn.magis.example/vod/ABC_media.ts"
    private val sub = "http://subs.magis.example/public/subs/es.srt"
    private val secret = mapOf("Content-Auth" to "token=SECRET-AUTH", "Content-License" to "token=SECRET-LICENSE", "App" to "SECRET-APP")

    private fun plugin(address: String, hosts: List<String> = listOf("example.com")) = InstalledPlugin(
        PluginManifest("xuper", "Xuper", "1.0.0", 1, "plugin.js", "", "", "", hosts, setOf("search", "resolve"), "#E0A030", null),
        InstalledRecord(address, "1.0.0", "x", hosts, 0L),
        null,
    )

    private val xuper = plugin(XuperPrivilege.SOURCE_REPO)

    private fun streams() = XuperStreams().apply { remember(cdn, secret, listOf(sub)) }

    /** What the script returns from `resolve()`; [headers] is what a hostile script might add. */
    private fun answer(url: String, headers: Map<String, String> = emptyMap(), subtitles: List<String> = listOf(sub)) = JSONObject()
        .put("url", url)
        .put("mime", "video/mp2t")
        .put("headers", JSONObject(headers))
        .put("subtitles", JSONArray(subtitles.map { JSONObject().put("lang", "es").put("url", it).put("format", "srt") }))
        .toString()

    private fun source(p: InstalledPlugin, answer: String, streams: XuperStreams? = streams()) =
        PluginContentSource(p, { _, _, _, _ -> answer }, p.hosts, xuperStreams = streams, log = {})

    private val ref = PluginRef("xuper", "m1", PluginRef.MOVIE, "magis1:movie:0:ABC").encode()

    private suspend fun rejected(source: PluginContentSource): String = try {
        source.resolve(ref)
        fail("the stream was accepted")
        ""
    } catch (e: GatewayException) {
        e.message.orEmpty()
    }

    // --- problem 1: the https/host carve-out ------------------------------------------------------

    @Test fun `the Xuper plugin's natively resolved http stream is accepted, subtitles included`() = runTest {
        val play = source(xuper, answer(cdn)).resolve(ref)
        assertEquals(cdn, play.url)
        assertEquals(listOf(sub), play.subtitles.map { it.url })
    }

    @Test fun `any other plugin's http stream is still rejected, even for the very same URL`() = runTest {
        for (address in listOf("someone-else/kino-plugin-xuper", "kinotvapp/kino-plugin-xuper@dev", "kinotvapp/kino-plugin-xuper/sub", "o/r")) {
            val message = rejected(source(plugin(address), answer(cdn)))
            assertTrue("$address: $message", message.endsWith("El video debe usar https"))
        }
    }

    @Test fun `any other plugin's http subtitle from the bridge is still dropped`() = runTest {
        val https = "https://example.com/v.m3u8"
        val play = source(plugin("o/r"), answer(https)).resolve(ref)
        assertEquals(emptyList<String>(), play.subtitles.map { it.url })
    }

    @Test fun `the Xuper plugin's http URL the bridge never resolved is rejected`() = runTest {
        for (url in listOf("http://192.168.1.1/video.ts", "http://cdn.magis.example/vod/OTHER_media.ts", "$cdn?x=1", "HTTP://cdn.magis.example/vod/ABC_media.ts")) {
            val message = rejected(source(xuper, answer(url)))
            assertTrue("$url: $message", message.endsWith("El video debe usar https"))
        }
    }

    @Test fun `the Xuper plugin's https URL on an undeclared host is still rejected`() = runTest {
        val message = rejected(source(xuper, answer("https://evil.example/v.ts")))
        assertTrue(message, message.endsWith("que el plugin no declaró"))
    }

    @Test fun `without the shared vault even the Xuper plugin gets the ordinary check`() = runTest {
        val message = rejected(source(xuper, answer(cdn), streams = null))
        assertTrue(message, message.endsWith("El video debe usar https"))
    }

    // --- problem 2: the headers never go through the script ----------------------------------------

    @Test fun `the Xuper stream plays with the native headers, never the script's`() = runTest {
        val hostile = mapOf("Content-Auth" to "forged", "X-Exfil" to "1")
        val play = source(xuper, answer(cdn, headers = hostile)).resolve(ref)
        assertEquals(secret, play.headers)
    }

    @Test fun `another plugin returning the same URL never gets the native headers`() = runTest {
        // Even with the https check out of the way (a declared https host), a URL is no key to them.
        val https = "https://example.com/v.m3u8"
        val vault = XuperStreams().apply { remember(https, secret, emptyList()) }
        val play = source(plugin("someone-else/kino-plugin-xuper"), answer(https, headers = mapOf("Referer" to "r")), vault).resolve(ref)
        assertEquals(mapOf("Referer" to "r"), play.headers)
    }

    @Test fun `a declared https stream of the Xuper plugin keeps the script's own headers`() = runTest {
        val https = "https://example.com/v.m3u8"
        val play = source(xuper, answer(https, headers = mapOf("Referer" to "r"), subtitles = emptyList())).resolve(ref)
        assertEquals(mapOf("Referer" to "r"), play.headers)
    }

    @Test fun `the vault is bounded and a re-resolve refreshes the headers`() {
        val vault = XuperStreams(cap = 2)
        vault.remember("http://a/1", mapOf("K" to "old"), emptyList())
        vault.remember("http://a/1", mapOf("K" to "new"), emptyList())
        assertEquals(mapOf("K" to "new"), vault.headersFor("http://a/1"))
        vault.remember("http://a/2", emptyMap(), emptyList())
        vault.remember("http://a/3", emptyMap(), emptyList())
        assertEquals(null, vault.headersFor("http://a/1"))
    }

    @Test fun `a reply with more subtitles than the vault holds still resolves its own stream`() = runTest {
        val vault = XuperStreams()
        val many = (1..XuperStreams.DEFAULT_CAP * 2).map { "http://subs.magis.example/public/subs/$it.srt" }
        vault.remember(cdn, secret, many)
        assertEquals(secret, vault.headersFor(cdn))
        // And through the real reader: the stream passes with its headers, subtitles up to the contract's cap.
        val play = source(xuper, answer(cdn, subtitles = many), vault).resolve(ref)
        assertEquals(secret, play.headers)
        assertEquals(many.take(PluginOutput.MAX_SUBTITLES), play.subtitles.map { it.url })
    }

    @Test fun `a subtitle URL never overwrites a stream's headers`() {
        val vault = XuperStreams()
        vault.remember(cdn, secret, emptyList())
        vault.remember("http://cdn.magis.example/vod/B_media.ts", emptyMap(), listOf(cdn))
        assertEquals(secret, vault.headersFor(cdn))
    }
}
