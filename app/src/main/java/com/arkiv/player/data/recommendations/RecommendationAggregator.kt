package com.arkiv.player.data.recommendations

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.gateway.ContentSource
import kotlinx.coroutines.CancellationException

/**
 * Adds a "For you" row's card to the library.
 *
 * The networked, writing part of the save; every decision comes from [RecommendationSaving],
 * which is pure and does get tested. Same split as `NewChapterFinder`, which also joins the
 * repository and the gateway for a single background task.
 *
 * A Magis series comes in as a SEASON (`addMagisSeason`, one episode per chapter) and not as a
 * standalone ref: a series recommendation's `ref` points at the whole season, and saving it with
 * `addMagisSource` left the item with a single episode and in the Movies row. A Caracol series
 * comes in just as complete, but through `addDituSeason`: [RecommendationSaving.targetFor] first
 * decides which of the two sources the recommendation is from.
 */
class RecommendationAggregator(
    private val repo: ArkivRepository,
    private val gateway: ContentSource,
) {

    /**
     * Saves [rec] and returns the item id to navigate to, or null if there's nothing to navigate to.
     */
    suspend fun add(rec: RecommendationEntity): String? = when (val target = RecommendationSaving.targetFor(rec)) {
        is RecommendationTarget.Magis -> addFromMagis(rec, target)
    }

    private suspend fun addFromMagis(rec: RecommendationEntity, target: RecommendationTarget.Magis): String? {
        val season = if (RecommendationSaving.needsChapters(rec)) seasonFromGateway(rec) else null
        val saved = if (season != null) {
            repo.addMagisSeason(
                contentId = target.contentId,
                title = rec.titulo,
                chapters = season.chapters,
                // The recommendation's ref IS the season's: it stays saved on the item and
                // `NewChapterFinder` can ask for new chapters later on.
                seriesRef = rec.ref,
                posterUrl = rec.posterUrl,
                tmdbId = season.tmdbId,
                seasonNumber = season.seasonNumber,
            ).isNotEmpty()
        } else {
            // Movies, and series whose chapters couldn't be listed: the standalone save, which is
            // better than saving nothing.
            repo.addMagisSource(
                ref = rec.ref,
                contentId = target.contentId,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
            ) != null
        }
        return if (saved) RecommendationSaving.itemIdFor(target) else null
    }

    /**
     * The chapters from the Magis portal, or null if it could not give them.
     *
     * Only [addFromMagis] calls this. A failure here is NOT terminal: the portal listing can fail
     * or come back empty, and an old row whose ref could not be read falls back to `Magis(rec.id)`,
     * where `MagisSource.episodesWithSeries` throws "ese ref no es de magis". Neither may leave
     * unsaved something that can still be played. [CancellationException] is rethrown: swallowing
     * it would keep running a coroutine its scope already considers dead.
     */
    private suspend fun seasonFromGateway(rec: RecommendationEntity): RecommendationSeason? = try {
        val (chapters, series) = gateway.episodesWithSeries(rec.ref)
        RecommendationSaving.seasonFor(chapters, series)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "chapters for \"${rec.titulo}\": ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "ArkivRecs"
    }
}
