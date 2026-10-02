package com.arkiv.player.ui.live

import com.arkiv.player.data.db.OwnLiveSourceDao
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.IptvOrgCatalog
import com.arkiv.player.data.live.XtreamUrl
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnLiveStore
import com.arkiv.player.data.live.OwnProbe
import com.arkiv.player.data.live.OwnSourceForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnSourcesXtreamViewModelTest {
    private class FakeDao : OwnLiveSourceDao {
        val rows = LinkedHashMap<String, OwnLiveSourceEntity>()
        override fun flowAll() = flowOf(rows.values.filter { !it.deleted })
        override suspend fun all() = rows.values.filter { !it.deleted }
        override suspend fun get(id: String) = rows[id]
        override suspend fun save(s: OwnLiveSourceEntity) { rows[s.id] = s }
        override suspend fun delete(id: String) { rows[id]?.let { rows[id] = it.copy(deleted = true) } }
        override suspend fun count() = rows.values.count { !it.deleted }
        override suspend fun getSince(cursor: Long) = rows.values.toList()
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

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private var probed: OwnSourceForm? = null
    private fun vm(dao: FakeDao = FakeDao()): Pair<OwnSourcesViewModel, FakeDao> =
        OwnSourcesViewModel(OwnLiveStore(dao, newId = { "id-${dao.rows.size}" }), { f -> probed = f; OwnProbe.Ok("bien") }, work = Dispatchers.Unconfined) to dao

    @Test fun `the Xtream form saves as an ordinary list whose address holds the login`() {
        val (v, dao) = vm()
        v.startNewXtream()
        v.change(v.ui.value.form.copy(name = "Mi servidor"))
        v.changeXtream(XtreamInput("miservidor.com:8080", "juan", "s3cret"))
        assertTrue(v.ui.value.cleartext)
        v.save()
        val row = dao.rows.values.single()
        assertEquals("PLAYLIST", row.kind)
        assertEquals("http://miservidor.com:8080/player_api.php?username=juan&password=s3cret", row.url)
        assertFalse(v.ui.value.open)
    }

    @Test fun `each Xtream field shows its own error and nothing is saved`() {
        val (v, dao) = vm()
        v.startNewXtream()
        v.change(v.ui.value.form.copy(name = "x"))
        v.save()
        assertEquals(setOf(XtreamUrl.XtreamField.SERVER, XtreamUrl.XtreamField.USERNAME, XtreamUrl.XtreamField.PASSWORD), v.ui.value.xtreamErrors.keys)
        assertTrue(dao.rows.isEmpty())
        v.changeXtream(v.ui.value.xtream!!.copy(username = "juan"))
        assertEquals(setOf(XtreamUrl.XtreamField.SERVER, XtreamUrl.XtreamField.PASSWORD), v.ui.value.xtreamErrors.keys)
    }

    @Test fun `a whole get_php address typed in Servidor fills the three fields`() {
        val (v, _) = vm()
        v.startNewXtream()
        v.changeXtream(XtreamInput(server = "http://tv.example.com:8000/get.php?username=ana&password=clave1&type=m3u_plus"))
        assertEquals(XtreamInput("http://tv.example.com:8000", "ana", "clave1"), v.ui.value.xtream)
    }

    @Test fun `pasting a single address goes to the address field, a Xtream one to the Xtream fields`() {
        val (v, _) = vm()
        v.startNew(OwnKind.CHANNEL)
        v.usePasted("  https://tv.example.com/lista.m3u8  ")
        assertEquals("https://tv.example.com/lista.m3u8", v.ui.value.form.url)
        assertEquals(OwnKind.PLAYLIST, v.ui.value.form.kind)
        assertNull(v.ui.value.form.pastedText)
        v.usePasted("http://tv.example.com:8000/player_api.php?username=ana&password=clave1")
        assertEquals(XtreamInput("http://tv.example.com:8000", "ana", "clave1"), v.ui.value.xtream)
        assertEquals("", v.ui.value.form.url)
    }

    @Test fun `pasting several addresses is a list of its own`() {
        val (v, _) = vm()
        v.startNew(OwnKind.PLAYLIST)
        v.usePasted("Uno,http://a.example.com/1.m3u8\nhttp://b.example.com/2.m3u8")
        assertNotNull(v.ui.value.form.pastedText)
        assertTrue((v.ui.value.probe as OwnProbe.Ok).message.contains("2 canales"))
    }

    @Test fun `a player_api address typed in the address field switches to the Xtream fields`() {
        val (v, _) = vm()
        v.startNew(OwnKind.PLAYLIST)
        v.change(v.ui.value.form.copy(url = "https://tv.example.com/player_api.php?username=ana&password=clave1"))
        assertEquals("ana", v.ui.value.xtream?.username)
    }

    @Test fun `editing a saved Xtream server opens its three fields, and Probar checks the built address`() {
        val (v, dao) = vm()
        v.startNewXtream()
        v.change(v.ui.value.form.copy(name = "S"))
        v.changeXtream(XtreamInput("https://tv.example.com", "ana", "clave1"))
        v.save()
        val saved = dao.rows.values.single()
        v.startEdit(saved)
        assertEquals(XtreamInput("https://tv.example.com", "ana", "clave1"), v.ui.value.xtream)
        assertEquals("", v.ui.value.form.url)
        v.probeNow()
        assertEquals(saved.url, probed!!.url)
        assertEquals("bien", (v.ui.value.probe as OwnProbe.Ok).message)
        v.changeXtream(v.ui.value.xtream!!.copy(password = "nueva"))
        v.save()
        assertTrue(dao.rows.getValue(saved.id).url.endsWith("password=nueva"))
    }

    @Test fun `the form state never prints the password`() {
        val (v, _) = vm()
        v.startNewXtream()
        v.changeXtream(XtreamInput("tv.example.com", "ana", "clave1"))
        assertFalse(v.ui.value.xtream.toString().let { it.contains("clave1") || it.contains("ana") })
    }

    @Test fun `an iptv-org pick is saved as a list, a second pick of the same one is refused`() {
        val (v, dao) = vm()
        v.openPicker()
        assertTrue(v.ui.value.picker)
        val co = IptvOrgCatalog.countries.first { it.code == "co" }
        v.addIptvOrg(co)
        assertEquals("https://iptv-org.github.io/iptv/countries/co.m3u", dao.rows.values.single().url)
        assertEquals("iptv-org · Colombia", dao.rows.values.single().name)
        assertFalse(v.ui.value.picker)
        v.openPicker()
        v.addIptvOrg(co)
        assertEquals(1, dao.rows.size)
        assertTrue(v.ui.value.picker && v.ui.value.notice!!.contains("Ya agregaste"))
        v.addIptvOrg(IptvOrgCatalog.languages.first { it.code == "spa" })
        assertEquals(2, dao.rows.size)
    }

    @Test fun `the manager labels a Xtream row by its server only`() {
        val url = "http://tv.example.com:8080/player_api.php?username=ana&password=clave1"
        assertEquals("Xtream", OwnSourcesCopy.kindLabel("PLAYLIST", url))
        assertEquals("tv.example.com", OwnSourcesCopy.hostOf(url))
        assertEquals("Lista", OwnSourcesCopy.kindLabel("PLAYLIST", "https://a.example.com/l.m3u"))
        assertEquals("Canal", OwnSourcesCopy.kindLabel("CHANNEL", "https://a.example.com/c.m3u8"))
    }
}
