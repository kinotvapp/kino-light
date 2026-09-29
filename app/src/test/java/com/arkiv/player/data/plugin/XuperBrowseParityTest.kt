package com.arkiv.player.data.plugin

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.db.HomeCatalogCacheDao
import com.arkiv.player.data.db.HomeCatalogCacheEntity
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.magis.FakePortalClient
import com.arkiv.player.data.magis.MagisCatalog
import com.arkiv.player.data.magis.MagisPluginBridge
import com.arkiv.player.data.magis.MagisResolve
import com.arkiv.player.data.magis.testSession
import com.arkiv.player.ui.home.HomeCatalogCodec
import com.arkiv.player.ui.home.HomeCatalogStore
import com.arkiv.player.ui.home.MagisHomeCatalog
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
 * `kino.xuper.browse` over [MagisHomeRow.all] -- the exact list [MagisPluginBridge.home] already
 * builds `shown` from (see [MagisHomeCatalog.load]), so paginating it is arithmetic over data
 * already in memory, never a new portal call.
 *
 * Unlike Tasks 5-8's parity tests, this one doesn't replay a real device fixture:
 * `app/src/test/resources/xuper-parity/home-1.json` (Task 9's capture) was hand-trimmed to 6 items
 * total, and every row's `all` there is the same length as `shown` (checked before writing this --
 * none exceeds [MagisPluginBridge]'s `PAGE_SIZE`), so it has no row that actually exercises a
 * second page. Real-device depth isn't needed here the way it was for Tasks 5-8: those replayed
 * genuine PORTAL RESPONSES (shapes only the real portal can be trusted to produce); this is offset
 * arithmetic and [MagisPluginBridge]'s own `itemFromCatalog` shaping, already proven against the
 * real capture by `XuperHomeParityTest`. So the pagination-boundary case below uses a synthetic but
 * real-typed [MagisHomeRow]/[CatalogItem] list, built directly, the same way `XuperHomeParityTest`'s
 * own synthetic tests (blank ref, MAGIS_SERIES kind) already do for edge cases the trimmed capture
 * doesn't cover.
 */
class XuperBrowseParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeHomeCatalogCacheDao(private var entity: HomeCatalogCacheEntity?) : HomeCatalogCacheDao {
        override suspend fun get(id: String) = entity
        override suspend fun save(row: HomeCatalogCacheEntity) { entity = row }
        override suspend fun clear() { entity = null }
    }

    /** Same shape as `XuperHomeParityTest.catalogServing`: [rows] served straight from a pre-seeded,
     *  always-fresh cache, never through `tree` (which throws if [browse] ever asked the portal). */
    private fun catalogServing(rows: List<MagisHomeRow>): MagisHomeCatalog {
        val dao = FakeHomeCatalogCacheDao(HomeCatalogCacheEntity(rowsJson = HomeCatalogCodec.encode(rows), fetchedAt = 0L))
        return MagisHomeCatalog(
            tree = { error("xuperBrowse must read MagisHomeCatalog's own cache, never ask a root directly") },
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

    private fun items(count: Int, prefix: String) = (1..count).map {
        CatalogItem(id = "$prefix$it", title = "$prefix$it", poster = null, durationS = 0, ref = "magis1:movie:0:$prefix$it", type = "movie")
    }

    @Test fun `browse paginates a row's already-fetched all list by offset`() = runBlocking {
        // 55 items, one more page size (50) than a single page: proves both the full first page
        // AND the shorter, final one, plus the cursor that connects them.
        val all = items(55, "it")
        val row = MagisHomeRow(id = "row-1", title = "Row 1", shown = all.take(6), all = all)
        val h = host(catalogServing(listOf(row)))

        val first = JSONObject(h.xuperBrowse("row-1", null))
        assertTrue(first.toString(), first.getBoolean("ok"))
        val firstData = first.getJSONObject("data")
        val firstItems = firstData.getJSONArray("items")
        assertEquals(50, firstItems.length())
        assertEquals("it1", firstItems.getJSONObject(0).getString("id"))
        assertEquals("magis1:movie:0:it1", firstItems.getJSONObject(0).getString("ref"))
        assertEquals("it50", firstItems.getJSONObject(49).getString("id"))
        assertEquals("magis1:movie:0:it50", firstItems.getJSONObject(49).getString("ref"))
        val next = firstData.getString("next")
        assertEquals("50", next)

        val second = JSONObject(h.xuperBrowse("row-1", next))
        assertTrue(second.toString(), second.getBoolean("ok"))
        val secondData = second.getJSONObject("data")
        val secondItems = secondData.getJSONArray("items")
        assertEquals(5, secondItems.length())
        assertEquals("it51", secondItems.getJSONObject(0).getString("id"))
        assertEquals("it55", secondItems.getJSONObject(4).getString("id"))
        assertTrue(secondData.toString(), secondData.isNull("next"))
    }

    @Test fun `browse of an unknown row ref returns not_found, matching every other plugin's browse`() = runBlocking {
        val row = MagisHomeRow(id = "row-1", title = "Row 1", shown = emptyList(), all = emptyList())
        val h = host(catalogServing(listOf(row)))

        val envelope = JSONObject(h.xuperBrowse("no-such-row", null))
        assertFalse(envelope.toString(), envelope.getBoolean("ok"))
        assertEquals(PluginErrors.NOT_FOUND, envelope.getString("code"))
        assertEquals("", envelope.getString("message"))
    }
}
