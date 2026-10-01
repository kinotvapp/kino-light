package com.arkiv.player.ui.live

import com.arkiv.player.data.db.OwnLiveSourceDao
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.OwnField
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
class OwnSourcesViewModelTest {
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

    private var saved = 0
    private fun vm(dao: FakeDao = FakeDao(), probe: suspend (OwnSourceForm) -> OwnProbe = { OwnProbe.Ok("bien") }): Pair<OwnSourcesViewModel, FakeDao> =
        OwnSourcesViewModel(OwnLiveStore(dao, newId = { "id-${dao.rows.size}" }), probe) { saved++ } to dao

    @Test fun `starting a new source opens an empty form of that kind`() {
        val (v, _) = vm()
        v.startNew(OwnKind.PLAYLIST)
        assertTrue(v.ui.value.open)
        assertEquals(OwnKind.PLAYLIST, v.ui.value.form.kind)
        assertNull(v.ui.value.editingId)
    }

    @Test fun `typing an http address raises the cleartext warning`() {
        val (v, _) = vm()
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "Uno", url = "http://tv.example.com/1.m3u8"))
        assertTrue(v.ui.value.cleartext)
        v.change(v.ui.value.form.copy(url = "https://tv.example.com/1.m3u8"))
        assertFalse(v.ui.value.cleartext)
    }

    @Test fun `saving an invalid form keeps the dialog open and shows the errors`() {
        val (v, dao) = vm()
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "", url = "http://192.168.1.5/x.m3u8"))
        v.save()
        assertTrue(v.ui.value.open)
        assertTrue(OwnField.URL in v.ui.value.errors && OwnField.NAME in v.ui.value.errors)
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, saved)
    }

    @Test fun `saving a valid form closes the dialog and tells the screen to reload`() {
        saved = 0
        val (v, dao) = vm()
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "Uno", url = "https://tv.example.com/1.m3u8"))
        v.save()
        assertFalse(v.ui.value.open)
        assertEquals(1, dao.rows.size)
        assertEquals(1, saved)
    }

    @Test fun `editing loads the source and saves over the same id`() {
        val (v, dao) = vm()
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "Uno", url = "https://tv.example.com/1.m3u8"))
        v.save()
        val id = dao.rows.keys.single()
        v.startEdit(dao.rows.getValue(id))
        assertEquals(id, v.ui.value.editingId)
        v.change(v.ui.value.form.copy(name = "Renombrado"))
        v.save()
        assertEquals("Renombrado", dao.rows.getValue(id).name)
        assertEquals(1, dao.rows.size)
    }

    @Test fun `probing shows the result and clears it when the address changes`() {
        val (v, _) = vm(probe = { OwnProbe.Ok("Encontré 3 canales") })
        v.startNew(OwnKind.PLAYLIST)
        v.change(v.ui.value.form.copy(name = "L", url = "http://tv.example.com/l.m3u"))
        v.probeNow()
        assertEquals(OwnProbe.Ok("Encontré 3 canales"), v.ui.value.probe)
        v.change(v.ui.value.form.copy(url = "http://tv.example.com/otra.m3u"))
        assertNull(v.ui.value.probe)
    }

    @Test fun `probing an invalid address never calls the network`() {
        var called = false
        val (v, _) = vm(probe = { called = true; OwnProbe.Ok("x") })
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(url = "http://10.0.0.1/x.m3u8"))
        v.probeNow()
        assertFalse(called)
        assertNotNull(v.ui.value.errors[OwnField.URL])
    }

    @Test fun `deleting removes it and reloads`() {
        val (v, dao) = vm()
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "Uno", url = "https://tv.example.com/1.m3u8"))
        v.save()
        saved = 0
        v.delete(dao.rows.keys.single())
        assertTrue(dao.rows.values.single().deleted)
        assertEquals(1, saved)
    }

    @Test fun `a probe that answers after the address changed is dropped, it describes another address`() {
        var release: (() -> Unit)? = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (v, _) = vm(probe = { gate.await(); OwnProbe.Ok("Se ve bien") })
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "Uno", url = "https://tv.example.com/1.m3u8"))
        v.probeNow()
        v.change(v.ui.value.form.copy(url = "https://tv.example.com/2.m3u8"))
        gate.complete(Unit)
        assertNull(v.ui.value.probe)
        assertFalse(v.ui.value.busy)
    }

    @Test fun `a failing store does not leave the dialog busy forever`() {
        val failing = object : com.arkiv.player.data.db.OwnLiveSourceDao by FakeDao() {
            override suspend fun all(): List<OwnLiveSourceEntity> = throw java.io.IOException("disco lleno")
        }
        val v = OwnSourcesViewModel(OwnLiveStore(failing), { OwnProbe.Ok("x") })
        v.startNew(OwnKind.CHANNEL)
        v.change(v.ui.value.form.copy(name = "Uno", url = "https://tv.example.com/1.m3u8"))
        v.save()
        assertFalse(v.ui.value.busy)
        assertEquals(OwnSourcesCopy.SAVE_FAILED, v.ui.value.notice)
    }

    // ---- "Pegar lista" / "Abrir archivo" ----

    private val m3u = "#EXTM3U\n#EXTINF:-1,Uno\nhttps://tv.example.com/uno.m3u8\n#EXTINF:-1,Dos\nhttps://tv.example.com/dos.m3u8"
    private fun pastedVm(dao: FakeDao = FakeDao(), probe: suspend (OwnSourceForm) -> OwnProbe = { OwnProbe.Ok("x") }) =
        OwnSourcesViewModel(OwnLiveStore(dao, newId = { "id-${dao.rows.size}" }, work = Dispatchers.Unconfined), probe, work = Dispatchers.Unconfined) { saved++ } to dao

    @Test fun `pasting a list fills the form with its text and says what it holds, then saves it without an address`() {
        val (v, dao) = pastedVm()
        v.startNew(OwnKind.PLAYLIST)
        v.usePasted("﻿$m3u\r\n")
        val ui = v.ui.value
        assertEquals(m3u, ui.form.pastedText)
        assertEquals(OwnSourcesCopy.PASTED_LABEL, ui.pasted)
        assertEquals(OwnProbe.Ok("Encontré 2 canales"), ui.probe)
        assertTrue(OwnField.URL !in ui.errors)
        v.change(ui.form.copy(name = "Pegada"))
        v.save()
        assertFalse(v.ui.value.open)
        assertEquals("kino-list:id-0", dao.rows.values.single().url)
        assertTrue(dao.partRows.isNotEmpty())
    }

    @Test fun `an empty clipboard or a bad list is reported on the address and keeps the form as it was`() {
        val (v, _) = pastedVm()
        v.startNew(OwnKind.PLAYLIST)
        v.usePasted(null)
        assertEquals(com.arkiv.player.data.live.OwnPastedList.EMPTY, v.ui.value.errors[OwnField.URL])
        v.usePasted("<html>hola</html>")
        assertTrue(v.ui.value.errors.getValue(OwnField.URL).contains("página web"))
        assertNull(v.ui.value.form.pastedText)
        assertNull(v.ui.value.pasted)
    }

    @Test fun `opening a file names the list after it, and a wrong or huge file is refused`() {
        val (v, _) = pastedVm()
        v.startNew(OwnKind.PLAYLIST)
        v.useFile("foto.png", m3u.toByteArray(), truncated = false)
        assertEquals(com.arkiv.player.data.live.OwnPastedList.WRONG_FILE, v.ui.value.errors[OwnField.URL])
        v.useFile("Fútbol.m3u", m3u.toByteArray(), truncated = true)
        assertEquals(com.arkiv.player.data.live.OwnPastedList.TOO_LARGE, v.ui.value.errors[OwnField.URL])
        v.useFile(null, null, truncated = false)
        assertEquals(com.arkiv.player.data.live.OwnPastedList.UNREADABLE_FILE, v.ui.value.errors[OwnField.URL])
        v.useFile("Fútbol.m3u", m3u.toByteArray(), truncated = false)
        assertEquals("Fútbol", v.ui.value.form.name)
        assertEquals(OwnSourcesCopy.fileLabel("Fútbol.m3u"), v.ui.value.pasted)
        assertTrue(v.ui.value.errors.isEmpty())
    }

    @Test fun `going back to an address drops the pasted text, and probing a pasted list never calls the network`() {
        var called = false
        val (v, _) = pastedVm(probe = { called = true; OwnProbe.Ok("x") })
        v.startNew(OwnKind.PLAYLIST)
        v.usePasted(m3u)
        v.probeNow()
        assertFalse(called)
        v.clearPasted()
        assertNull(v.ui.value.form.pastedText)
        assertNull(v.ui.value.pasted)
        assertNull(v.ui.value.probe)
    }

    @Test fun `editing a pasted list shows it as pasted and keeps its text when only the name changes`() {
        val (v, dao) = pastedVm()
        v.startNew(OwnKind.PLAYLIST)
        v.usePasted(m3u)
        v.change(v.ui.value.form.copy(name = "Pegada"))
        v.save()
        val row = dao.rows.values.single()
        v.startEdit(row)
        assertEquals(OwnSourcesCopy.PASTED_SAVED, v.ui.value.pasted)
        v.change(v.ui.value.form.copy(name = "Renombrada"))
        v.save()
        assertEquals("Renombrada", dao.rows.getValue(row.id).name)
        assertEquals(row.contentDigest, dao.rows.getValue(row.id).contentDigest)
    }

    @Test fun `a pasted list is shown as pasted in the managers, not as a weird address`() {
        assertEquals(OwnSourcesCopy.PASTED_LABEL, OwnSourcesCopy.hostOf("kino-list:abc"))
    }
}
