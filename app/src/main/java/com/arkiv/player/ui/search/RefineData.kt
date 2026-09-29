package com.arkiv.player.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeShow
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbDetail
import com.arkiv.player.data.catalog.TmdbEpisode
import com.arkiv.player.data.catalog.TmdbSeason
import kotlinx.coroutines.delay

/**
 * What the REFINE step of a series or anime search shows, on the phone and on the TV: the seasons, the chosen season's
 * chapters, and how many episodes an anime has. The two screens draw it differently and load it the same way, which is
 * [rememberRefineData].
 */
internal class RefineData(
    val seasons: List<TmdbSeason>,
    /** The series' TMDB detail arrived (its seasons may still be none). False while it loads or if it failed. */
    val detailLoaded: Boolean,
    val selectedSeason: Int?,
    val selectSeason: (Int) -> Unit,
    val currentEpisodes: List<TmdbEpisode>,
    val loadingEpisodes: Boolean,
    val animeTotal: Int,
    /** The anime's detail arrived. */
    val animeLoaded: Boolean,
)

/** The season shown first: the first real one, skipping the specials (season 0), or the specials when that is all there is. */
internal fun defaultRefineSeason(seasons: List<TmdbSeason>): Int? =
    seasons.firstOrNull { it.seasonNumber >= 1 }?.seasonNumber ?: seasons.firstOrNull()?.seasonNumber

internal fun refineSeasonLabel(seasonNumber: Int): String = if (seasonNumber == 0) "Especiales" else "T$seasonNumber"

internal fun refineEpisodeLabel(episode: TmdbEpisode): String = "E${episode.episode} · ${episode.name}"

/**
 * Loads what REFINE needs for [card]. Reuses [vmDetail] / [vmAnimeShow] when the ViewModel already has them (coming back
 * from RESULTS); otherwise asks TMDB / AniList right here, so the selector never waits for the source search. A season's
 * chapters are fetched when it is chosen and kept, so going back and forth between seen seasons costs nothing.
 *
 * [seasonDebounceMs] waits before fetching a chosen season: on the TV the season follows FOCUS, so scrubbing the chips
 * with the D-pad would otherwise fetch once per chip (the phone chooses by tap and passes 0).
 */
@Composable
internal fun rememberRefineData(
    card: TitleCard,
    vmDetail: TmdbDetail?,
    vmAnimeShow: AnimeShow?,
    tmdbApi: TmdbApi,
    aniListApi: AniListApi,
    seasonDebounceMs: Long,
): RefineData {
    var localDetail by remember(card) { mutableStateOf<TmdbDetail?>(null) }
    var localAnimeShow by remember(card) { mutableStateOf<AnimeShow?>(null) }

    val effectiveDetail = vmDetail?.takeIf { it.id == card.tmdbId } ?: localDetail
    val effectiveAnimeShow = vmAnimeShow?.takeIf { it.id == card.anilistId } ?: localAnimeShow

    // Only requests what the VM doesn't already have (avoids a refetch on returning from RESULTS with back()).
    LaunchedEffect(card.tmdbId, vmDetail) {
        val tmdbId = card.tmdbId ?: return@LaunchedEffect
        if (card.kind != "series") return@LaunchedEffect
        if (vmDetail?.id == tmdbId) return@LaunchedEffect
        localDetail = runCatching { tmdbApi.detail("tv", tmdbId) }.getOrNull()
    }
    LaunchedEffect(card.anilistId, vmAnimeShow) {
        val anilistId = card.anilistId ?: return@LaunchedEffect
        if (card.kind != "anime") return@LaunchedEffect
        if (vmAnimeShow?.id == anilistId) return@LaunchedEffect
        localAnimeShow = runCatching { aniListApi.details(anilistId) }.getOrNull()
    }

    var selectedSeason by remember(card) { mutableStateOf<Int?>(null) }
    var episodesBySeason by remember(card) { mutableStateOf<Map<Int, List<TmdbEpisode>>>(emptyMap()) }
    var loadingEpisodes by remember(card) { mutableStateOf(false) }

    // Preselects the first "real" season as soon as they're known.
    LaunchedEffect(effectiveDetail) {
        if (selectedSeason == null) selectedSeason = defaultRefineSeason(effectiveDetail?.seasons.orEmpty())
    }

    // Loads the chosen season's chapters, cached per season.
    //  - the wait comes BEFORE touching `loadingEpisodes` or the cache: if the person keeps scrubbing, every restart
    //    cancels the previous coroutine during the wait and it never reaches the TMDB fetch;
    //  - `loadingEpisodes` is only set AFTER the cache check, and the fetch sits in a try/finally: a season already
    //    cached never touches the flag, and a fetch cancelled halfway (another season chosen) still clears it, so it
    //    never sticks on "Cargando…".
    LaunchedEffect(selectedSeason, card.tmdbId) {
        val season = selectedSeason ?: return@LaunchedEffect
        val tmdbId = card.tmdbId ?: return@LaunchedEffect
        if (seasonDebounceMs > 0) delay(seasonDebounceMs)
        if (episodesBySeason.containsKey(season)) return@LaunchedEffect
        loadingEpisodes = true
        try {
            // `.orEmpty()`: a network failure looks the same as an empty season and gets retried by entering again.
            val eps = runCatching { tmdbApi.seasonEpisodes(tmdbId, season) }.getOrNull().orEmpty()
            episodesBySeason = episodesBySeason + (season to eps)
        } finally {
            loadingEpisodes = false
        }
    }

    return RefineData(
        seasons = effectiveDetail?.seasons.orEmpty(),
        detailLoaded = effectiveDetail != null,
        selectedSeason = selectedSeason,
        selectSeason = { selectedSeason = it },
        currentEpisodes = selectedSeason?.let { episodesBySeason[it] }.orEmpty(),
        loadingEpisodes = loadingEpisodes,
        animeTotal = effectiveAnimeShow?.episodes ?: 0,
        animeLoaded = effectiveAnimeShow != null,
    )
}
