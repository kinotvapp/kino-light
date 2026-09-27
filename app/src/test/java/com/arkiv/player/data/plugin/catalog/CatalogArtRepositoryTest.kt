package com.arkiv.player.data.plugin.catalog

import com.arkiv.player.data.plugin.ManifestParser
import com.arkiv.player.data.plugin.PluginFetcher
import com.arkiv.player.data.plugin.PluginInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class CatalogArtRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private var now = 10_000_000_000L
    private val ttl = PluginInstaller.DAY_MS
    private val retryAfter = 2 * 60 * 1000L

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40) { it.toByte() }
    private val otherPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20) { (it + 100).toByte() }

    /** Serves [files] by URL, records every URL asked, and can hold every fetch behind [gate]. */
    private inner class FakeFetcher : PluginFetcher {
        val files = HashMap<String, ByteArray>()
        val failures = HashMap<String, Exception>()
        val urls = ConcurrentLinkedQueue<String>()
        var gate: CompletableDeferred<Unit>? = null
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        @Volatile var sawCancellation = false

        override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
            urls += url
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                try {
                    gate?.await()
                } catch (e: CancellationException) {
                    sawCancellation = true
                    throw e
                }
                failures[url]?.let { throw it }
                return files[url] ?: throw FileNotFoundException(url)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun count() = urls.size
    }

    private val fetcher = FakeFetcher()

    @Before fun setUp() {
        dir = File(tmp.root, "catalog-art")
    }

    private fun repository(concurrency: Int = 3) =
        CatalogArtRepository(fetcher, dir, clock = { now }, ttlMs = ttl, retryAfterMs = retryAfter, concurrency = concurrency)

    private fun manifestJson(color: String? = "#112233", icon: String? = "icon.png", id: String = "demo-plugin") =
        buildString {
            append("""{"id":"$id","name":"Demo","version":"1.0.0","apiVersion":1,"entry":"main.js",""")
            append(""""hosts":["example.com"],"capabilities":["search","resolve"]""")
            if (color != null) append(""","color":"$color"""")
            if (icon != null) append(""","icon":"$icon"""")
            append("}")
        }

    private fun manifestUrl(repo: String) = "https://raw.githubusercontent.com/$repo/HEAD/kino-plugin.json"
    private fun iconUrl(repo: String, path: String = "icon.png") = "https://raw.githubusercontent.com/$repo/HEAD/$path"

    private fun serve(repo: String, manifest: String = manifestJson(), icon: ByteArray? = png, iconPath: String = "icon.png") {
        fetcher.files[manifestUrl(repo)] = manifest.toByteArray()
        if (icon != null) fetcher.files[iconUrl(repo, iconPath)] = icon
    }

    private fun inTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun filesOnDisk(): List<File> = dir.walk().filter { it.isFile }.toList()

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }

    // ---- the happy path ----------------------------------------------------------------------

    @Test fun refresh_returns_the_colour_and_the_icon_and_cached_returns_the_same() = inTest {
        serve("kinotvapp/kino-plugin-demo")
        val repo = repository()

        val art = repo.refresh("kinotvapp/kino-plugin-demo")

        assertNotNull(art)
        assertEquals("#112233", art!!.colorHex)
        assertArrayEquals(png, art.iconFile!!.readBytes())
        assertEquals(
            listOf(manifestUrl("kinotvapp/kino-plugin-demo"), iconUrl("kinotvapp/kino-plugin-demo")),
            fetcher.urls.toList(),
        )
        val cached = repo.cached("kinotvapp/kino-plugin-demo")
        assertEquals(art.colorHex, cached!!.colorHex)
        assertArrayEquals(png, cached.iconFile!!.readBytes())
        assertEquals(2, fetcher.count())
    }

    @Test fun the_art_lives_where_the_layout_says_and_no_temp_file_is_left() = inTest {
        serve("kinotvapp/kino-plugin-demo")
        repository().refresh("kinotvapp/kino-plugin-demo")

        val folder = File(dir, "kinotvapp__kino-plugin-demo")
        assertEquals(setOf("art.json", "icon.png"), folder.list()!!.toSet())
        assertArrayEquals(png, File(folder, "icon.png").readBytes())
    }

    @Test fun a_second_refresh_inside_the_ttl_makes_no_fetch_and_one_after_it_fetches_again() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        assertEquals(2, fetcher.count())

        now += ttl - 1
        val inside = repo.refresh("o/r")
        assertEquals(2, fetcher.count())
        assertEquals("#112233", inside!!.colorHex)

        serve("o/r", manifest = manifestJson(color = "#445566"), icon = otherPng)
        now += 1
        val after = repo.refresh("o/r")
        assertEquals(4, fetcher.count())
        assertEquals("#445566", after!!.colorHex)
        assertArrayEquals(otherPng, after.iconFile!!.readBytes())
    }

    @Test fun a_manifest_without_an_icon_gives_the_colour_only() = inTest {
        serve("o/r", manifest = manifestJson(icon = null), icon = null)
        val art = repository().refresh("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertNull(art.iconFile)
        assertEquals(listOf(manifestUrl("o/r")), fetcher.urls.toList())
        assertFalse(File(dir, "o__r/icon.png").exists())
    }

    @Test fun a_manifest_without_a_colour_gives_the_icon_only() = inTest {
        serve("o/r", manifest = manifestJson(color = null))
        val art = repository().refresh("o/r")

        assertNull(art!!.colorHex)
        assertArrayEquals(png, art.iconFile!!.readBytes())
    }

    @Test fun a_manifest_with_neither_is_cached_as_nothing_and_not_refetched_inside_the_ttl() = inTest {
        serve("o/r", manifest = manifestJson(color = null, icon = null), icon = null)
        val repo = repository()

        val art = repo.refresh("o/r")
        assertEquals(CatalogArt(null, null), art)
        repo.refresh("o/r")
        assertEquals(1, fetcher.count())
        assertEquals(CatalogArt(null, null), repo.cached("o/r"))
    }

    @Test fun an_icon_in_a_sub_folder_is_asked_for_at_that_path() = inTest {
        serve("o/r", manifest = manifestJson(icon = "assets/logo.png"), iconPath = "assets/logo.png")
        val art = repository().refresh("o/r")

        assertArrayEquals(png, art!!.iconFile!!.readBytes())
        assertTrue(iconUrl("o/r", "assets/logo.png") in fetcher.urls)
    }

    // ---- icons that are not usable -------------------------------------------------------------

    @Test fun an_icon_that_is_html_keeps_the_colour_and_writes_no_icon() = inTest {
        serve("o/r", icon = "<html><body>not found</body></html>".toByteArray())
        val art = repository().refresh("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertNull(art.iconFile)
        assertFalse(File(dir, "o__r/icon.png").exists())
        assertNull(repository().cached("o/r")!!.iconFile)
    }

    @Test fun an_icon_over_128_kb_keeps_the_colour_and_writes_no_icon() = inTest {
        serve("o/r", icon = png + ByteArray(PluginInstaller.MAX_ICON_BYTES))
        val art = repository().refresh("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertNull(art.iconFile)
        assertFalse(File(dir, "o__r/icon.png").exists())
    }

    @Test fun an_icon_of_exactly_128_kb_is_accepted() = inTest {
        val exactly = png + ByteArray(PluginInstaller.MAX_ICON_BYTES - png.size)
        assertEquals(PluginInstaller.MAX_ICON_BYTES, exactly.size)
        serve("o/r", icon = exactly)

        assertArrayEquals(exactly, repository().refresh("o/r")!!.iconFile!!.readBytes())
    }

    @Test fun an_empty_icon_keeps_the_colour_and_writes_no_icon() = inTest {
        serve("o/r", icon = ByteArray(0))
        val art = repository().refresh("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertNull(art.iconFile)
        assertFalse(File(dir, "o__r/icon.png").exists())
    }

    @Test fun an_icon_that_is_only_the_signature_is_not_an_image() = inTest {
        serve("o/r", icon = png.copyOf(8))
        assertNull(repository().refresh("o/r")!!.iconFile)
    }

    @Test fun an_icon_that_404s_keeps_the_colour() = inTest {
        serve("o/r", icon = null)
        val art = repository().refresh("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertNull(art.iconFile)
    }

    @Test fun a_new_manifest_that_drops_its_icon_removes_the_old_one() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        assertTrue(File(dir, "o__r/icon.png").exists())

        serve("o/r", manifest = manifestJson(icon = null), icon = null)
        now += ttl
        val art = repo.refresh("o/r")

        assertNull(art!!.iconFile)
        assertFalse(File(dir, "o__r/icon.png").exists())
        assertNull(repo.cached("o/r")!!.iconFile)
    }

    @Test fun a_transient_icon_failure_keeps_the_icon_already_on_disk() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")

        serve("o/r", manifest = manifestJson(color = "#445566"))
        fetcher.failures[iconUrl("o/r")] = IOException("connection reset")
        now += ttl
        val art = repo.refresh("o/r")

        assertEquals("#445566", art!!.colorHex)
        assertArrayEquals(png, art.iconFile!!.readBytes())
    }

    @Test fun an_art_whose_icon_file_went_missing_is_downloaded_again() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        assertTrue(File(dir, "o__r/icon.png").delete())
        assertNull(repo.cached("o/r")!!.iconFile)

        val art = repo.refresh("o/r")

        assertArrayEquals(png, art!!.iconFile!!.readBytes())
        assertEquals(4, fetcher.count())
    }

    // ---- manifests that are not usable ---------------------------------------------------------

    @Test fun a_manifest_the_parser_rejects_gives_null_and_nothing_on_disk() = inTest {
        serve("o/r", manifest = manifestJson(id = "Not A Valid Id"))
        assertNull(repository().refresh("o/r"))
        assertTrue(filesOnDisk().isEmpty())
        assertNull(repository().cached("o/r"))
    }

    @Test fun a_manifest_that_is_not_json_gives_null_and_nothing_on_disk() = inTest {
        fetcher.files[manifestUrl("o/r")] = "<html>rate limited</html>".toByteArray()
        assertNull(repository().refresh("o/r"))
        assertTrue(filesOnDisk().isEmpty())
    }

    @Test fun a_manifest_over_the_size_limit_gives_null_and_nothing_on_disk() = inTest {
        val padding = "x".repeat(ManifestParser.MAX_BYTES)
        serve("o/r", manifest = manifestJson().dropLast(1) + ""","description":"$padding"}""")
        assertNull(repository().refresh("o/r"))
        assertTrue(filesOnDisk().isEmpty())
        assertEquals(1, fetcher.count())
    }

    @Test fun a_manifest_that_asks_for_a_hostile_icon_path_gives_null() = inTest {
        serve("o/r", manifest = manifestJson(icon = "../../secret.png"))
        assertNull(repository().refresh("o/r"))
        assertEquals(listOf(manifestUrl("o/r")), fetcher.urls.toList())
    }

    // ---- failures ------------------------------------------------------------------------------

    @Test fun a_404_gives_null() = inTest {
        assertNull(repository().refresh("o/r"))
        assertTrue(filesOnDisk().isEmpty())
    }

    @Test fun an_io_error_gives_null() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        assertNull(repository().refresh("o/r"))
        assertTrue(filesOnDisk().isEmpty())
    }

    @Test fun an_unexpected_exception_from_the_fetcher_gives_null_instead_of_crashing() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IllegalArgumentException("not a raw GitHub URL")
        assertNull(repository().refresh("o/r"))
    }

    @Test fun a_fetch_failure_with_a_stale_copy_returns_the_stale_copy_and_does_not_rewrite_it() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        val artJson = File(dir, "o__r/art.json")
        val before = artJson.readBytes()
        artJson.setLastModified(1_000L)

        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        now += 3 * ttl
        val art = repo.refresh("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertArrayEquals(png, art.iconFile!!.readBytes())
        assertArrayEquals(before, artJson.readBytes())
        assertEquals(1_000L, artJson.lastModified())
    }

    // A manifest that is 404 or Invalid is a verdict about the repo, not about the network: it keeps the
    // full TTL as its negative window. Anything else (offline, timeout, 5xx, a failed write) is transient
    // and only holds off the next attempt for retryAfterMs.

    @Test fun a_missing_manifest_is_not_retried_in_the_same_process_until_the_ttl() = inTest {
        val repo = repository()

        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())
        assertNull(repo.refresh("o/r"))
        // Well past the short window a transient failure would have: a 404 does not come back within a day.
        now += retryAfter * 10
        assertNull(repo.refresh("o/r"))
        now += ttl - retryAfter * 10 - 1
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())

        serve("o/r")
        now += 1
        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
    }

    @Test fun an_invalid_manifest_is_not_retried_in_the_same_process_until_the_ttl() = inTest {
        serve("o/r", manifest = manifestJson(id = "Not A Valid Id"))
        val repo = repository()

        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())
        now += retryAfter * 10
        assertNull(repo.refresh("o/r"))
        now += ttl - retryAfter * 10 - 1
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())

        serve("o/r")
        now += 1
        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
    }

    @Test fun an_io_error_is_retried_once_retryAfterMs_has_passed_and_not_before() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        val repo = repository()

        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())
        assertNull(repo.refresh("o/r"))
        now += retryAfter - 1
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())

        fetcher.failures.clear()
        serve("o/r")
        now += 1
        val art = repo.refresh("o/r")
        assertEquals("#112233", art!!.colorHex)
        assertArrayEquals(png, art.iconFile!!.readBytes())
        assertEquals(3, fetcher.count())
    }

    @Test fun a_transient_failure_that_persists_waits_another_window_each_time() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IOException("GitHub respondió 503")
        val repo = repository()

        assertNull(repo.refresh("o/r"))
        now += retryAfter
        assertNull(repo.refresh("o/r"))
        assertEquals(2, fetcher.count())
        now += retryAfter - 1
        assertNull(repo.refresh("o/r"))
        assertEquals(2, fetcher.count())
        now += 1
        assertNull(repo.refresh("o/r"))
        assertEquals(3, fetcher.count())
    }

    @Test fun the_default_retry_window_is_two_minutes() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        val repo = CatalogArtRepository(fetcher, dir, clock = { now }, ttlMs = ttl)

        assertNull(repo.refresh("o/r"))
        now += 2 * 60 * 1000L - 1
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())
        fetcher.failures.clear()
        serve("o/r")
        now += 1
        assertNotNull(repo.refresh("o/r"))
    }

    @Test fun a_failed_disk_write_is_a_transient_failure() = inTest {
        serve("o/r")
        dir.mkdirs()
        // A file where the repo's folder must go: creating the folder and writing into it both fail.
        val blocker = File(dir, "o__r").also { it.writeText("in the way") }
        val repo = repository()

        assertNull(repo.refresh("o/r"))
        assertEquals(2, fetcher.count())
        assertNull(repo.refresh("o/r"))
        assertEquals(2, fetcher.count())

        assertTrue(blocker.delete())
        now += retryAfter
        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(4, fetcher.count())
    }

    @Test fun a_stale_copy_is_still_returned_during_a_transient_failure_and_replaced_once_the_window_passes() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        assertEquals(2, fetcher.count())

        now += ttl
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
        // Inside the window: no new attempt, the copy keeps being answered.
        now += retryAfter - 1
        val during = repo.refresh("o/r")
        assertEquals("#112233", during!!.colorHex)
        assertArrayEquals(png, during.iconFile!!.readBytes())
        assertEquals(3, fetcher.count())

        // After it: a new attempt, and the network's answer replaces the copy.
        fetcher.failures.clear()
        serve("o/r", manifest = manifestJson(color = "#445566"), icon = otherPng)
        now += 1
        val after = repo.refresh("o/r")
        assertEquals("#445566", after!!.colorHex)
        assertArrayEquals(otherPng, after.iconFile!!.readBytes())
        assertEquals(5, fetcher.count())
    }

    @Test fun retryFailed_makes_the_next_refresh_download_at_once_after_a_transient_failure() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        val repo = repository()
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())

        fetcher.failures.clear()
        serve("o/r")
        repo.retryFailed()

        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
    }

    @Test fun retryFailed_makes_the_next_refresh_download_at_once_after_a_404() = inTest {
        val repo = repository()
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())
        serve("o/r")
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())

        repo.retryFailed()

        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
    }

    @Test fun retryFailed_makes_the_next_refresh_download_at_once_after_an_invalid_manifest() = inTest {
        serve("o/r", manifest = manifestJson(id = "Not A Valid Id"))
        val repo = repository()
        assertNull(repo.refresh("o/r"))
        assertEquals(1, fetcher.count())

        serve("o/r")
        repo.retryFailed()

        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
    }

    @Test fun retryFailed_forgets_every_repo_not_just_one() = inTest {
        fetcher.failures[manifestUrl("a/one")] = IOException("offline")
        val repo = repository()
        assertNull(repo.refresh("a/one"))
        assertNull(repo.refresh("b/two"))
        assertEquals(2, fetcher.count())

        fetcher.failures.clear()
        serve("a/one")
        serve("b/two")
        repo.retryFailed()

        assertNotNull(repo.refresh("a/one"))
        assertNotNull(repo.refresh("b/two"))
    }

    @Test fun retryFailed_does_not_make_a_fresh_copy_download_again() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")

        repo.retryFailed()
        repo.refresh("o/r")

        assertEquals(2, fetcher.count())
    }

    @Test fun retryFailed_with_nothing_failed_does_nothing() = inTest {
        repository().retryFailed()
        assertEquals(0, fetcher.count())
        assertFalse(dir.exists())
    }

    @Test fun a_failed_repo_with_a_stale_copy_is_not_retried_either_and_keeps_returning_the_copy() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        now += ttl
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")

        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
        assertEquals(3, fetcher.count())
    }

    @Test fun after_a_success_the_repo_is_fresh_and_a_later_failure_is_never_even_seen() = inTest {
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        val repo = repository()
        repo.refresh("o/r")
        now += ttl
        fetcher.failures.clear()
        serve("o/r")
        assertNotNull(repo.refresh("o/r"))
        // Fresh now: a new failure window does not apply to it.
        fetcher.failures[manifestUrl("o/r")] = IOException("offline")
        assertNotNull(repo.refresh("o/r"))
        assertEquals(1 + 2, fetcher.count())
    }

    // ---- hostile repo strings ------------------------------------------------------------------

    @Test fun hostile_or_malformed_repo_strings_give_null_no_fetch_and_nothing_on_disk() = inTest {
        val repo = repository()
        for (bad in listOf(
            "../x", "a/b/c", "a/..", "../..", "./x", "a/.", "", " ", "a", "a/", "/a", "a b/c", "a/b c", "a\\b/c",
            "a/b@main", "https://github.com/a/b", "a/b?x=1", "a/b#c", "a__b/c", "a_b/c", "a/b\u0000", "..", "a/../b",
        )) {
            assertNull("refresh: '$bad'", repo.refresh(bad))
            assertNull("cached: '$bad'", repo.cached(bad))
        }
        assertEquals(0, fetcher.count())
        assertFalse(dir.exists() && dir.walk().any { it != dir })
        // Nothing escaped the directory either.
        assertTrue(tmp.root.walk().filter { it.isFile }.none())
    }

    // ---- concurrency ---------------------------------------------------------------------------

    @Test fun two_concurrent_refreshes_of_one_repo_make_one_manifest_fetch() = inTest {
        serve("o/r")
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val repo = repository()

        val a = async { repo.refresh("o/r") }
        val b = async { repo.refresh("o/r") }
        waitUntil { fetcher.count() >= 1 }
        delay(100)
        gate.complete(Unit)
        val (ra, rb) = awaitAll(a, b)

        assertEquals(1, fetcher.urls.count { it == manifestUrl("o/r") })
        assertEquals(1, fetcher.urls.count { it == iconUrl("o/r") })
        assertEquals("#112233", ra!!.colorHex)
        assertEquals("#112233", rb!!.colorHex)
        assertArrayEquals(png, rb.iconFile!!.readBytes())
    }

    @Test fun two_concurrent_refreshes_of_one_failing_repo_make_one_fetch() = inTest {
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val repo = repository()

        val a = async { repo.refresh("o/r") }
        val b = async { repo.refresh("o/r") }
        waitUntil { fetcher.count() >= 1 }
        delay(100)
        gate.complete(Unit)

        assertNull(a.await())
        assertNull(b.await())
        assertEquals(1, fetcher.count())
    }

    @Test fun six_concurrent_refreshes_of_six_repos_never_exceed_three_fetches_in_flight() = inTest {
        val repos = (1..6).map { "owner$it/repo$it" }
        repos.forEach { serve(it) }
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val repo = repository(concurrency = 3)

        val all = repos.map { async { repo.refresh(it) } }
        waitUntil { fetcher.inFlight.get() == 3 }
        delay(150)
        assertEquals(3, fetcher.inFlight.get())
        assertEquals(3, fetcher.count())
        gate.complete(Unit)
        val results = all.awaitAll()

        assertEquals(3, fetcher.maxInFlight.get())
        assertTrue(results.all { it != null && it.colorHex == "#112233" && it.iconFile != null })
        assertEquals(12, fetcher.count())
    }

    @Test fun a_cancelled_caller_cancels_its_download_and_leaves_no_partial_file() = inTest {
        serve("o/r")
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val repo = repository()

        val job = launch { repo.refresh("o/r") }
        waitUntil { fetcher.inFlight.get() == 1 }
        job.cancelAndJoin()

        assertTrue(fetcher.sawCancellation)
        assertEquals(0, fetcher.inFlight.get())
        assertTrue(filesOnDisk().isEmpty())

        // Cancelling is not a failure: the next caller downloads instead of being told "failed recently".
        fetcher.gate = null
        assertEquals("#112233", repo.refresh("o/r")!!.colorHex)
    }

    @Test fun a_waiter_whose_caller_was_cancelled_does_not_take_the_others_result_with_it() = inTest {
        serve("o/r")
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        val repo = repository()

        val first = launch { repo.refresh("o/r") }
        waitUntil { fetcher.inFlight.get() == 1 }
        val second = async { repo.refresh("o/r") }
        delay(50)
        first.cancelAndJoin()
        gate.complete(Unit)

        assertEquals("#112233", second.await()!!.colorHex)
    }

    // ---- cached() ------------------------------------------------------------------------------

    @Test fun cached_returns_what_is_on_disk_whatever_its_age_and_never_fetches() = inTest {
        serve("o/r")
        val repo = repository()
        repo.refresh("o/r")
        val calls = fetcher.count()

        now += 100 * ttl

        assertEquals("#112233", repo.cached("o/r")!!.colorHex)
        assertEquals(calls, fetcher.count())
    }

    @Test fun cached_is_null_when_nothing_was_downloaded() = inTest {
        assertNull(repository().cached("o/r"))
        assertFalse(dir.exists())
    }

    @Test fun cached_survives_a_new_repository_instance() = inTest {
        serve("o/r")
        repository().refresh("o/r")

        val art = repository().cached("o/r")

        assertEquals("#112233", art!!.colorHex)
        assertArrayEquals(png, art.iconFile!!.readBytes())
    }

    @Test fun cached_on_a_corrupt_art_json_is_null_and_does_not_throw() = inTest {
        val folder = File(dir, "o__r").also { it.mkdirs() }
        for (garbage in listOf("", "{", "not json", "[]", "{\"color\":\"#112233\"}", "{\"fetchedAt\":\"x\"}", "\u0000\u0000")) {
            File(folder, "art.json").writeText(garbage)
            assertNull("'$garbage'", repository().cached("o/r"))
        }
    }

    @Test fun cached_ignores_a_colour_on_disk_that_is_not_rrggbb() = inTest {
        val folder = File(dir, "o__r").also { it.mkdirs() }
        File(folder, "art.json").writeText("""{"fetchedAt":1,"color":"red","icon":false}""")
        assertEquals(CatalogArt(null, null), repository().cached("o/r"))
    }

    @Test fun refresh_over_a_corrupt_art_json_downloads_and_repairs_it() = inTest {
        val folder = File(dir, "o__r").also { it.mkdirs() }
        File(folder, "art.json").writeText("{")
        serve("o/r")

        assertEquals("#112233", repository().refresh("o/r")!!.colorHex)
        assertEquals("#112233", repository().cached("o/r")!!.colorHex)
    }

    @Test fun art_json_has_the_documented_shape() = inTest {
        serve("o/r")
        repository().refresh("o/r")

        val o = org.json.JSONObject(File(dir, "o__r/art.json").readText())
        assertEquals(now, o.getLong("fetchedAt"))
        assertEquals("#112233", o.getString("color"))
        assertEquals(true, o.getBoolean("icon"))
    }
}
