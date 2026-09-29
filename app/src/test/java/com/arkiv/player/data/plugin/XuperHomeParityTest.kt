package com.arkiv.player.data.plugin

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.db.HomeCatalogCacheDao
import com.arkiv.player.data.db.HomeCatalogCacheEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.data.magis.FakePortalClient
import com.arkiv.player.data.magis.MagisCatalog
import com.arkiv.player.data.magis.MagisPluginBridge
import com.arkiv.player.data.magis.MagisResolve
import com.arkiv.player.data.magis.testSession
import com.arkiv.player.ui.home.HomeCatalogCodec
import com.arkiv.player.ui.home.HomeCatalogStore
import com.arkiv.player.ui.home.MagisHomeCatalog
import com.arkiv.player.ui.home.MagisHomeClassifier
import com.arkiv.player.ui.home.MagisHomeRow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `kino.xuper.home` over [MagisHomeCatalog.rows], on rows recorded from a REAL activated session
 * (`app/src/test/resources/xuper-parity/home-1.json`, local-only, never committed -- see the Task 9
 * report). Unlike Tasks 5-7, there is no separate "native" home orchestration to replay against:
 * `MagisHomeCatalog`/`MagisHomeClassifier` already ARE the production logic (the native Home screen
 * reads the SAME instance), so what's tested here is [MagisPluginBridge.home]'s SHAPING -- that it
 * reads through [MagisHomeCatalog] unchanged (never asks the portal on its own) and that every real
 * field a row/item carries survives both the plugin envelope and [PluginOutput]'s own contract
 * reader.
 *
 * `home-1.json` was captured on the phone (see the throwaway harness, saved at
 * `.superpowers/sdd/2026-09-25-xuper-privileged-plugin/task-9-XuperHomeCapture.kt.txt`) by running
 * the real [MagisHomeCatalog.load] and encoding its rows with [HomeCatalogCodec] -- the SAME
 * encoding `HomeCatalogStore` persists, so no parallel fixture format was invented. The captured
 * pass answered with all 4 roots (56 rows); the committed file is hand-trimmed to 4 rows / 6 items
 * chosen to cover a movie and a series item, an item with 7 genres (over the contract's 5, to prove
 * the reader still truncates it), one item with no `backdrop` and one with no `score`.
 */
class XuperHomeParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeHomeCatalogCacheDao(private var entity: HomeCatalogCacheEntity?) : HomeCatalogCacheDao {
        override suspend fun get(id: String) = entity
        override suspend fun save(row: HomeCatalogCacheEntity) { entity = row }
        override suspend fun clear() { entity = null }
    }

    private fun fixtureRows(): List<MagisHomeRow> =
        HomeCatalogCodec.decode(File("src/test/resources/xuper-parity/home-1.json").readText())

    /**
     * A [MagisHomeCatalog] whose [MagisHomeCatalog.rows] serves EXACTLY [rows], straight from its
     * own persistent cache (a real [HomeCatalogStore] over a fake DAO, pre-seeded and fresh by
     * [now]'s clock) -- never through `tree`, which throws if [home] ever called it. Proves
     * [MagisPluginBridge.home] reads through the real cache-first path unchanged, exactly as the
     * brief requires ("must not duplicate or fight that caching, only read through it").
     */
    private fun catalogServing(rows: List<MagisHomeRow>): MagisHomeCatalog {
        val dao = FakeHomeCatalogCacheDao(HomeCatalogCacheEntity(rowsJson = HomeCatalogCodec.encode(rows), fetchedAt = 0L))
        return MagisHomeCatalog(
            tree = { error("xuperHome must read MagisHomeCatalog's own cache, never ask a root directly") },
            store = HomeCatalogStore(dao),
            now = { 0L },
        )
    }

    private fun bridge(homeCatalog: MagisHomeCatalog): MagisPluginBridge {
        val fake = FakePortalClient()
        val session = testSession(fake)
        return MagisPluginBridge(
            MagisCatalog(fake, session), MagisResolve(fake, session), TmdbApi(),
            vodStore = null, streams = XuperStreams(), homeCatalog = homeCatalog,
        )
    }

    private fun host(homeCatalog: MagisHomeCatalog) = DefaultPrivilegedXuperHost(
        "xuper",
        PluginHttp(OkHttpClient(), "xuper", EffectiveHosts(emptyList()), "9.9.9"),
        PluginStorage(File(tmp.newFolder(), "storage.json")),
        PluginConfig.EMPTY,
        null,
        lazyOf(bridge(homeCatalog)),
    )

    // --- real device fixture --------------------------------------------------------------------

    @Test fun `xuperHome shapes the real captured rows, ref equal to the row id`() = runBlocking {
        val rows = fixtureRows()
        assertEquals(4, rows.size) // sanity: the trimmed fixture wasn't accidentally emptied
        val envelope = JSONObject(host(catalogServing(rows)).xuperHome())
        assertTrue(envelope.toString(), envelope.getBoolean("ok"))
        val data = envelope.getJSONArray("data")
        assertEquals(rows.size, data.length())
        for (i in rows.indices) {
            val row = rows[i]
            val out = data.getJSONObject(i)
            assertEquals(row.id, out.getString("id"))
            assertEquals(row.title, out.getString("title"))
            assertEquals(row.id, out.getString("ref"))
            assertEquals(row.shown.size, out.getJSONArray("items").length())
        }
    }

    @Test fun `the plugin contract reader keeps every real item's id, ref, kind, images, genres and rating`() = runBlocking {
        val rows = fixtureRows()
        val data = JSONObject(host(catalogServing(rows)).xuperHome()).getJSONArray("data")
        val read = PluginOutput.rows(data.toString(), allowSeries = true, allowBrowse = true)
        assertEquals(rows.map { it.id }, read.map { it.id })
        assertEquals(rows.map { it.id }, read.map { it.ref }) // Task 10's browse looks the row back up by this

        for ((row, parsedRow) in rows.zip(read)) {
            assertEquals("row " + row.id, row.shown.map { it.id }, parsedRow.items.map { it.id })
            assertEquals("row " + row.id, row.shown.map { it.ref }, parsedRow.items.map { it.ref })
            assertEquals(
                "row " + row.id,
                row.shown.map { if (it.type in MAGIS_SERIES) "series" else "movie" },
                parsedRow.items.map { it.kind },
            )
            assertEquals("row " + row.id, row.shown.map { it.poster.orEmpty() }, parsedRow.items.map { it.poster })
            assertEquals("row " + row.id, row.shown.map { it.backdrop.orEmpty() }, parsedRow.items.map { it.backdrop })
            assertEquals("row " + row.id, row.shown.map { it.score }, parsedRow.items.map { it.rating })
            // The contract caps genres at 5; one real item here carries 7 -- the reader truncates it.
            assertEquals("row " + row.id, row.shown.map { it.genres.take(5) }, parsedRow.items.map { it.genres })
            // No captured item has a positive durationS: no runtimeMinutes was fabricated for any of them.
            assertTrue("row " + row.id, parsedRow.items.all { it.runtimeMinutes == 0 })
        }
    }

    // --- synthetic: shaping edge cases not present in the real capture --------------------------

    @Test fun `kind reads the same MAGIS_SERIES set search's itemFrom does, not the narrower CatalogItem isSeries`() = runBlocking {
        // "variety" is in MAGIS_SERIES (so a search result for the same title would say "series")
        // but CatalogItem.isSeries only matches "teleplay" -- a different, narrower question.
        val items = (1..MagisHomeClassifier.MIN_GENRE_SIZE).map {
            CatalogItem(id = "v$it", title = "v$it", poster = null, durationS = 0, ref = "magis1:variety:0:v$it", type = "variety")
        }
        val homeCatalog = MagisHomeCatalog(tree = { root ->
            if (root == "series") listOf(CatalogSection(1, "Top", false, items)) else emptyList()
        })
        val data = JSONObject(host(homeCatalog).xuperHome()).getJSONArray("data")
        val read = PluginOutput.rows(data.toString(), allowSeries = true, allowBrowse = true)
        assertTrue(read.isNotEmpty())
        assertTrue(read.flatMap { it.items }.isNotEmpty())
        assertTrue(read.flatMap { it.items }.all { it.kind == "series" })
    }

    @Test fun `an item with a blank ref is dropped, and a row where every item drops disappears too`() = runBlocking {
        val valid = CatalogItem(id = "ok1", title = "ok1", poster = null, durationS = 0, ref = "magis1:movie:0:ok1", type = "movie")
        val blankRef = CatalogItem(id = "nr1", title = "nr1", poster = null, durationS = 0, ref = "", type = "movie")
        val homeCatalog = MagisHomeCatalog(tree = { root ->
            when (root) {
                "peliculas" -> listOf(CatalogSection(1, "Top", false, listOf(valid, blankRef)))
                "series" -> listOf(CatalogSection(1, "Top", false, listOf(blankRef.copy(id = "nr2"))))
                else -> emptyList()
            }
        })
        val data = JSONObject(host(homeCatalog).xuperHome()).getJSONArray("data")
        val rows = (0 until data.length()).map { data.getJSONObject(it) }
        val peliculasTop = rows.first { it.getString("id") == "magis_top_peliculas" }
        assertEquals(1, peliculasTop.getJSONArray("items").length())
        assertEquals("ok1", peliculasTop.getJSONArray("items").getJSONObject(0).getString("id"))
        // series' only candidate had a blank ref: the whole row is dropped, not sent empty.
        assertTrue(rows.none { it.getString("id") == "magis_top_series" })
    }

    @Test fun `a partial load (one root down) still shapes the rows the answering roots produced`() = runBlocking {
        val items = (1..MagisHomeClassifier.MIN_GENRE_SIZE).map {
            CatalogItem(id = "s$it", title = "s$it", poster = null, durationS = 0, ref = "magis1:teleplay:0:s$it", type = "teleplay", genres = listOf("Drama"))
        }
        val homeCatalog = MagisHomeCatalog(tree = { root ->
            when (root) {
                "peliculas" -> error("portal down")
                "series" -> listOf(CatalogSection(1, "All", false, items))
                else -> emptyList()
            }
        })
        val envelope = JSONObject(host(homeCatalog).xuperHome())
        assertTrue(envelope.toString(), envelope.getBoolean("ok"))
        val ids = (0 until envelope.getJSONArray("data").length()).map { envelope.getJSONArray("data").getJSONObject(it).getString("id") }
        assertTrue(ids.any { it == "magis_g_series_drama" })
        assertTrue(ids.none { it.contains("peliculas") })
    }

    @Test fun `all four roots empty answers ok with no rows, never throws`() = runBlocking {
        // MagisHomeCatalog.load() itself reports Crash.report(EmptyCatalog(...)) here, unchanged
        // by this task (this file never touches ui/home/MagisHomeCatalog.kt) -- this only confirms
        // xuperHome() doesn't choke on that degenerate pass.
        val homeCatalog = MagisHomeCatalog(tree = { emptyList() })
        val envelope = JSONObject(host(homeCatalog).xuperHome())
        assertTrue(envelope.toString(), envelope.getBoolean("ok"))
        assertEquals(0, envelope.getJSONArray("data").length())
    }

    @Test fun `an unexpected failure building the bridge collapses to unavailable, like the other xuper members`() = runBlocking {
        val host = DefaultPrivilegedXuperHost(
            "xuper",
            PluginHttp(OkHttpClient(), "xuper", EffectiveHosts(emptyList()), "9.9.9"),
            PluginStorage(File(tmp.newFolder(), "storage.json")),
            PluginConfig.EMPTY,
            null,
            lazy<MagisPluginBridge> { throw IllegalStateException("boom de prueba") },
        )
        val envelope = JSONObject(host.xuperHome())
        assertFalse(envelope.toString(), envelope.getBoolean("ok"))
        assertEquals(PluginErrors.UNAVAILABLE, envelope.getString("code"))
        assertEquals("boom de prueba", envelope.getString("message"))
    }
}
