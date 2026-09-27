package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.catalog.TmdbInfo
import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.MAGIS_SERIES
import com.arkiv.player.data.local.DownloadDisplayState
import com.arkiv.player.ui.formatRuntime
import com.arkiv.player.ui.plainSynopsis
import java.util.Locale

enum class TitleKind { MOVIE, SERIES }

/**
 * What the info page draws. Every field but [title] and [kind] is optional: the page paints only
 * what exists, so it looks right with just the portal's data and gets better when TMDB adds more.
 *
 * Deliberately NOT `GatewayResult`: that one still carries torrent-era fields (`seeders`,
 * `sizeBytes`) and no synopsis, genres or backdrop. Chapters do reuse `GatewayEpisode` and
 * `GatewaySeries`, which are already source-neutral.
 */
data class TitleInfo(
    val title: String,
    val kind: TitleKind,
    val poster: String? = null,
    val backdrop: String? = null,
    val synopsis: String = "",
    val genres: List<String> = emptyList(),
    val year: String = "",
    val score: Double? = null,
    val runtimeMinutes: Int = 0,
    val episodeCount: Int = 0,
    val seasonNumber: Int? = null,
    /** What only TMDB knows (see [withTmdb]); empty until it answers, or when it cannot be matched. */
    val tagline: String = "",
    /** A movie's directors, or a series' creators. */
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    /** Age rating ("12+", "TV-MA"). */
    val certification: String = "",
)

/**
 * Adds what TMDB knows about this title. The portal's data stays where it exists: its synopsis, score
 * and a movie's runtime are what the card already showed, and TMDB fills only the blanks. TMDB's
 * genres do replace the portal's, which are the English tags its rows classify by. A series' runtime
 * is per episode, so it is not the title's and is left out.
 */
fun TitleInfo.withTmdb(t: TmdbInfo): TitleInfo = copy(
    synopsis = synopsis.ifBlank { plainSynopsis(t.overview) },
    year = t.year.ifBlank { year },
    genres = t.genres.ifEmpty { genres },
    runtimeMinutes = if (kind == TitleKind.MOVIE && runtimeMinutes <= 0) t.runtimeMinutes else runtimeMinutes,
    score = score ?: t.voteAverage,
    tagline = t.tagline,
    directors = t.directors,
    cast = t.cast,
    certification = t.certification,
)

/**
 * The card the person tapped, as the page's first (instant) paint. Only a movie's duration is the
 * title's runtime: a series card's is per episode (as with TMDB's, see [withTmdb]).
 */
fun CatalogItem.toTitleInfo(): TitleInfo {
    val kind = if (type in MAGIS_SERIES) TitleKind.SERIES else TitleKind.MOVIE
    return TitleInfo(
        title = title.ifBlank { id },
        kind = kind,
        poster = poster?.takeIf { it.isNotBlank() },
        backdrop = backdrop?.takeIf { it.isNotBlank() },
        synopsis = plainSynopsis(description),
        genres = genres.filter { it.isNotBlank() },
        score = score,
        runtimeMinutes = if (kind == TitleKind.MOVIE) durationS / 60 else 0,
        episodeCount = episodeCount,
    )
}

/** "★ 7.9  ·  2026  ·  1 h 43 min  ·  12+", leaving out whatever is not known. */
fun TitleInfo.metaLine(): String = listOfNotNull(
    score?.let { String.format(Locale.US, "★ %.1f", it) },
    year.takeIf { it.isNotBlank() },
    runtimeMinutes.takeIf { it > 0 }?.let { formatRuntime(it * 60.0) },
    certification.takeIf { it.isNotBlank() },
).joinToString("  ·  ")

/**
 * The lines under the synopsis: who directed (or created) it and the first [maxCast] of the cast.
 * Empty when TMDB gave neither.
 */
fun TitleInfo.creditLines(maxCast: Int = 5): List<String> = listOfNotNull(
    directors.takeIf { it.isNotEmpty() }
        ?.let { (if (kind == TitleKind.SERIES) "Creada por" else "Dirección") + ": " + it.joinToString(", ") },
    cast.takeIf { it.isNotEmpty() }?.let { "Reparto: " + it.take(maxCast).joinToString(", ") },
)

/** The small line above a TV title: "Película", "Serie" or "Serie · 12 episodios". */
fun TitleInfo.kindLine(): String = when (kind) {
    TitleKind.MOVIE -> "Película"
    TitleKind.SERIES -> listOfNotNull("Serie", episodesWord(episodeCount)).joinToString("  ·  ")
}

/** "Temporada 1 · 12 episodios", or whatever part of that is known. */
fun seasonHeader(number: Int?, episodeCount: Int): String = listOfNotNull(
    number?.takeIf { it > 0 }?.let { "Temporada $it" },
    episodesWord(episodeCount),
).joinToString("  ·  ")

private fun episodesWord(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 episodio"
    else -> "$count episodios"
}

/** "T1 · E3", or "E3" when the season is not known. */
fun chapterNumberLabel(season: Int?, number: Int): String =
    if (season != null && season > 0) "T$season · E$number" else "E$number"

/**
 * A chapter's season for identity: the source's own, else 1. Season 0 (specials) counts as 1, the
 * rule `PluginEntities.chapterId` applies when it saves, so the page and the library agree.
 */
val GatewayEpisode.seasonOrOne: Int get() = (season ?: 1).coerceAtLeast(1)

/**
 * The key of the chapter numbered [number] in [season] in the page's lists: unique across seasons
 * even when a source repeats numbers. One format for a chapter ([listKey]) and for the main button's
 * target ([PrimaryAction.listKey]), which the TV focus guard compares.
 */
fun chapterListKey(season: Int, number: Int): String = "$season-$number"

/** The chapter's key in the page's lists (see [chapterListKey]). */
val GatewayEpisode.listKey: String get() = chapterListKey(seasonOrOne, number)

/** The season to print next to this chapter: its own when the source gave one, else [fallback]. */
fun GatewayEpisode.labelSeason(fallback: Int?): Int? = season?.let { seasonOrOne } ?: fallback

/** The chapter's own name: TMDB's when there is one, else the portal's, else null. */
fun chapterName(chapter: GatewayEpisode): String? =
    chapter.tmdbTitle?.takeIf { it.isNotBlank() } ?: chapter.title.takeIf { it.isNotBlank() }

/** "4. El ataque", or "Episodio 4" when the chapter has no name. */
fun chapterLine(chapter: GatewayEpisode): String =
    chapterName(chapter)?.let { "${chapter.number}. $it" } ?: "Episodio ${chapter.number}"

/** The text on the movie's download button. */
fun downloadLabel(state: DownloadDisplayState): String = when (state) {
    DownloadDisplayState.NotDownloaded -> "Descargar"
    DownloadDisplayState.Queued -> "En cola"
    is DownloadDisplayState.Downloading ->
        state.fraction?.let { "Descargando ${(it * 100).toInt()} %" } ?: "Descargando"
    DownloadDisplayState.Done -> "Descargada"
    is DownloadDisplayState.Failed -> "Falló la descarga"
    DownloadDisplayState.NeedsConfirmation -> "En espera"
}
