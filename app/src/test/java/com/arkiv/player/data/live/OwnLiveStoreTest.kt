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
    }

    private var n = 0
    private fun store(dao: FakeDao = FakeDao()) = OwnLiveStore(dao) { "id${++n}" } to dao
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
}
