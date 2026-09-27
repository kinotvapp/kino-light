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

    private val result = GatewayResult(source = "plugin:xuper", title = "Peli", ref = "r", extra = mapOf("pluginItemId" to "c1"))
    private fun chapter(n: Int) = GatewayEpisode(number = n, title = "E$n", ref = "r$n")
    private val queued = mutableListOf<Pair<String, String>>()
    private val saved = mutableListOf<Pair<List<Int>, List<Int>>>()

    private fun actions(
        movieId: String? = "plugin:xuper:c1::0",
        chapterIds: Map<Int, String?> = emptyMap(),
        outcome: EnqueueOutcome = EnqueueOutcome.QUEUED,
        sourceFor: (String) -> String = { DownloadSource.sourceFor(it) { true } },
    ) = MagisDownloadActions(
        episodeIdForMovie = { movieId },
        episodeIdsForChapters = { _, chapters, chosen, _ ->
            saved += chapters.map { it.number } to chosen.map { it.number }
            chosen.map { chapterIds[it.number] }
        },
        sourceFor = sourceFor,
        enqueue = { id, source -> queued += id to source; outcome },
    )

    @Test
    fun `a movie is enqueued with the source its id maps to`() = runTest {
        assertEquals(EnqueueOutcome.QUEUED, actions().enqueueMovie(result))
        assertEquals(listOf("plugin:xuper:c1::0" to DownloadSource.XUPER), queued)
    }

    @Test
    fun `a movie that cannot be prepared queues nothing`() = runTest {
        assertNull(actions(movieId = null).enqueueMovie(result))
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `chosen chapters are saved as one batch with the whole list and enqueued one by one, unpreparable ones skipped`() = runTest {
        val all = listOf(chapter(1), chapter(2), chapter(3), chapter(4))
        val outcomes = actions(chapterIds = mapOf(1 to "plugin:xuper:c1::e1", 2 to null, 3 to "plugin:xuper:c1::e3"))
            .enqueueChapters(result, all, listOf(chapter(1), chapter(2), chapter(3)), null)
        assertEquals(2, outcomes.size)
        assertEquals("one save, of the whole list, for the three chosen", listOf(listOf(1, 2, 3, 4) to listOf(1, 2, 3)), saved)
        assertEquals(listOf("plugin:xuper:c1::e1" to DownloadSource.XUPER, "plugin:xuper:c1::e3" to DownloadSource.XUPER), queued)
    }

    @Test
    fun `nothing chosen saves and queues nothing`() = runTest {
        assertEquals(emptyList<EnqueueOutcome>(), actions().enqueueChapters(result, listOf(chapter(1)), emptyList(), null))
        assertTrue(saved.isEmpty())
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `the source is whatever the injected rule says for each id`() = runTest {
        // Without the Xuper check a plugin episode maps to "plugin", which has no strategy: the rule is the caller's.
        actions(sourceFor = { DownloadSource.sourceFor(it) }).enqueueMovie(result)
        assertEquals(listOf("plugin:xuper:c1::0" to "plugin"), queued)
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
