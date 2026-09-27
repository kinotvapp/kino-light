package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.local.EnqueueOutcome

/**
 * The non-Compose core of "download a title" from the info page (`TitleSource.downloads`), kept
 * from the native Magis page and now serving plugin titles (the Xuper install's, and those of any
 * plugin that declared `download`): a movie is saved and enqueued; a season's chosen chapters are
 * saved in ONE batch (the whole list the page loaded, as playing saves it) and enqueued one by one,
 * each a separate file, under the source their id maps to ([sourceFor]: `DownloadSource.XUPER` for
 * the recognized Xuper install, `DownloadSource.PLUGIN_DOWNLOAD` for the others). The queue already
 * knows how to group them by series.
 *
 * What stays with the caller, because it is Compose-only: the notification-permission request, the
 * duplicate notice and the toasts.
 *
 * The dependencies come in as functions so this is testable without an `AppGraph`.
 */
class MagisDownloadActions(
    /** Saves the movie and returns its library episode id, null when it could not be prepared. */
    private val episodeIdForMovie: suspend (GatewayResult) -> String?,
    /**
     * Saves the season with every chapter the page listed and returns the library episode id of
     * each of the chosen ones, in order, null for one the save did not keep.
     */
    private val episodeIdsForChapters: suspend (
        season: GatewayResult,
        chapters: List<GatewayEpisode>,
        chosen: List<GatewayEpisode>,
        series: GatewaySeries?,
    ) -> List<String?>,
    /** The `downloads.source` an episode id downloads under (see `DownloadSource.sourceFor`). */
    private val sourceFor: (episodeId: String) -> String,
    private val enqueue: suspend (episodeId: String, source: String) -> EnqueueOutcome,
) {
    /** Enqueues a movie. Null when its episode could not be prepared, in which case nothing was queued. */
    suspend fun enqueueMovie(result: GatewayResult): EnqueueOutcome? {
        val episodeId = episodeIdForMovie(result) ?: return null
        return enqueue(episodeId, sourceFor(episodeId))
    }

    /**
     * Saves the season once, with [chapters] whole, and enqueues each of [chosen] in turn. A
     * chapter whose episode could not be prepared is skipped.
     */
    suspend fun enqueueChapters(
        season: GatewayResult,
        chapters: List<GatewayEpisode>,
        chosen: List<GatewayEpisode>,
        series: GatewaySeries?,
    ): List<EnqueueOutcome> {
        if (chosen.isEmpty()) return emptyList()
        return episodeIdsForChapters(season, chapters, chosen, series)
            .filterNotNull()
            .map { episodeId -> enqueue(episodeId, sourceFor(episodeId)) }
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
