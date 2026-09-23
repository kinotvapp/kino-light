package com.arkiv.player.data.recommendations

import com.arkiv.player.data.SeasonChapter
import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewaySeries

/**
 * The season to save from a recommendation, already in the model `ArkivRepository.addMagisSeason`
 * understands.
 *
 * [tmdbId] and [seasonNumber] are null when the gateway couldn't match the series against TMDB,
 * and it doesn't stop being saved because of that: `MagisEntities.buildSeason` preserves them
 * against whatever was already in the database instead of overwriting it.
 */
data class RecommendationSeason(
    val chapters: List<SeasonChapter>,
    val tmdbId: Int?,
    val seasonNumber: Int?,
)

/** Which source a recommendation lands in when saving it, and with what contentId. */
internal sealed interface RecommendationTarget {
    val contentId: String
    data class Magis(override val contentId: String) : RecommendationTarget
    data class Caracol(override val contentId: String) : RecommendationTarget
}

/**
 * What to save when adding a "For you" row's card to the library. Pure/JVM (tested without Room
 * or network); the caller supplies the network and the write, see [RecommendationAggregator].
 *
 * First decides which SOURCE the recommendation is from ([targetFor], [targetForRef], [itemIdFor]:
 * Magis or Caracol, by its `ref`) and then, for Magis, how to save it:
 *
 * **A series recommendation is a SEASON, not an episode.** A Magis series' `ref` points at the
 * whole season (it carries `episode: 0` inside), so saving it as-is with `addMagisSource` left the
 * item with a single episode and marked as a movie — which is exactly how "My Hero Academia" came
 * in, with 1 of its 13 episodes. The episodes have to be requested from the portal
 * (`MagisCatalog.detail`) and saved with `addMagisSeason`, same as the season dialog's "Save"
 * button (`SearchPlayback.magisEpisodeIdFor`). Caracol resolves its own path in
 * `RecommendationAggregator.addFromCaracol`.
 */
object RecommendationSaving {

    /**
     * Whether this recommendation needs its episode list requested before saving it.
     *
     * Decided by [RecommendationEntity.tipo] —already matched against TMDB— and only applies to
     * Magis: Caracol decides by its own `ref` (`DituRef.isSeries`, see
     * `RecommendationAggregator.addFromCaracol`). Asking for a movie would pay a portal listing
     * call for nothing.
     */
    fun needsChapters(rec: RecommendationEntity): Boolean = rec.tipo == "tv"

    /**
     * Which source this `ref` is from, or null if it's from none known. Caracol is asked first,
     * but the order doesn't matter: `DituRef.decode` and `MagisRef.decode` only accept
     * their own (their prefix, or an old gateway ref with its own source inside).
     */
    internal fun targetForRef(ref: String): RecommendationTarget? {
        com.arkiv.player.data.ditu.DituRef.decode(ref)?.let { return RecommendationTarget.Caracol(it.contentId) }
        com.arkiv.player.data.magis.MagisRef.decode(ref)?.let { return RecommendationTarget.Magis(it.contentId) }
        return null
    }

    /**
     * Which source [rec] is from. A Caracol ref can NEVER be saved as Magis (the player would send
     * it to `loadMagis`). An old row with a ref that isn't understood falls back to Magis with its
     * id, which is how it used to be saved.
     */
    internal fun targetFor(rec: RecommendationEntity): RecommendationTarget =
        targetForRef(rec.ref) ?: RecommendationTarget.Magis(rec.id)

    /** The id of the item left in the library: the same one the search for that source builds. */
    internal fun itemIdFor(target: RecommendationTarget): String = when (target) {
        is RecommendationTarget.Magis -> com.arkiv.player.data.MagisEntities.itemIdFor(target.contentId)
        is RecommendationTarget.Caracol -> com.arkiv.player.data.DituEntities.itemIdFor(target.contentId)
    }

    /**
     * The season to save, or **null** if there is none and the caller has to fall back to the
     * usual standalone save.
     *
     * Null for an empty list, not a season with zero chapters: the Magis portal listing can come
     * back empty, and `addMagisSeason` with an empty list writes nothing, so without this null the
     * card would stay unsaved and never open its detail. Caracol refs never get here: `targetFor()`
     * sends them to `RecommendationAggregator.addFromCaracol`.
     *
     * A [GatewaySeries.tmdbId] of 0 is "didn't come" and not an identification: it comes from an
     * `optInt`, and that 0 would beat the `?:` `buildSeason` uses to preserve an already-saved tmdbId.
     */
    fun seasonFor(
        chapters: List<GatewayEpisode>,
        series: GatewaySeries?,
    ): RecommendationSeason? {
        if (chapters.isEmpty()) return null
        return RecommendationSeason(
            chapters = chapters.map {
                SeasonChapter(
                    number = it.number,
                    title = it.title,
                    ref = it.ref,
                    still = it.still,
                    tmdbTitle = it.tmdbTitle,
                    overview = it.overview,
                )
            },
            tmdbId = series?.tmdbId?.takeIf { it > 0 },
            seasonNumber = series?.seasonNumber,
        )
    }
}
