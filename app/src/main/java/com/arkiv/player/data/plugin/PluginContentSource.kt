package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPage
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person must configure [pluginId] before its titles open: the screen offers a button to its
 * Configurar screen. [message] is already the Spanish sentence ("Configura X en Ajustes ▸ Plugins").
 */
class PluginSetupRequiredException(val pluginId: String, message: String) : RuntimeException(message)

/**
 * One installed plugin speaking the contract Magis and Caracol speak.
 *
 * Search results carry `source = "plugin:<id>"` and a wrapped ref ([PluginRef]); `resolve` and
 * `episodesWithSeries` unwrap it and hand the plugin its OWN ref. [browse] and [searchPage] take
 * the plugin's own refs and cursors: they are never saved, only paged through. Everything the
 * plugin returns goes through [PluginOutput] first, gated to [hosts] (declared + the servers typed
 * in its settings). Errors surface as [GatewayException] with the plugin's name, the same channel
 * the other sources use; a typed error ([PluginErrorException]) is worded by [PluginErrors], with
 * `geo_blocked` as a [GatewayBlockedException] (the player's blocked-content dialog) and
 * `auth_required` as [PluginSetupRequiredException].
 */
class PluginContentSource(
    private val plugin: InstalledPlugin,
    private val caller: PluginCaller,
    // The plugin's effective hosts (declared ∪ typed servers): a declared-only default would
    // silently drop the person's own server for any caller relying on it.
    private val hosts: EffectiveHosts = plugin.hosts,
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) : ContentSource {
    private val id = plugin.manifest.id
    private val name = plugin.manifest.name
    private val source = PluginIds.sourceFor(id)
    private val caps = plugin.manifest.capabilities
    private val allowSeries = "episodes" in caps
    private val allowNext = "browse" in caps

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
        SEARCH_TIMEOUT_MS + maxOf(HOME_TIMEOUT_MS, BROWSE_TIMEOUT_MS, EPISODES_TIMEOUT_MS, RESOLVE_TIMEOUT_MS) + SEARCH_BACKSTOP_GRACE_MS

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
                // DownSources shows "<plugin> no respondió: <error>" (or the typed error's own
                // sentence). A timeout's own message names the capability in English: never shown.
                // Its own seconds, not the search limit: the pool's LOAD timeout (10 s) lands here too.
                val error = when (e) {
                    is PluginTimeoutException -> "tardó más de ${e.seconds} s"
                    is PluginErrorException -> PluginErrors.userMessage(e.code, name) ?: e.message ?: "error del plugin"
                    else -> e.message ?: "error del plugin"
                }
                emit(SearchEvent.SourceError(source, error, System.currentTimeMillis() - t0, 0, cause = e))
                return@flow
            }
            val page = PluginOutput.page(out, PluginOutput.MAX_SEARCH_ITEMS, allowSeries, allowNext, hosts) { log("[$id] $it") }
            page.items.forEach { emit(SearchEvent.ResultEvent(source, resultFrom(plugin, it))) }
            emit(SearchEvent.SourceDone(source, page.items.size, System.currentTimeMillis() - t0, more = page.next))
        }
    }

    /** "Ver más" on this plugin's search results: the same query, from [cursor] on. */
    suspend fun searchPage(queryJson: String, cursor: String): GatewayPage {
        val arg = runCatching { JSONObject(queryJson) }.getOrElse { JSONObject() }.put("cursor", cursor.take(PluginOutput.MAX_CURSOR_CHARS))
        val out = callOrThrow("search", arg.toString(), SEARCH_TIMEOUT_MS)
        return pageOf(PluginOutput.page(out, PluginOutput.MAX_SEARCH_ITEMS, allowSeries, allowNext, hosts) { log("[$id] $it") })
    }

    override suspend fun browse(ref: String, cursor: String?): GatewayPage {
        if (!allowNext) throw GatewayException("$name no tiene más para mostrar")
        val arg = JSONObject().put("ref", ref.take(PluginOutput.MAX_REF_CHARS)).put("cursor", cursor?.take(PluginOutput.MAX_CURSOR_CHARS) ?: JSONObject.NULL)
        val out = callOrThrow("browse", arg.toString(), BROWSE_TIMEOUT_MS)
        return pageOf(PluginOutput.page(out, PluginOutput.MAX_BROWSE_ITEMS, allowSeries, allowNext = true, hosts) { log("[$id] $it") })
    }

    private fun pageOf(page: PluginPage) = GatewayPage(page.items.map { resultFrom(plugin, it) }, page.next)

    override suspend fun resolve(ref: String): GatewayPlayable {
        val own = decodeOwn(ref)
        if (own.kind == PluginRef.SERIES) throw GatewayException("Elige un capítulo primero")
        val out = callOrThrow("resolve", JSONObject.quote(own.ref), RESOLVE_TIMEOUT_MS)
        val stream = try {
            PluginOutput.stream(out, hosts)
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
            expiresInSeconds = stream.expiresInSeconds,
        )
    }

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
        val own = decodeOwn(ref)
        if (own.kind != PluginRef.SERIES || "episodes" !in caps) throw GatewayException("Esto no tiene capítulos")
        val out = callOrThrow("episodes", JSONObject.quote(own.ref), EPISODES_TIMEOUT_MS)
        val parsed = try {
            PluginOutput.episodes(out, { log("[$id] $it") }, hosts)
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
    } catch (e: PluginErrorException) {
        throw typed(e)
    } catch (e: Exception) {
        throw GatewayException("$name: ${e.message}", e)
    }

    /** A typed error as the screens expect it; an unknown code keeps the generic "<name>: <message>". */
    private fun typed(e: PluginErrorException): RuntimeException {
        val message = PluginErrors.userMessage(e.code, name) ?: return GatewayException("$name: ${e.message}", e)
        return when (e.code) {
            PluginErrors.AUTH_REQUIRED -> PluginSetupRequiredException(id, message)
            PluginErrors.GEO_BLOCKED -> GatewayBlockedException(message)
            else -> GatewayException(message, e)
        }
    }

    private fun decodeOwn(ref: String): PluginRef =
        PluginRef.decode(ref)?.takeIf { it.pluginId == id } ?: throw GatewayException("Ese enlace no es de $name")

    companion object {
        const val SEARCH_TIMEOUT_MS = 15_000L

        /** Slack in [searchTimeoutMs] beyond own limit + queue wait: the runtime load. */
        const val SEARCH_BACKSTOP_GRACE_MS = 5_000L
        const val HOME_TIMEOUT_MS = 20_000L
        const val BROWSE_TIMEOUT_MS = 20_000L
        const val EPISODES_TIMEOUT_MS = 20_000L
        const val RESOLVE_TIMEOUT_MS = 20_000L

        const val MAX_ALT_TITLES = 5
        const val MAX_ALT_TITLE_CHARS = 200

        /**
         * `search(query)` argument: `{ q, type: "movie"|"series"|"any", season, episode, tmdbId,
         * year, originalTitle, altTitles, cursor }` — `cursor` null on the first page.
         */
        fun queryJson(ctx: GatewaySearchQuery, cursor: String? = null): String = JSONObject()
            .put("q", ctx.q)
            .put("type", when (ctx.type) { "movie" -> "movie"; "tv" -> "series"; else -> "any" })
            .put("season", ctx.season)
            .put("episode", ctx.episode)
            .put("tmdbId", ctx.tmdbId)
            .put("year", ctx.year)
            .put("originalTitle", ctx.originalTitle.take(MAX_ALT_TITLE_CHARS))
            .put("altTitles", JSONArray(ctx.altTitles.filter { it.isNotBlank() }.map { it.take(MAX_ALT_TITLE_CHARS) }.distinct().take(MAX_ALT_TITLES)))
            .put("cursor", cursor ?: JSONObject.NULL)
            .toString()

        /** Shared by search, Home rows and "Ver más", so a title reaches `SearchPlayback` the same way from all. */
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
                extra = buildMap {
                    put("poster", item.poster)
                    put("backdrop", item.backdrop)
                    put("overview", item.overview)
                    put("pluginName", plugin.manifest.name)
                    put("color", plugin.manifest.color.orEmpty())
                    put("pluginItemId", item.id)
                    if (item.originalTitle.isNotEmpty()) put("originalTitle", item.originalTitle)
                    if (item.genres.isNotEmpty()) put("genres", item.genres.joinToString(", "))
                    item.rating?.let { put("rating", "%.1f".format(java.util.Locale.ROOT, it)) }
                    if (item.runtimeMinutes > 0) put("runtimeMinutes", item.runtimeMinutes.toString())
                    if (item.tmdbId > 0) put("tmdbId", item.tmdbId.toString())
                    if (item.imdbId.isNotEmpty()) put("imdbId", item.imdbId)
                    if (item.badges.isNotEmpty()) put("badges", item.badges.joinToString("|"))
                },
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
