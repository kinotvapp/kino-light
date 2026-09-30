package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kino's own TMDB key never sits in a converted Nuvio plugin: the script carries
 * [NuvioPluginConverter.TMDB_KEY_MARKER], and a runtime swaps the key in only on requests to TMDB,
 * redacting it from everything the plugin gets back or logs.
 */
class NuvioTmdbKeyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "a1b2c3d4e5f6realkey"
    private val marker = NuvioPluginConverter.TMDB_KEY_MARKER
    private val sent = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

    /** Answers every request itself (no network), echoing the key the way a server error page might. */
    private val base = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request()
        sent += req.url.toString()
        okhttp3.Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"echo":"${req.url.queryParameter("api_key")}"}""".toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private fun host(): DefaultPluginHost {
        val http = PluginHttp(base, "nuvio-x", EffectiveHosts(listOf(NuvioPluginConverter.TMDB_HOST, "fakesrc.example")), "1.0", log = { logs += it })
        return DefaultPluginHost(
            "nuvio-x", http, PluginStorage(tmp.root.resolve("s.json")), logger = { logs += it },
            secrets = NuvioPluginConverter.tmdbKeySecrets(key),
        )
    }

    private fun get(url: String) = JSONObject().put("url", url).put("method", "GET").toString()

    @Test fun `the converted script carries the marker, never a key`() {
        val scraper = NuvioScraperEntry("fakesrc", "FakeSrc", "providers/fakesrc.js", true, emptyList(), listOf("movie"), null, emptyList())
        val script = NuvioPluginConverter.convert(scraper, "fetch('https://fakesrc.example/')", repoSlug = "o/r").script
        assertTrue(script.contains("var __NUVIO_TMDB_KEY = \"$marker\";"))
        assertFalse(script.contains("__NUVIO_TMDB_API_KEY__"))
    }

    @Test fun `the key is sent to TMDB, and the plugin only ever sees the marker`() = runBlocking {
        val out = JSONObject(host().fetch(get("https://api.themoviedb.org/3/movie/603?api_key=$marker&language=es-MX")))
        assertEquals(listOf("https://api.themoviedb.org/3/movie/603?api_key=$key&language=es-MX"), sent.toList())
        assertEquals("{\"echo\":\"$marker\"}", out.getString("text"))
        assertFalse(out.toString(), key in out.toString())
        assertTrue(logs.none { key in it })
    }

    @Test fun `a request carrying the marker anywhere else is refused before it leaves`() = runBlocking {
        val out = JSONObject(host().fetch(get("https://fakesrc.example/steal?k=$marker")))
        assertEquals("host_not_allowed", out.getJSONObject("error").getString("code"))
        assertTrue(sent.isEmpty())
        // Without the marker the same host is an ordinary request.
        host().fetch(get("https://fakesrc.example/page"))
        assertEquals(listOf("https://fakesrc.example/page"), sent.toList())
    }

    @Test fun `logs and errors are redacted`() {
        val h = host()
        runBlocking { h.fetch(get("https://api.themoviedb.org/3/movie/1?api_key=$marker")) }
        h.log("info", "the key is $key")
        assertTrue(logs.toString(), logs.none { key in it })
        assertTrue(logs.any { "the key is $marker" in it })
    }

    @Test fun `kino secret answers the marker, and no key means no secrets`() {
        assertEquals(marker, host().secret(NuvioPluginConverter.TMDB_KEY_SECRET))
        assertNull(NuvioPluginConverter.tmdbKeySecrets(null))
        assertNull(NuvioPluginConverter.tmdbKeySecrets(""))
        assertEquals(listOf(NuvioPluginConverter.TMDB_HOST), NuvioPluginConverter.tmdbKeySecrets(key)!!.sealedHosts)
    }
}
