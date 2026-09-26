package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BundledPluginFetcherTest {
    private val manifestBytes = """{"id":"xuper"}""".toByteArray()
    private val scriptBytes = "exports.search = () => [];".toByteArray()
    private val fromDelegate = "from-github".toByteArray()

    private val assets = mapOf("kino-plugin.json" to manifestBytes, "plugin.js" to scriptBytes)
    private val delegateUrls = mutableListOf<String>()
    private val delegate = PluginFetcher { url, _ ->
        delegateUrls += url
        fromDelegate
    }
    private val fetcher = BundledPluginFetcher({ file -> assets[file] }, delegate)

    private fun xuper(file: String) = PluginAddress("kinotvapp", "kino-plugin-xuper").rawUrl(file)

    @Test
    fun `the manifest of the Xuper repo is answered from the assets and never reaches the delegate`() = runBlocking {
        val bytes = fetcher.fetch(xuper("kino-plugin.json"), 1_000)

        assertArrayEquals(manifestBytes, bytes)
        assertTrue(delegateUrls.isEmpty())
    }

    @Test
    fun `every bundled file of the Xuper repo is served, not just the manifest`() = runBlocking {
        assertArrayEquals(scriptBytes, fetcher.fetch(xuper("plugin.js"), 1_000))
        assertTrue(delegateUrls.isEmpty())
    }

    @Test
    fun `the URL served from the assets is the one PluginAddress builds for the Xuper repo`() {
        assertEquals(
            "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD/kino-plugin.json",
            xuper("kino-plugin.json"),
        )
    }

    @Test
    fun `the same file under another ref is not served from the assets`() = runBlocking {
        val url = PluginAddress("kinotvapp", "kino-plugin-xuper", ref = "some-branch").rawUrl("kino-plugin.json")

        assertArrayEquals(fromDelegate, fetcher.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `the same file under another owner is not served from the assets`() = runBlocking {
        val url = PluginAddress("someone-else", "kino-plugin-xuper").rawUrl("kino-plugin.json")

        assertArrayEquals(fromDelegate, fetcher.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `the same file in another repo of the same owner is not served from the assets`() = runBlocking {
        val url = PluginAddress("kinotvapp", "kino-plugin-archive").rawUrl("kino-plugin.json")

        assertArrayEquals(fromDelegate, fetcher.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `a file of the Xuper repo that is missing in the assets falls to the delegate`() = runBlocking {
        val url = xuper("not-bundled.js")

        assertArrayEquals(fromDelegate, fetcher.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `any other URL goes to the delegate`() = runBlocking {
        val url = "https://raw.githubusercontent.com/kinotvapp/kino-plugin-archive/HEAD/kino-plugin.json"

        assertArrayEquals(fromDelegate, fetcher.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `the delegate gets the size limit it was asked for`() = runBlocking {
        var seenMax = -1
        val spy = BundledPluginFetcher({ null }, PluginFetcher { _, max -> seenMax = max; fromDelegate })

        spy.fetch(xuper("kino-plugin.json"), 4_321)

        assertEquals(4_321, seenMax)
    }

    @Test
    fun `a URL that only starts like the Xuper repo but tries to leave it is not served from the assets`() = runBlocking {
        val secret = "secret".toByteArray()
        val leaky = BundledPluginFetcher(
            { file -> if (file == "../other/secret.js") secret else assets[file] },
            delegate,
        )
        val url = xuper("../other/secret.js")

        assertArrayEquals(fromDelegate, leaky.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `the bare repo root, with no file, is not served from the assets`() = runBlocking {
        val url = xuper("")
        val greedy = BundledPluginFetcher({ manifestBytes }, delegate)

        assertArrayEquals(fromDelegate, greedy.fetch(url, 1_000))
        assertEquals(listOf(url), delegateUrls)
    }

    @Test
    fun `a bundled file bigger than the limit fails like a download that is too large`() {
        try {
            runBlocking { fetcher.fetch(xuper("kino-plugin.json"), manifestBytes.size - 1) }
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(delegateUrls.isEmpty())
        }
    }

    @Test
    fun `a bundled file exactly at the limit is served`() = runBlocking {
        assertArrayEquals(manifestBytes, fetcher.fetch(xuper("kino-plugin.json"), manifestBytes.size))
    }
}
