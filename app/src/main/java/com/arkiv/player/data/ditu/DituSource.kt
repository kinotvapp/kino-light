package com.arkiv.player.data.ditu

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.pickTmdbMatch
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Caracol Streaming speaking the same contract Magis already speaks.
 *
 * It's `DituAdapter`'s port (`arkiv-api/src/arkiv_api/adapters/ditu/adapter.py`): what the gateway
 * used to do between Caracol's API and the app —building results, flattening seasons, crossing
 * with TMDB— lives here.
 *
 * TMDB is used ONLY for the `tmdbId` and the canonical title, and only when the title matches (see
 * [episodesWithSeries]). The images are Caracol's, which always has them; TMDB's only come in if
 * Caracol didn't bring any.
 */
internal class DituSource(
    private val catalog: DituCatalog,
    private val episodes: DituEpisodes,
    private val resolver: DituResolve,
    private val tmdb: TmdbApi? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ContentSource {

    override fun recognizes(ref: String): Boolean = DituRef.decode(ref) != null

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = flow {
        val t0 = nowMs()
        emit(SearchEvent.SourceStart(SOURCE))
        val items = runCatching { catalog.search(ctx.q) }.getOrElse { e ->
            emit(SearchEvent.SourceError(SOURCE, e.message ?: "error de Caracol", nowMs() - t0, 0, cause = e))
            emit(SearchEvent.Done(nowMs() - t0))
            return@flow
        }
        for (item in items) {
            emit(SearchEvent.ResultEvent(SOURCE, resultFrom(item)))
        }
        emit(SearchEvent.SourceDone(SOURCE, items.size, nowMs() - t0))
        emit(SearchEvent.Done(nowMs() - t0))
    }.flowOn(Dispatchers.IO)

    override suspend fun resolve(ref: String): GatewayPlayable {
        val ownRef = DituRef.decode(ref) ?: throw GatewayException("Ese enlace no es de Caracol")
        return runCatching { resolver.vod(ownRef) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo reproducir en Caracol", it) }
    }

    /** Resolve a live channel. Doesn't go through [resolve] because a channel has no `ref`: what
     *  identifies it is the (channelId, assetId) pair that came with the list. */
    suspend fun resolveChannel(channel: DituChannel): GatewayPlayable =
        runCatching { resolver.live(channel) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo abrir el canal", it) }

    suspend fun channels(): List<DituChannel> =
        runCatching { catalog.channels() }
            .getOrElse { throw GatewayException(it.message ?: "No se pudieron listar los canales", it) }

    /**
     * The whole catalog, cached for [CATALOG_TTL_MS]: it's some 330 titles in a single call (see
     * [DituCatalog]), and requesting them every time the section is opened is time given away for free.
     *
     * [force] skips the cache: it's the reload button, for when Caracol adds something and there's
     * no wanting to wait for it to expire. If the call fails, the cache is left as it was.
     */
    suspend fun fullCatalog(force: Boolean = false): List<DituItem> {
        val cached = cachedCatalog
        if (!force && cached != null && nowMs() - cached.fetchedAtMs < CATALOG_TTL_MS) {
            return cached.titles
        }
        val titles = runCatching { catalog.catalog() }
            .getOrElse { throw GatewayException(it.message ?: "No se pudo cargar el catálogo", it) }
        cachedCatalog = CachedCatalog(titles, nowMs())
        return titles
    }

    /** A single value, not a cache by key: Caracol's catalog is one. */
    private class CachedCatalog(val titles: List<DituItem>, val fetchedAtMs: Long)

    @Volatile
    private var cachedCatalog: CachedCatalog? = null

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
        val ownRef = DituRef.decode(ref) ?: throw GatewayException("Ese enlace no es de Caracol")
        val season = runCatching { episodes.forRef(ownRef) }
            .getOrElse { throw GatewayException(it.message ?: "No se pudieron leer los capítulos", it) }

        val eps = season.episodes.map {
            // The season travels per chapter: in a group, `serie`'s is a single one for all of them.
            GatewayEpisode(number = it.number, title = it.title, ref = it.ref(), season = it.season)
        }
        if (season.seriesTitle.isBlank() && season.posterUrl.isBlank()) return eps to null

        var series = GatewaySeries(
            imdbId = "",
            tmdbId = 0,
            seasonNumber = season.season,
            title = season.seriesTitle,
            posterUrl = season.posterUrl,
            backdropUrl = season.backdropUrl,
        )
        // TMDB only contributes identity, not images: Caracol's are the right ones for its own
        // catalog. Failing here can't cost the chapters, which are already ready.
        //
        // `val tmdb = tmdb` (shadow the property in a local): the original brief called
        // `tmdb.search(...)` inside `runCatching`'s lambda trusting the `tmdb != null` smart-cast
        // above, and it doesn't compile — Kotlin doesn't apply a smart-cast to a class property
        // captured inside a lambda, only to local variables.
        val tmdb = tmdb
        if (tmdb != null && season.seriesTitle.isNotBlank()) {
            // Only an EXACT hit ([pickTmdbMatch]: normalized title, against TMDB's Spanish one or
            // the original). The first text result can be another series, and the `tmdbId` is the
            // key `LibraryGrouping` groups series by (`tv:<tmdbId>`): a wrong hit would rename this
            // series in the library or merge it with the other one. With no match the series is
            // left with no id and Caracol's title. A false negative costs little: the images are
            // already Caracol's.
            val hit = runCatching { tmdb.search("tv", season.seriesTitle) }.getOrNull()
                ?.let { pickTmdbMatch(season.seriesTitle, it) }
                ?.takeIf { it.exact }
                ?.item
            if (hit != null) {
                series = series.copy(
                    tmdbId = hit.id,
                    title = hit.title.ifBlank { series.title },
                    posterUrl = series.posterUrl.ifBlank { hit.posterUrl },
                    backdropUrl = series.backdropUrl.ifBlank { hit.backdropUrl },
                )
            }
        }
        return eps to series
    }

    internal companion object {
        const val SOURCE = "ditu"

        /**
         * A Caracol title as the rest of the app sees it. Used by search ([search]) and the TV's
         * Caracol section (`TvCaracolScreen`): so a title opened from either of the two arrives the
         * same way at `SearchPlayback` and the chapter list.
         */
        fun resultFrom(item: DituItem) = GatewayResult(
            source = SOURCE,
            title = item.title,
            ref = item.ref(),
            kind = if (item.isMovie) "movie" else "series",
            year = item.year,
            extra = mapOf("poster" to item.posterUrl, "content_type" to item.contentType),
        )

        /** How long [fullCatalog]'s cache is worth: 6h. */
        private const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
