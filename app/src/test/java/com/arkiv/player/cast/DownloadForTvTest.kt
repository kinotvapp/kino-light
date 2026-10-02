package com.arkiv.player.cast

import android.content.Context
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.local.PrepState
import com.arkiv.player.dlna.DlnaDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Descargar y preparar para la TV", the last option of an exhausted cast ([DownloadForTv]): when it
 * is offered, what accepting it does, and how the prepared download gets to the TV.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadForTvTest {

    private val vod = CastGaveUp.Exhausted(CastGaveUp.Receiver.CHROMECAST, "plugin:x::ep1", "La película", live = false, routes = "direct=refused")

    private fun offer(e: CastGaveUp.Exhausted = vod, tv: Boolean = false, downloadable: Boolean = true, downloaded: Boolean = false, keptAsIs: Boolean = false) =
        DownloadForTvPolicy.offer(e, tv, downloadable, downloaded, keptAsIs)

    @Test fun `offered for a downloadable VOD title only`() {
        assertEquals(DownloadForTvPolicy.Kind.DOWNLOAD, offer())
        assertNull("never live", offer(vod.copy(live = true)))
        assertNull("a channel id without a title", offer(vod.copy(episodeId = "")))
        assertNull("not downloadable (no strategy, a plugin without `download`)", offer(downloadable = false))
        assertNull("never on a TV", offer(tv = true))
        assertEquals("over DLNA too", DownloadForTvPolicy.Kind.DOWNLOAD, offer(vod.copy(receiver = CastGaveUp.Receiver.DLNA)))
    }

    @Test fun `already downloaded, it sends the download instead`() {
        assertEquals(DownloadForTvPolicy.Kind.SEND_DOWNLOAD, offer(downloaded = true))
        assertEquals("even if its source could not download it today", DownloadForTvPolicy.Kind.SEND_DOWNLOAD, offer(downloaded = true, downloadable = false))
        assertNull("a file kept as it is (AC-3…) would fail the same way", offer(downloaded = true, keptAsIs = true))
        assertNull("still never live", offer(vod.copy(live = true), downloaded = true))
    }

    @Test fun `a cast prefers the download once the title is on the phone`() {
        val downloaded = setOf("plugin:x::ep1")
        assertTrue("opened from the network before the download finished", DownloadForTvPolicy.reopenAsLocal("plugin:x::ep1", downloaded, null))
        assertTrue(DownloadForTvPolicy.reopenAsLocal("plugin:x::ep1", downloaded, "magis:other"))
        assertTrue("already playing the file", !DownloadForTvPolicy.reopenAsLocal("plugin:x::ep1", downloaded, "plugin:x::ep1"))
        assertTrue("not downloaded", !DownloadForTvPolicy.reopenAsLocal("plugin:y::ep2", downloaded, null))
        assertTrue(!DownloadForTvPolicy.reopenAsLocal(null, downloaded, null))
    }

    @Test fun `the waiting list survives a restart and forgets what is days old`() {
        val now = 10_000_000_000L
        val raw = DownloadForTvPolicy.encodePending(mapOf("a" to (now to "Título\tcon tab"), "b" to (now - DownloadForTvPolicy.PENDING_MAX_AGE_MS - 1 to "Viejo")))
        val back = DownloadForTvPolicy.decodePending(raw, now)
        assertEquals(setOf("a"), back.keys)
        assertEquals("Título con tab", back["a"]!!.second)
        assertEquals(emptyMap<String, Pair<Long, String>>(), DownloadForTvPolicy.decodePending(null, now))
    }

    // ---- DownloadForTv itself --------------------------------------------------------------------

    private val completed = MutableStateFlow<Set<String>>(emptySet())
    private val enqueued = mutableListOf<String>()
    private val prepared = mutableListOf<String>()
    private val notified = mutableListOf<Pair<String, String>>()
    private val said = mutableListOf<String>()
    private var outcome = EnqueueOutcome.QUEUED

    private val prefs by lazy { RuntimeEnvironment.getApplication().getSharedPreferences("dft-test", Context.MODE_PRIVATE) }

    private fun subject() = DownloadForTv(
        prefs = prefs,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isTelevision = { false },
        downloadable = { it.startsWith("plugin:x") },
        completedIds = completed,
        keptAsIs = { false },
        enqueueDownload = { enqueued += it; outcome },
        requestPrep = { prepared += it },
        notifyReady = { id, title -> notified += id to title },
        say = { said += it },
    )

    @Before fun setUp() {
        prefs.edit().clear().commit()
        SendToTv.requests.value = null
        PlayerReopen.requests.value = null
        PlayerOnScreen.episodeId = null
        PlayerOnScreen.foreground = false
    }

    @After fun tearDown() = setUp()

    @Test fun `accepted, the title is queued and waits for the TV`() {
        val d = subject()
        val offer = d.offerFor(vod)
        assertNotNull(offer)
        assertEquals("Descargar y preparar para la TV", offer!!.label)
        assertTrue(offer.explanation.contains("ocupa espacio en tu teléfono"))
        assertNull("not downloadable", d.offerFor(vod.copy(episodeId = "plugin:z::1")))
        offer.start(vod)
        assertEquals(listOf("plugin:x::ep1"), enqueued)
        assertTrue(said.single().contains("Te avisamos"))
        // The download finished and was prepared: "Listo para la TV", and nothing else off-screen.
        d.onPrepared("plugin:x::ep1", PrepState.READY)
        assertEquals(listOf("plugin:x::ep1" to "La película"), notified)
        assertNull(SendToTv.requests.value)
        assertNull(PlayerReopen.requests.value)
        // Only once.
        d.onPrepared("plugin:x::ep1", PrepState.READY)
        assertEquals(1, notified.size)
    }

    @Test fun `ready while the player shows it, reopened from the file and sent to the same TV`() {
        val tvSet = DlnaDevice("Sala", "http://192.168.1.20/ctl")
        val e = vod.copy(receiver = CastGaveUp.Receiver.DLNA, dlnaDevice = tvSet, positionMs = 61_000)
        val d = subject()
        d.offerFor(e)!!.start(e)
        // Nothing prepared yet (the download is still running): keeps waiting.
        d.onPrepared(e.episodeId, null)
        assertTrue(notified.isEmpty())
        PlayerOnScreen.episodeId = e.episodeId
        PlayerOnScreen.foreground = true
        d.onPrepared(e.episodeId, PrepState.READY)
        assertEquals(e.episodeId, PlayerReopen.requests.value)
        val request = SendToTv.requests.value!!
        assertEquals(tvSet, request.target!!.dlnaDevice)
        assertEquals(61_000L, request.target!!.positionMs)
        assertNotNull(SendToTv.take(request, e.episodeId))
        assertNull("taken once", SendToTv.take(request, e.episodeId))
    }

    @Test fun `already downloaded, the offer sends it, through its preparation`() {
        completed.value = setOf("plugin:x::ep1")
        val d = subject()
        val offer = d.offerFor(vod)!!
        assertEquals("Enviar la descarga a la TV", offer.label)
        offer.start(vod)
        assertEquals(listOf("plugin:x::ep1"), prepared)
        assertTrue(enqueued.isEmpty())
        d.onPrepared("plugin:x::ep1", PrepState.READY)
        assertEquals(1, notified.size)
    }

    @Test fun `a queue that says it was already there prepares it right away`() {
        outcome = EnqueueOutcome.ALREADY_DOWNLOADED
        val d = subject()
        d.offerFor(vod)!!.start(vod)
        assertEquals(listOf("plugin:x::ep1"), prepared)
    }

    @Test fun `a request for another title is not this player's`() {
        SendToTv.offer("a")
        val r = SendToTv.requests.value
        assertNull(SendToTv.take(r, "b"))
        assertNull(SendToTv.take(r, "a", now = System.currentTimeMillis() + SendToTv.MAX_AGE_MS + 1))
    }
}
