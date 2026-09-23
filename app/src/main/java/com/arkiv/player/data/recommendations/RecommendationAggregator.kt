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
     *
     * Almost always null lines up with "nothing ended up saved", but not always: at
     * [addFromCaracol]'s edge where not even the chosen episode could be saved on its own, the
     * series may have still landed in the library (`addDituSeason` wrote it before failing on the
     * chosen one), just without that episode ready to play.
     */
    suspend fun add(rec: RecommendationEntity): String? = when (val target = RecommendationSaving.targetFor(rec)) {
        is RecommendationTarget.Magis -> addFromMagis(rec, target)
        is RecommendationTarget.Caracol -> addFromCaracol(rec, target)
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
     * Caracol decides by its ref and not by `rec.tipo`: a `BUNDLE`/`GROUP_OF_BUNDLES` is listed and
     * saved whole, like in search (`SearchPlayback.playDituSeason`); a `VOD` is saved alone.
     * The chosen one is the first episode: nobody picked one, and `addDituSeason` needs some.
     */
    private suspend fun addFromCaracol(rec: RecommendationEntity, target: RecommendationTarget.Caracol): String? {
        val tmdbId = rec.tmdbId.takeIf { it > 0 }
        val isSeries = com.arkiv.player.data.ditu.DituRef.decode(rec.ref)?.isSeries == true
        val episodeId = if (isSeries) {
            val (chapters, series) = chaptersOf(rec) ?: return null
            val list = chapters.map { com.arkiv.player.data.DituEntities.caracolChapter(it, series) }
            val chosen = list.firstOrNull() ?: return null
            repo.addDituSeason(
                seriesRef = rec.ref,
                title = rec.titulo,
                chapters = list,
                chosen = chosen,
                posterUrl = rec.posterUrl.ifBlank { series?.posterUrl.orEmpty() },
                backdropUrl = series?.backdropUrl.orEmpty(),
                tmdbId = tmdbId,
                // `rec.titulo` is TMDB's (the cascade confirmed it), i.e. the canonical one.
                tituloCanonico = rec.titulo.takeIf { tmdbId != null },
            ) ?: run {
                // `addDituSeason` already wrote the item and its episodes as soon as it found a
                // valid contentId (see its KDoc): the null here is NOT "nothing got saved", it's
                // that [chosen] -the first of the list- didn't match its own ref among what was
                // just saved. Same as `SearchPlayback.playDituSeason`, it falls back to saving JUST
                // that episode before giving up.
                repo.addDituSource(
                    ref = chosen.ref,
                    seriesRef = rec.ref,
                    title = rec.titulo,
                    episode = chosen.number,
                    episodeTitle = chosen.title,
                    posterUrl = rec.posterUrl.ifBlank { series?.posterUrl.orEmpty() },
                    backdropUrl = series?.backdropUrl.orEmpty(),
                    season = chosen.season,
                    tmdbId = tmdbId,
                    tituloCanonico = rec.titulo.takeIf { tmdbId != null },
                )
            }
        } else {
            repo.addDituSource(
                ref = rec.ref,
                title = rec.titulo,
                posterUrl = rec.posterUrl,
                tmdbId = tmdbId,
                tituloCanonico = rec.titulo.takeIf { tmdbId != null },
            )
        }
        return episodeId?.let { RecommendationSaving.itemIdFor(target) }
    }

    /** A Caracol series' episodes, or null if they couldn't be listed. */
    private suspend fun chaptersOf(rec: RecommendationEntity) = try {
        gateway.episodesWithSeries(rec.ref)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "chapters from Caracol for \"${rec.titulo}\": ${e.javaClass.simpleName}: ${e.message}")
        null
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
