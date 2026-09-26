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
    fun `a URL of another host goes to the delegate`() = runBlocking {
        val url = "https://example.com/kino-plugin.json"

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
    fun `a bundled file bigger than the limit fails like a download that is too large`() {
        try {
            runBlocking { fetcher.fetch(xuper("kino-plugin.json"), manifestBytes.size - 1) }
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals("archivo demasiado grande", e.message)
            assertTrue(delegateUrls.isEmpty())
        }
    }

    @Test
    fun `a bundled file exactly at the limit is served`() = runBlocking {
        assertArrayEquals(manifestBytes, fetcher.fetch(xuper("kino-plugin.json"), manifestBytes.size))
    }

    // Look-alike URLs. The assets fake below answers ANY name it is asked, so nothing but the
    // fetcher's own checks can keep these away from the assets: each one must reach the delegate
    // with the exact URL, and the assets lambda must not even be asked.

    private val lookAlikePrefixes = listOf(
        "repo with a longer name" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper-evil/HEAD/kino-plugin.json",
        "repo with a digit appended" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper2/HEAD/kino-plugin.json",
        "ref HEAD2" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD2/kino-plugin.json",
        "ref HEADER" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEADER/kino-plugin.json",
        "ref in lower case" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/head/kino-plugin.json",
        "no ref at all" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/kino-plugin.json",
        "another owner" to "https://raw.githubusercontent.com/someone-else/kino-plugin-xuper/HEAD/kino-plugin.json",
        "owner in another case" to "https://raw.githubusercontent.com/KinoTVApp/kino-plugin-xuper/HEAD/kino-plugin.json",
        "repo in another case" to "https://raw.githubusercontent.com/kinotvapp/Kino-Plugin-Xuper/HEAD/kino-plugin.json",
        "plain http" to "http://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD/kino-plugin.json",
        "scheme in upper case" to "HTTPS://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD/kino-plugin.json",
        "host in upper case" to "https://RAW.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD/kino-plugin.json",
        "another host" to "https://example.com/kinotvapp/kino-plugin-xuper/HEAD/kino-plugin.json",
        "host with a suffix" to "https://raw.githubusercontent.com.evil.example/kinotvapp/kino-plugin-xuper/HEAD/kino-plugin.json",
        "no slash after HEAD" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEADkino-plugin.json",
        "no slash after HEAD, nothing else" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD",
        "an extra slash before HEAD" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper//HEAD/kino-plugin.json",
        "an extra slash after the repo root" to "https://raw.githubusercontent.com/kinotvapp/kino-plugin-xuper/HEAD//kino-plugin.json",
        "a leading space" to " " + xuper("kino-plugin.json"),
    )

    private val lookAlikeFiles = listOf(
        "a query" to "kino-plugin.json?x=1",
        "a fragment" to "kino-plugin.json#x",
        "an encoded ../" to "%2e%2e%2f",
        "an encoded dot-dot before a name" to "%2e%2e%2fkino-plugin.json",
        "an encoded letter" to "%41",
        "an encoded letter inside a name" to "kino-plugin%2Ejson",
        "a backslash" to "sub\\kino-plugin.json",
        "a backslash traversal" to "..\\kino-plugin.json",
        "a sub-folder" to "sub/dir.json",
        "a sub-folder of a bundled name" to "sub/kino-plugin.json",
        "a ./ segment" to "./kino-plugin.json",
        "a lone dot" to ".",
        "a lone dot-dot" to "..",
        "a leading ../" to "../kino-plugin.json",
        "a ../ in the middle" to "a/../kino-plugin.json",
        "a climb to a sibling file" to "../other/secret.js",
        "a double slash" to "//kino-plugin.json",
        "a trailing slash" to "kino-plugin.json/",
        "an empty file (the repo root)" to "",
        "a trailing space" to "kino-plugin.json ",
        "a trailing newline" to "kino-plugin.json\n",
        "a NUL byte" to "kino-plugin.json\u0000",
        "a name with a non-ASCII letter" to "kino-plugín.json",
        "a path longer than the manifest limit" to "a".repeat(ManifestParser.MAX_PATH_CHARS + 1),
    )

    /** Answers any name it is asked, and remembers every name. */
    private inner class Permissive {
        val asked = mutableListOf<String>()
        val fetcher = BundledPluginFetcher({ file -> asked += file; manifestBytes }, delegate)
    }

    private fun assertAllGoToTheDelegate(rows: List<Pair<String, String>>) {
        val problems = mutableListOf<String>()
        for ((label, url) in rows) {
            delegateUrls.clear()
            val permissive = Permissive()
            val bytes = runBlocking { permissive.fetcher.fetch(url, 1_000_000) }
            if (permissive.asked.isNotEmpty()) problems += "$label: the assets were asked for ${permissive.asked} (url=$url)"
            if (delegateUrls != listOf(url)) problems += "$label: the delegate saw $delegateUrls instead of the url (url=$url)"
            if (!bytes.contentEquals(fromDelegate)) problems += "$label: the answer did not come from the delegate (url=$url)"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `look-alike prefixes reach the delegate even when the assets would answer anything`() {
        assertAllGoToTheDelegate(lookAlikePrefixes)
    }

    @Test
    fun `file names that are not a plain name reach the delegate even when the assets would answer anything`() {
        assertAllGoToTheDelegate(lookAlikeFiles.map { (label, file) -> label to xuper(file) })
    }

    @Test
    fun `the legit bundled names are served from the assets, and only they are asked for`() {
        for (file in listOf("kino-plugin.json", "plugin.js")) {
            delegateUrls.clear()
            val permissive = Permissive()

            val bytes = runBlocking { permissive.fetcher.fetch(xuper(file), 1_000) }

            assertArrayEquals(file, manifestBytes, bytes)
            assertEquals(file, listOf(file), permissive.asked)
            assertTrue(file, delegateUrls.isEmpty())
        }
    }

    @Test
    fun `a name the shared manifest path rule accepts is not refused just for having two dots in it`() {
        // ManifestParser.isSafeRelativePath accepts this segment, so the fetcher must too.
        assertTrue(ManifestParser.isSafeRelativePath("a..b.js"))
        val permissive = Permissive()

        val bytes = runBlocking { permissive.fetcher.fetch(xuper("a..b.js"), 1_000) }

        assertArrayEquals(manifestBytes, bytes)
        assertEquals(listOf("a..b.js"), permissive.asked)
        assertTrue(delegateUrls.isEmpty())
    }
}
