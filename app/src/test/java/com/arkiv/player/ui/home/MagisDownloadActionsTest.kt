package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.local.EnqueueOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagisDownloadActionsTest {

    private val result = GatewayResult(source = "magis", title = "Peli", ref = "r", extra = mapOf("content_id" to "c1"))
    private fun chapter(n: Int) = GatewayEpisode(number = n, title = "E$n", ref = "r$n")
    private val queued = mutableListOf<Pair<String, String>>()

    private fun actions(
        movieId: String? = "magis:c1::0",
        chapterIds: Map<Int, String?> = emptyMap(),
        outcome: EnqueueOutcome = EnqueueOutcome.QUEUED,
    ) = MagisDownloadActions(
        episodeIdForMovie = { movieId },
        episodeIdForChapter = { _, chapter, _ -> chapterIds[chapter.number] },
        enqueue = { id, source -> queued += id to source; outcome },
    )

    @Test
    fun `a movie is enqueued with the source its id maps to`() = runTest {
        assertEquals(EnqueueOutcome.QUEUED, actions().enqueueMovie(result))
        assertEquals(listOf("magis:c1::0" to DownloadSource.sourceFor("magis:c1::0")), queued)
    }

    @Test
    fun `a movie that cannot be prepared queues nothing`() = runTest {
        assertNull(actions(movieId = null).enqueueMovie(result))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `chapters are enqueued one by one under the magis source and unpreparable ones are skipped`() = runTest {
        val outcomes = actions(chapterIds = mapOf(1 to "magis:c1::e1", 2 to null, 3 to "magis:c1::e3"))
            .enqueueChapters(result, listOf(chapter(1), chapter(2), chapter(3)), null)
        assertEquals(2, outcomes.size)
        assertEquals(listOf("magis:c1::e1" to "magis", "magis:c1::e3" to "magis"), queued)
    }

    @Test
    fun `the message says when everything was already saved`() {
        val already = listOf(EnqueueOutcome.ALREADY_DOWNLOADED, EnqueueOutcome.ALREADY_QUEUED)
        assertEquals("Esos capítulos ya estaban guardados.", chapterEnqueueMessage(already, requested = 2))
        assertEquals("Esos capítulos ya estaban guardados.", chapterEnqueueMessage(emptyList(), requested = 2))
    }

    @Test
    fun `the message counts a full batch`() {
        val all = List(3) { EnqueueOutcome.QUEUED }
        assertEquals("Descargando 3 capítulo(s)…", chapterEnqueueMessage(all, requested = 3))
    }

    @Test
    fun `the message says how many were new when only some were`() {
        val some = listOf(EnqueueOutcome.QUEUED, EnqueueOutcome.ALREADY_DOWNLOADED, EnqueueOutcome.QUEUED)
        assertEquals("Se encolaron 2 de 3 (el resto ya estaba).", chapterEnqueueMessage(some, requested = 3))
    }

    // queuedDownloadToastText: only a fresh EnqueueOutcome.QUEUED gets the "queued" toast.
    // ALREADY_QUEUED and ALREADY_DOWNLOADED already surface their own message through
    // rememberDuplicateDownloadNotice, so this must stay silent for them — otherwise the person would
    // see both "you already have that" and a false "queued".

    @Test
    fun `a queued movie gets the toast with its title`() {
        assertEquals("Descarga de \"Matrix\" en cola", queuedDownloadToastText(EnqueueOutcome.QUEUED, "Matrix"))
    }

    @Test
    fun `an already queued or downloaded movie gets no toast`() {
        assertNull(queuedDownloadToastText(EnqueueOutcome.ALREADY_QUEUED, "Matrix"))
        assertNull(queuedDownloadToastText(EnqueueOutcome.ALREADY_DOWNLOADED, "Matrix"))
    }
}
