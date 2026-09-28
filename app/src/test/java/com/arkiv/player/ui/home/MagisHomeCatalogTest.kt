package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.CatalogSection
import com.arkiv.player.data.db.HomeCatalogCacheDao
import com.arkiv.player.data.db.HomeCatalogCacheEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class MagisHomeCatalogTest {

    private fun dramas(type: String) = listOf(
        CatalogSection(
            id = 1, name = "All", adult = false,
            items = (1..6).map {
                CatalogItem(id = "$type$it", title = "t$it", poster = null, durationS = 0, type = type, genres = listOf("Drama"))
            },
        ),
    )

    @Test
    fun `asks for the four roots and never the adults one`() = runTest {
        val asked = mutableListOf<String>()
        MagisHomeCatalog(tree = { root -> synchronized(asked) { asked += root }; emptyList() }).rows()

        assertEquals(setOf("peliculas", "series", "anime", "infantil"), asked.toSet())
        assertEquals(4, asked.size)
    }

    @Test
    fun `a root that fails doesn't take the others down`() = runTest {
        val rows = MagisHomeCatalog(tree = { root ->
            when (root) {
                "peliculas" -> error("portal down")
                "series" -> dramas("teleplay")
                else -> emptyList()
            }
        }).rows()

        assertTrue(rows.any { it.id == "magis_g_series_drama" })
        assertTrue(rows.none { it.id.contains("peliculas") })
    }

    @Test
    fun `reports the roots that failed or came back empty as missing`() = runTest {
        val home = MagisHomeCatalog(tree = { root ->
            when (root) {
                "peliculas" -> error("portal down")
                "anime" -> emptyList()
                "series" -> dramas("teleplay")
                else -> dramas("kids")
            }
        }).load()

        assertEquals(setOf(MagisKind.PELICULAS, MagisKind.ANIME), home.missing)
        assertTrue(home.rows.any { it.id == "magis_g_series_drama" })
    }

    @Test
    fun `a pass where every root answered misses nothing`() = runTest {
        val home = MagisHomeCatalog(tree = { dramas("teleplay") }).load()

        assertEquals(emptySet<MagisKind>(), home.missing)
    }

    // --- freshness: forced reload, partial passes, failed refetches --------------------------------

    private class FakeDao(var entity: HomeCatalogCacheEntity? = null) : HomeCatalogCacheDao {
        override suspend fun get(id: String) = entity
        override suspend fun save(row: HomeCatalogCacheEntity) { entity = row }
        override suspend fun clear() { entity = null }
    }

    private val seedRows = listOf(
        MagisHomeRow(
            "magis_seed", "Seed",
            listOf(CatalogItem(id = "old1", title = "old", poster = null, durationS = 0, type = "movie", genres = listOf("Drama"))),
            listOf(CatalogItem(id = "old1", title = "old", poster = null, durationS = 0, type = "movie", genres = listOf("Drama"))),
        ),
    )

    private var now = 10 * 3_600_000L
    private val cachedReads = AtomicInteger()
    private val freshReads = mutableListOf<String>()
    private var failing = setOf<String>()

    private fun catalog(dao: FakeDao) = MagisHomeCatalog(
        tree = { root ->
            cachedReads.incrementAndGet()
            if (root in failing) error("portal down") else rootDramas(root)
        },
        freshTree = { root ->
            synchronized(freshReads) { freshReads += root }
            if (root in failing) error("portal down") else rootDramas(root)
        },
        store = HomeCatalogStore(dao),
        now = { now },
    )

    /** [dramas] with ids unique per root, so no title is claimed by two roots. */
    private fun rootDramas(root: String) = dramas(if (root == "peliculas") "movie" else "teleplay")
        .map { s -> s.copy(items = s.items.map { it.copy(id = root + it.id) }) }

    private fun seededDao() = FakeDao(HomeCatalogCacheEntity(rowsJson = HomeCatalogCodec.encode(seedRows), fetchedAt = now - 60_000L))

    @Test
    fun `without invalidate a fresh snapshot is served and nothing is ever forced`() = runTest {
        val c = catalog(seededDao())
        assertEquals(listOf("magis_seed"), c.rows().map { it.id })
        assertEquals(0, cachedReads.get())
        assertEquals(emptyList<String>(), freshReads)
    }

    @Test
    fun `invalidate makes the next pass refetch every root past the in-memory cache, once`() = runTest {
        val dao = seededDao()
        val c = catalog(dao)
        c.invalidate()
        val rows = c.rows()
        assertEquals(4, freshReads.size)
        assertEquals(0, cachedReads.get())
        assertTrue(rows.any { it.id == "magis_g_series_drama" })
        assertTrue("the complete pass replaced the snapshot", dao.entity!!.fetchedAt == now)
        // The new snapshot is fresh: no more portal reads.
        c.rows()
        assertEquals(4, freshReads.size)
        assertEquals(0, cachedReads.get())
    }

    @Test
    fun `a forced refetch that fails keeps showing the last good snapshot without deleting it`() = runTest {
        val dao = seededDao()
        val c = catalog(dao)
        failing = setOf("peliculas", "series", "anime", "infantil")
        c.invalidate()
        assertEquals(listOf("magis_seed"), c.rows().map { it.id })
        assertNotNull("the snapshot survives a failed refetch", dao.entity)
        assertEquals(listOf("magis_seed"), HomeCatalogCodec.decode(dao.entity!!.rowsJson).map { it.id })
    }

    @Test
    fun `a partial pass is served for five minutes only, then only the missing root is refetched`() = runTest {
        val dao = seededDao()
        val c = catalog(dao)
        failing = setOf("peliculas")
        c.invalidate()
        val partial = c.rows()
        assertTrue(partial.none { it.id.contains("peliculas") })
        assertEquals(4, freshReads.size)
        val snapshotAt = dao.entity!!.fetchedAt

        now += 4 * 60_000L
        assertEquals(partial, c.rows())
        assertEquals("within five minutes the partial pass is reused", 4, freshReads.size)

        failing = emptySet()
        now += 60_000L
        val complete = c.rows()
        assertEquals("only the root that failed is forced again", listOf("peliculas"), freshReads.drop(4))
        assertEquals("the roots that answered come from the in-memory cache", 3, cachedReads.get())
        assertTrue("a later complete pass replaces the partial one", complete.any { it.id.contains("peliculas") })
        assertTrue("and is persisted", dao.entity!!.fetchedAt > snapshotAt)
    }

    @Test
    fun `a partial pass is never persisted over the last complete snapshot`() = runTest {
        val dao = seededDao()
        failing = setOf("anime")
        val c = catalog(dao)
        c.invalidate()
        c.rows()
        assertEquals(listOf("magis_seed"), HomeCatalogCodec.decode(dao.entity!!.rowsJson).map { it.id })
    }

    @Test
    fun `a new invalidate drops the partial pass memo and retries at once`() = runTest {
        val c = catalog(seededDao())
        failing = setOf("peliculas")
        c.invalidate()
        c.rows()
        failing = emptySet()
        c.invalidate()
        assertTrue(c.rows().any { it.id.contains("peliculas") })
    }

    @Test
    fun `concurrent passes after an invalidate ask each root once`() = runTest {
        val c = catalog(seededDao())
        c.invalidate()
        listOf(async { c.rows() }, async { c.rows() }, async { c.load() }).awaitAll()
        assertEquals(4, freshReads.size)
    }

    @Test
    fun `back online drops a partial pass memo so the missing root is retried at once`() = runTest {
        val c = catalog(seededDao())
        failing = setOf("peliculas")
        c.invalidate()
        c.rows()
        failing = emptySet()
        c.forgetFailedPass()
        assertTrue(c.rows().any { it.id.contains("peliculas") })
        assertEquals(listOf("peliculas"), freshReads.drop(4))
    }
}
