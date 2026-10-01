package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceDao
import com.arkiv.player.data.db.OwnLiveSourceEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnLiveStoreTest {
    private class FakeDao : OwnLiveSourceDao {
        val rows = LinkedHashMap<String, OwnLiveSourceEntity>()
        override fun flowAll() = flowOf(rows.values.filter { !it.deleted })
        override suspend fun all() = rows.values.filter { !it.deleted }
        override suspend fun get(id: String) = rows[id]
        override suspend fun save(s: OwnLiveSourceEntity) { rows[s.id] = s }
        override suspend fun delete(id: String) { rows[id]?.let { rows[id] = it.copy(deleted = true) } }
        override suspend fun count() = rows.values.count { !it.deleted }
        override suspend fun getSince(cursor: Long) = rows.values.filter { it.updatedAt > cursor }
        val partRows = LinkedHashMap<Pair<String, Int>, com.arkiv.player.data.db.OwnListPartEntity>()
        override suspend fun parts(sourceId: String) = partRows.values.filter { it.sourceId == sourceId }.sortedBy { it.part }
        override suspend fun part(sourceId: String, part: Int) = partRows[sourceId to part]
        override suspend fun saveParts(parts: List<com.arkiv.player.data.db.OwnListPartEntity>) { parts.forEach { partRows[it.sourceId to it.part] = it } }
        override suspend fun deleteParts(sourceId: String) { partRows.keys.removeAll { it.first == sourceId } }
        override suspend fun deleteStaleParts(sourceId: String, digest: String, parts: Int) {
            partRows.values.removeAll { it.sourceId == sourceId && (it.digest != digest || it.part >= parts) }
        }
        override suspend fun partsSince(cursor: Long) = partRows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
    }

    private var n = 0
    private fun store(dao: FakeDao = FakeDao(), now: Long = 1_000L) = OwnLiveStore(dao, { "id${++n}" }, { now }) to dao
    private fun ch(name: String, url: String) = OwnSourceForm(OwnKind.CHANNEL, name, url)

    @Test fun `a valid source is saved with a fresh id`() = runTest {
        val (s, dao) = store()
        assertEquals(OwnSaveResult.Saved, s.save(null, ch("Uno", "https://a.example.com/1.m3u8")))
        assertEquals(listOf("Uno"), dao.rows.values.map { it.name })
    }

    @Test fun `an invalid form saves nothing and reports the fields`() = runTest {
        val (s, dao) = store()
        val r = s.save(null, ch("", "http://192.168.1.5/x.m3u8")) as OwnSaveResult.Invalid
        assertTrue(OwnField.NAME in r.errors && OwnField.URL in r.errors)
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `the same address twice is refused, but editing a source keeps its own address`() = runTest {
        val (s, dao) = store()
        s.save(null, ch("Uno", "https://a.example.com/1.m3u8"))
        assertTrue(s.save(null, ch("Otro", "https://a.example.com/1.m3u8")) is OwnSaveResult.Invalid)
        val id = dao.rows.keys.single()
        assertEquals(OwnSaveResult.Saved, s.save(id, ch("Uno renombrado", "https://a.example.com/1.m3u8")))
        assertEquals("Uno renombrado", dao.rows.getValue(id).name)
    }

    @Test fun `a tombstoned address can be added again`() = runTest {
        val (s, dao) = store()
        s.save(null, ch("Uno", "https://a.example.com/1.m3u8"))
        s.delete(dao.rows.keys.single())
        assertEquals(OwnSaveResult.Saved, s.save(null, ch("Uno de nuevo", "https://a.example.com/1.m3u8")))
    }

    @Test fun `the limit of sources per device is enforced on create only`() = runTest {
        val (s, dao) = store()
        repeat(OwnLive.MAX_SOURCES) { i -> assertEquals(OwnSaveResult.Saved, s.save(null, ch("C$i", "https://a.example.com/$i.m3u8"))) }
        assertEquals(OwnSaveResult.TooMany, s.save(null, ch("C", "https://a.example.com/extra.m3u8")))
        assertEquals(OwnSaveResult.Saved, s.save(dao.rows.keys.first(), ch("Editado", "https://a.example.com/0.m3u8")))
    }

    @Test fun `deleting leaves a tombstone`() = runTest {
        val (s, dao) = store()
        s.save(null, ch("Uno", "https://a.example.com/1.m3u8"))
        val id = dao.rows.keys.single()
        s.delete(id)
        assertTrue(dao.rows.getValue(id).deleted)
    }

    @Test fun `a channel code finds its source, a playlist code finds it by the list key`() = runTest {
        val (s, dao) = store()
        s.save(null, ch("Uno", "https://a.example.com/1.m3u8"))
        s.save(null, OwnSourceForm(OwnKind.PLAYLIST, "Lista", "http://tv.example.com/get.php?u=ana"))
        val single = dao.rows.values.first { it.kind == "CHANNEL" }
        val list = dao.rows.values.first { it.kind == "PLAYLIST" }
        assertEquals(single.id, s.sourceFor(single.id)?.id)
        val key = PlaylistSource.cacheKey(list.url)
        assertEquals(list.id, s.sourceFor("~$key.c1")?.id)
        assertNull(s.sourceFor("~00000000.c1"))
    }

    @Test fun `two lists on the same server path are refused, they would share one cache file and the same channel codes`() = runTest {
        val (s, dao) = store()
        val a = OwnSourceForm(OwnKind.PLAYLIST, "Cuenta A", "http://tv.example.com/get.php?username=A&password=x&type=m3u")
        val b = OwnSourceForm(OwnKind.PLAYLIST, "Cuenta B", "http://tv.example.com/get.php?username=B&password=y&type=m3u")
        assertEquals(OwnSaveResult.Saved, s.save(null, a))
        val r = s.save(null, b) as OwnSaveResult.Invalid
        assertTrue(OwnField.URL in r.errors)
        assertEquals(1, dao.rows.size)
        // Editing the first one keeps its own key.
        assertEquals(OwnSaveResult.Saved, s.save(dao.rows.keys.single(), a.copy(name = "Cuenta A2")))
    }

    @Test fun `a channel on that same path is fine, only lists share a cache`() = runTest {
        val (s, _) = store()
        s.save(null, OwnSourceForm(OwnKind.PLAYLIST, "L", "http://tv.example.com/get.php?u=A"))
        assertEquals(OwnSaveResult.Saved, s.save(null, ch("C", "http://tv.example.com/get.php?u=B")))
    }

    @Test fun `an edit always gets a clock newer than the row it replaces, even when a peer's clock is ahead`() = runTest {
        val dao = FakeDao()
        val future = 9_999_999_999_999L
        dao.rows["s1"] = OwnLiveSourceEntity("s1", "CHANNEL", "Uno", "https://a.example.com/1.m3u8", updatedAt = future)
        val (s, _) = store(dao, now = 1_000L)
        assertEquals(OwnSaveResult.Saved, s.save("s1", ch("Uno editado", "https://a.example.com/1.m3u8")))
        assertTrue(dao.rows.getValue("s1").updatedAt > future)
    }

    @Test fun `a new source is saved with no clock, so the trigger seals it`() = runTest {
        val (s, dao) = store()
        s.save(null, ch("Uno", "https://a.example.com/1.m3u8"))
        assertEquals(0L, dao.rows.values.single().updatedAt)
    }
}
