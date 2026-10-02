package com.arkiv.player.data.subtitles

/**
 * What the library knows about the title on screen, for an online subtitle search: its kind
 * (`"movie"`/`"tv"`), the ids it carries (a plugin's `ids.tmdb`, an IMDb id inside the item id) and
 * its name. A Xuper title often carries no id at all, only [title] (maybe with "(year)").
 */
data class SubtitleSubject(
    val kind: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val title: String = "",
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isEpisode: Boolean get() = kind == "tv"

    /** The work's identity, episode aside: what [SubtitleIdResolver] caches. */
    val workKey: String get() = "$kind|${tmdbId ?: 0}|${imdbId.orEmpty()}|${title.trim().lowercase()}|${year ?: 0}"
}

/**
 * Turns a [SubtitleSubject] into a [SubtitleQuery] with the strongest ids it can find, cheapest
 * first: the ids the title already carries; with a TMDB id but no IMDb one, TMDB's `external_ids`
 * (both catalogs index by IMDb best); with neither, a TMDB search by title (and year) for the id,
 * then its IMDb. When nothing is found the query keeps the title, which both catalogs also search
 * by. Each work's answer is cached for the process (a series' episodes share one), failures
 * included, so reopening the menu costs no TMDB call.
 */
class SubtitleIdResolver(
    /** TMDB's IMDb id for (`"movie"`/`"tv"`, tmdbId), or null. */
    private val imdbOf: suspend (String, Int) -> String?,
    /** TMDB's id for a (`"movie"`/`"tv"`, title, year) search, or null. */
    private val searchTmdb: suspend (String, String, Int?) -> Int?,
) {
    private data class Ids(val tmdb: Int?, val imdb: String?)

    private val cache = object : LinkedHashMap<String, Ids>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ids>?) = size > MAX_CACHED
    }

    suspend fun resolve(subject: SubtitleSubject, languages: List<String>): SubtitleQuery {
        val ids = synchronized(cache) { cache[subject.workKey] } ?: lookUp(subject).also { synchronized(cache) { cache[subject.workKey] = it } }
        return SubtitleQuery(
            isEpisode = subject.isEpisode,
            imdbId = ids.imdb,
            tmdbId = ids.tmdb,
            title = OnlineSubtitleRules.bareTitle(subject.title),
            year = subject.year ?: OnlineSubtitleRules.yearIn(subject.title),
            season = subject.season,
            episode = subject.episode,
            languages = languages,
        )
    }

    private suspend fun lookUp(s: SubtitleSubject): Ids {
        val type = if (s.isEpisode) "tv" else "movie"
        var tmdb = s.tmdbId?.takeIf { it > 0 }
        var imdb = s.imdbId?.takeIf { OnlineSubtitleRules.imdbIn(it) == it }
        if (imdb != null && tmdb != null) return Ids(tmdb, imdb)
        if (tmdb == null && imdb == null) {
            val title = OnlineSubtitleRules.bareTitle(s.title)
            if (title.isNotEmpty()) tmdb = safe { searchTmdb(type, title, s.year ?: OnlineSubtitleRules.yearIn(s.title)) }?.takeIf { it > 0 }
        }
        if (imdb == null && tmdb != null) imdb = safe { imdbOf(type, tmdb) }?.let { OnlineSubtitleRules.imdbIn(it) }
        return Ids(tmdb, imdb)
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_CACHED = 64
    }
}
