package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.local.DownloadSource
import com.arkiv.player.data.local.EnqueueOutcome

/**
 * The non-Compose core of "download a title" for the info page (`TitleSource.downloads`), kept from
 * the native Magis page: a movie is enqueued with the source its episode id maps to; a season is
 * enqueued chapter by chapter under `"magis"`, each one a separate file, and the queue already
 * knows how to group them by series.
 *
 * What stays with the caller, because it is Compose-only: the notification-permission request, the
 * duplicate notice and the toasts.
 *
 * The three dependencies come in as functions so this is testable without an `AppGraph`.
 */
class MagisDownloadActions(
    private val episodeIdForMovie: suspend (GatewayResult) -> String?,
    private val episodeIdForChapter: suspend (GatewayResult, GatewayEpisode, GatewaySeries?) -> String?,
    private val enqueue: suspend (episodeId: String, source: String) -> EnqueueOutcome,
) {
    /** Enqueues a movie. Null when its episode could not be prepared, in which case nothing was queued. */
    suspend fun enqueueMovie(result: GatewayResult): EnqueueOutcome? {
        val episodeId = episodeIdForMovie(result) ?: return null
        return enqueue(episodeId, DownloadSource.sourceFor(episodeId))
    }

    /** Enqueues each chapter in turn. A chapter whose episode could not be prepared is skipped. */
    suspend fun enqueueChapters(
        season: GatewayResult,
        chapters: List<GatewayEpisode>,
        series: GatewaySeries?,
    ): List<EnqueueOutcome> = chapters.mapNotNull { chapter ->
        val episodeId = episodeIdForChapter(season, chapter, series) ?: return@mapNotNull null
        enqueue(episodeId, "magis")
    }
}

/**
 * Text for the "queued" toast after downloading a movie from the info page, or null to show
 * nothing. Only [EnqueueOutcome.QUEUED] gets this toast: [EnqueueOutcome.ALREADY_QUEUED] and
 * [EnqueueOutcome.ALREADY_DOWNLOADED] already surface their own message through
 * `rememberDuplicateDownloadNotice`, so a "queued" toast on top of that would be misleading — the
 * person would see both "you already have that" and a false "queued".
 */
fun queuedDownloadToastText(outcome: EnqueueOutcome, movieTitle: String): String? =
    if (outcome == EnqueueOutcome.QUEUED) "Descarga de \"$movieTitle\" en cola" else null

/** The toast after enqueueing [requested] chapters, from what the queue answered for each. */
fun chapterEnqueueMessage(outcomes: List<EnqueueOutcome>, requested: Int): String {
    val queued = outcomes.count { it == EnqueueOutcome.QUEUED }
    return when {
        queued == 0 -> "Esos capítulos ya estaban guardados."
        queued == requested -> "Descargando $requested capítulo(s)…"
        else -> "Se encolaron $queued de $requested (el resto ya estaba)."
    }
}
