package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.SearchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/**
 * One installed plugin speaking the contract Magis and Caracol speak.
 *
 * Search results carry `source = "plugin:<id>"` and a wrapped ref ([PluginRef]); `resolve` and
 * `episodesWithSeries` unwrap it and hand the plugin its OWN ref. Everything the plugin returns
 * goes through [PluginOutput] first. Errors surface as [GatewayException] with the plugin's name,
 * the same channel the other sources use.
 */
class PluginContentSource(
    private val plugin: InstalledPlugin,
    private val caller: PluginCaller,
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) : ContentSource {
    private val id = plugin.manifest.id
    private val name = plugin.manifest.name
    private val source = PluginIds.sourceFor(id)
    private val caps = plugin.manifest.capabilities

    /**
     * `CompositeSource`'s limit is only a BACKSTOP: the plugin's own call limit
     * ([SEARCH_TIMEOUT_MS], passed to [PluginCaller.call]) must fire first, because only its
     * [PluginTimeoutException] counts toward "no responde" in `PluginRuntimePool`.
     *
     * The backstop's clock starts when the search is collected; the plugin's own starts only once
     * the pool's per-plugin mutex is taken. In the worst case the search waits there behind ONE
     * slow call of the same plugin (the longest other capability limit), then runs its full
     * [SEARCH_TIMEOUT_MS]. So the backstop is own limit + that queue wait + a grace for the runtime
     * load. With equal limits the backstop always won, the call was cancelled instead of timing
     * out, and a hanging search never counted.
     */
    override val searchTimeoutMs: Long? =
        SEARCH_TIMEOUT_MS + maxOf(HOME_TIMEOUT_MS, EPISODES_TIMEOUT_MS, RESOLVE_TIMEOUT_MS) + SEARCH_BACKSTOP_GRACE_MS

    override fun recognizes(ref: String): Boolean = ref.startsWith(PluginRef.prefixFor(id))

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> {
        if ("search" !in caps) return emptyFlow()
        return flow {
            val t0 = System.currentTimeMillis()
            emit(SearchEvent.SourceStart(source, label = name))
            val out = try {
                caller.call(id, "search", queryJson(ctx), SEARCH_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // DownSources shows "<plugin> no respondió: <error>". A timeout's own message names
                // the capability in English ("search no respondió en 15 s"): never shown.
                val error = if (e is PluginTimeoutException) "tardó más de ${SEARCH_TIMEOUT_MS / 1000} s" else e.message ?: "error del plugin"
                emit(SearchEvent.SourceError(source, error, System.currentTimeMillis() - t0, 0, cause = e))
                return@flow
            }
            val items = PluginOutput.items(out, allowSeries = "episodes" in caps) { log("[$id] $it") }
            items.forEach { emit(SearchEvent.ResultEvent(source, resultFrom(plugin, it))) }
            emit(SearchEvent.SourceDone(source, items.size, System.currentTimeMillis() - t0))
        }
    }

    override suspend fun resolve(ref: String): GatewayPlayable {
        val own = decodeOwn(ref)
        if (own.kind == PluginRef.SERIES) throw GatewayException("Elige un capítulo primero")
        val out = callOrThrow("resolve", JSONObject.quote(own.ref), RESOLVE_TIMEOUT_MS)
        val stream = try {
            PluginOutput.stream(out, plugin.record.hosts)
        } catch (e: PluginContractException) {
            throw GatewayException("$name: ${e.message}", e)
        }
        return GatewayPlayable(
            kind = "plugin",
            url = stream.url,
            headers = stream.headers,
            mime = stream.mime,
            subtitles = stream.subtitles.map { GatewaySubtitle(it.lang, it.url, it.format) },
            durationMs = stream.durationMs,
        )
    }

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
        val own = decodeOwn(ref)
        if (own.kind != PluginRef.SERIES || "episodes" !in caps) throw GatewayException("Esto no tiene capítulos")
        val out = callOrThrow("episodes", JSONObject.quote(own.ref), EPISODES_TIMEOUT_MS)
        val parsed = try {
            PluginOutput.episodes(out) { log("[$id] $it") }
        } catch (e: PluginContractException) {
            throw GatewayException("$name: ${e.message}", e)
        }
        val episodes = parsed.episodes.map { e ->
            GatewayEpisode(
                number = e.number,
                title = e.title.ifBlank { "Capítulo ${e.number}" },
                ref = PluginRef(id, own.itemId, PluginRef.EPISODE, e.ref, e.season, e.number).encode(),
                still = e.still.ifBlank { null },
                overview = e.overview.ifBlank { null },
                season = e.season,
            )
        }
        val series = parsed.series?.let { s ->
            GatewaySeries(
                imdbId = s.imdbId, tmdbId = s.tmdbId,
                seasonNumber = parsed.episodes.minOfOrNull { it.season } ?: 1,
                title = s.title, posterUrl = s.poster, backdropUrl = s.backdrop,
            )
        }
        return episodes to series
    }

    private suspend fun callOrThrow(function: String, argJson: String, timeoutMs: Long): String = try {
        caller.call(id, function, argJson, timeoutMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PluginTimeoutException) {
        throw GatewayException("$name no respondió a tiempo", e)
    } catch (e: Exception) {
        throw GatewayException("$name: ${e.message}", e)
    }

    private fun decodeOwn(ref: String): PluginRef =
        PluginRef.decode(ref)?.takeIf { it.pluginId == id } ?: throw GatewayException("Ese enlace no es de $name")

    companion object {
        const val SEARCH_TIMEOUT_MS = 15_000L

        /** Slack in [searchTimeoutMs] beyond own limit + queue wait: the runtime load. */
        const val SEARCH_BACKSTOP_GRACE_MS = 5_000L
        const val HOME_TIMEOUT_MS = 20_000L
        const val EPISODES_TIMEOUT_MS = 20_000L
        const val RESOLVE_TIMEOUT_MS = 20_000L

        /** `search(query)` argument: `{ q, type: "movie"|"series"|"any", season, episode, tmdbId, year }`. */
        fun queryJson(ctx: GatewaySearchQuery): String = JSONObject()
            .put("q", ctx.q)
            .put("type", when (ctx.type) { "movie" -> "movie"; "tv" -> "series"; else -> "any" })
            .put("season", ctx.season)
            .put("episode", ctx.episode)
            .put("tmdbId", ctx.tmdbId)
            .put("year", ctx.year)
            .toString()

        /** Shared by search and Home rows, so a title reaches `SearchPlayback` the same way from both. */
        fun resultFrom(plugin: InstalledPlugin, item: PluginItem): GatewayResult {
            val kind = if (item.kind == "series") PluginRef.SERIES else PluginRef.MOVIE
            return GatewayResult(
                source = PluginIds.sourceFor(plugin.manifest.id),
                title = item.title,
                ref = PluginRef(plugin.manifest.id, item.id, kind, item.ref).encode(),
                kind = item.kind,
                lang = item.lang,
                quality = item.quality,
                year = item.year,
                extra = mapOf(
                    "poster" to item.poster,
                    "backdrop" to item.backdrop,
                    "overview" to item.overview,
                    "pluginName" to plugin.manifest.name,
                    "color" to plugin.manifest.color.orEmpty(),
                    "pluginItemId" to item.id,
                ),
            )
        }
    }
}

/**
 * Catches every plugin ref no usable [PluginContentSource] claimed, i.e. one whose plugin is
 * disabled, unresponsive, damaged or uninstalled, and fails with the registry's message for it
 * ("Activa el plugin X…", "Esto venía del plugin X, que ya no está instalado") instead of
 * `CompositeSource`'s generic "No hay ninguna fuente que sepa abrir esto". Goes LAST in the list,
 * after the usable plugins' sources. Never searches.
 */
class UnusablePluginSource(private val access: PluginPlayback) : ContentSource {
    override fun recognizes(ref: String): Boolean = ref.startsWith("${PluginRef.PREFIX}:")

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()

    override suspend fun resolve(ref: String): GatewayPlayable = throw blocked(ref)

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> = throw blocked(ref)

    private fun blocked(ref: String): GatewayException {
        val pluginId = ref.removePrefix("${PluginRef.PREFIX}:").substringBefore(':', "").takeIf { it.isNotEmpty() }
        // Ready = it became usable after this call's source list was read: asking again works.
        return GatewayException(access.accessFor(pluginId).blockedMessage() ?: "El plugin no está listo, inténtalo de nuevo")
    }
}
