package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayAudioTrack
import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.PluginBlockedException
import com.arkiv.player.data.gateway.GatewayPage
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.GatewaySubtitle
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.gateway.SeasonRef
import com.arkiv.player.data.gateway.SeriesListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person must configure [pluginId] before its titles open: the screen offers a button to its
 * Configurar screen. [message] is already the Spanish sentence ("Configura X en Menú ▸ Plugins", see [PluginsPlace]).
 */
class PluginSetupRequiredException(val pluginId: String, message: String) : RuntimeException(message)

/**
 * One installed plugin speaking the contract Magis and Caracol speak.
 *
 * Search results carry `source = "plugin:<id>"` and a wrapped ref ([PluginRef]); `resolve` and
 * `episodesWithSeries` unwrap it and hand the plugin its OWN ref. [browse] and [searchPage] take
 * the plugin's own refs and cursors: they are never saved, only paged through. Everything the
 * plugin returns goes through [PluginOutput] first, gated to [currentHosts] (declared + the servers typed
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
    /**
     * `AppGraph`'s one [XuperStreams], handed to every plugin's source alike: only the plugin
     * [XuperPrivilege.grants] ever reads it (see [xuper]), so passing it to the others grants nothing.
     */
    xuperStreams: XuperStreams? = null,
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
    /**
     * The hosts to check a call's output against, read AFTER the call returns (never cached): a
     * host the person approved in the middle of that very call (reactive approval) must count for
     * its answer too -- a stream or poster on the host `resolve()`/`search()` just fetched from. In
     * production `AppGraph` reads the registry, which the approval writes before the retried
     * `kino.fetch` goes out; [hosts] (a fixed set) otherwise.
     */
    private val currentHosts: () -> EffectiveHosts = { hosts },
    /**
     * Asks the person about a returned Stream URL's undeclared host (see [askAboutUndeclaredHosts]);
     * `AppGraph` wires its [StreamHostApproval]. Null (tests, and every caller that never heard of
     * it) refuses such a Stream exactly as before.
     */
    private val streamHostApproval: StreamHostDecider? = null,
    /**
     * Whether the person granted this plugin the broad video permission, read NOW from the
     * INSTALLED record (`AppGraph` reads the registry): a grant made in the dialog right after this
     * very `resolve` must count for its answer. Defaults to the record this source was built with.
     */
    private val anyVideoHostGranted: () -> Boolean = { plugin.record.videoFromAnyHost },
) : ContentSource {
    private val id = plugin.manifest.id
    private val name = plugin.manifest.name
    private val source = PluginIds.sourceFor(id)
    private val caps = plugin.manifest.capabilities
    private val allowSeries = "episodes" in caps
    private val allowNext = "browse" in caps
    /** Live channels are apiVersion 2: a v1 plugin's live item is dropped like any invalid one. */
    private val allowLive = PluginOutput.allowsLive(plugin.manifest.apiVersion)
    /**
     * Widevine is the `drm` capability (apiVersion 2; the parser never lets a v1 manifest declare
     * it). The manifest on disk is the one the person approved -- an update that adds `drm` waits
     * for approval -- so this is the same source `allowSeries` and `offersDownloads` read.
     */
    private val allowDrm = "drm" in caps

    /**
     * A converted Nuvio scraper ([InstalledRecord.nuvioScraperId]) chains several page fetches and
     * hoster extractions in one `getStreams` (PelisPlusHD tries ~20 titles one by one): it gets
     * [NUVIO_RESOLVE_TIMEOUT_MS]. Every other plugin keeps [RESOLVE_TIMEOUT_MS]. Like every call
     * limit, its clock stops while the person answers a host question ([PluginCallClock]).
     */
    private val resolveTimeoutMs = if (plugin.record.nuvioScraperId != null) NUVIO_RESOLVE_TIMEOUT_MS else RESOLVE_TIMEOUT_MS

    /** [XuperStreams], only for the one plugin [XuperPrivilege.grants]: null for every other one. */
    private val xuper: XuperStreams? = xuperStreams?.takeIf { XuperPrivilege.grants(plugin.record) }

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
     *
     * The plugin's clock can pause while the person answers a host prompt ([PluginCallClock]), but
     * a `search` never asks ([PluginCall.asksAboutHosts]), so its own limit never stretches and this
     * backstop can't cut a call someone is answering. The one stretch is the queue wait: a `resolve`
     * or `episodes` of the same plugin that is waiting for the person holds the pool's lock for as
     * long as they take. The backstop then drops the queued SEARCH (a cancellation, not a
     * "no responde" strike), which is right: the call being answered keeps running.
     */
    override val searchTimeoutMs: Long? =
        SEARCH_TIMEOUT_MS + maxOf(HOME_TIMEOUT_MS, BROWSE_TIMEOUT_MS, EPISODES_TIMEOUT_MS, resolveTimeoutMs) + SEARCH_BACKSTOP_GRACE_MS

    override fun recognizes(ref: String): Boolean = ref.startsWith(PluginRef.prefixFor(id))

    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> {
        if ("search" !in caps) return emptyFlow()
        return flow {
            val t0 = System.currentTimeMillis()
            emit(SearchEvent.SourceStart(source, label = name))
            val query = queryJson(ctx)
            val out = try {
                caller.call(id, "search", query, SEARCH_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // DownSources shows "<plugin> no respondió: <error>" (or the typed error's own
                // sentence). A timeout's own message names the capability in English: never shown.
                // Its own seconds, not the search limit: the pool's LOAD timeout (10 s) lands here too.
                // A reason Kino worked out itself (a refused host, a site that didn't answer) comes
                // first; it is a whole sentence, which DownSources shows as is.
                val error = PluginCalls.explained(e, name) ?: when (e) {
                    is PluginTimeoutException -> "tardó más de ${e.seconds} s"
                    is PluginErrorException -> PluginErrors.userMessage(e.code, name) ?: e.message ?: "error del plugin"
                    else -> e.message ?: "error del plugin"
                }
                val trace = (e as? PluginException)?.trace?.summary()?.takeIf { it.isNotEmpty() }
                log("[$id] search failed after ${System.currentTimeMillis() - t0} ms: $error" + (trace?.let { " [$it]" } ?: ""))
                emit(SearchEvent.SourceError(source, error, System.currentTimeMillis() - t0, 0, cause = e))
                return@flow
            }
            log("[$id] search ok after ${System.currentTimeMillis() - t0} ms")
            val page = searchPageOf(out, query)
            page.items.forEach { emit(SearchEvent.ResultEvent(source, resultFrom(plugin, it))) }
            emit(SearchEvent.SourceDone(source, page.items.size, System.currentTimeMillis() - t0, more = page.next))
        }
    }

    /** "Ver más" on this plugin's search results: the same query, from [cursor] on. */
    suspend fun searchPage(queryJson: String, cursor: String): GatewayPage {
        val arg = runCatching { JSONObject(queryJson) }.getOrElse { JSONObject() }.put("cursor", cursor.take(PluginOutput.MAX_CURSOR_CHARS))
        val out = callOrThrow("search", arg.toString(), SEARCH_TIMEOUT_MS)
        return pageOf(searchPageOf(out, queryJson))
    }

    /** A search answer as shown: checked like any page, minus the channels unrelated to the query ([LiveSearchRelevance]). */
    private fun searchPageOf(out: String, queryJson: String): PluginPage {
        val page = PluginOutput.page(out, PluginOutput.MAX_SEARCH_ITEMS, allowSeries, allowNext, currentHosts(), allowLive) { log("[$id] $it") }
        val kept = LiveSearchRelevance.filter(page.items, LiveSearchRelevance.queryForms(queryJson))
        if (kept.size < page.items.size) log("[$id] search: dropped ${page.items.size - kept.size} live channel(s) unrelated to the query")
        return page.copy(items = kept)
    }

    override suspend fun browse(ref: String, cursor: String?): GatewayPage {
        if (!allowNext) throw GatewayException("$name no tiene más para mostrar")
        val arg = JSONObject().put("ref", ref.take(PluginOutput.MAX_REF_CHARS)).put("cursor", cursor?.take(PluginOutput.MAX_CURSOR_CHARS) ?: JSONObject.NULL)
        val out = callOrThrow("browse", arg.toString(), BROWSE_TIMEOUT_MS)
        return pageOf(PluginOutput.page(out, PluginOutput.MAX_BROWSE_ITEMS, allowSeries, allowNext = true, currentHosts(), allowLive) { log("[$id] $it") })
    }

    private fun pageOf(page: PluginPage) = GatewayPage(page.items.map { resultFrom(plugin, it) }, page.next)

    override suspend fun resolve(ref: String): GatewayPlayable {
        val own = decodeOwn(ref)
        if (own.kind == PluginRef.SERIES) throw GatewayException("Elige un capítulo primero")
        val out = callOrThrow("resolve", JSONObject.quote(own.ref), resolveTimeoutMs)
        // Outside the call on purpose: its limit (20 s, 45 s for a Nuvio scraper) and the pool's per-plugin lock are both over
        // by now, so a person taking their time to answer holds up neither.
        askAboutUndeclaredHosts(out, own)
        val hosts = streamHostsFor(own)
        val stream = try {
            PluginOutput.stream(out, hosts, xuper, allowDrm)
        } catch (e: PluginContractException) {
            throw GatewayException("$name: ${e.message}", e)
        }
        if (hosts.anyPublicVideoHost) logAnyVideoHost(stream, hosts.strict)
        if (own.kind == PluginRef.LIVE) return livePlayable(stream)
        return playable(stream).copy(
            durationMs = stream.durationMs,
            audioTracks = stream.audioTracks.map { GatewayAudioTrack(it.lang, it.url, it.label) },
        )
    }

    /**
     * The hosts a resolved Stream of [own] is checked against, read NOW (see [currentHosts]): a host
     * approved during the call, or by [askAboutUndeclaredHosts] right after it, counts. A channel's
     * stream may be on any public host only if the INSTALLED record approved liveStreamHosts "any".
     * A movie or episode's only when the person granted the broad video permission AND this is the
     * player's own call ([InteractivePluginCall], never under [BackgroundPluginCall]) or the download
     * queue's ([PluginDownloadCall]): a saved copy follows the same rule as playing that stream, and
     * its client is gated with the same relaxed hosts (`AppGraph.pluginDownloaderFor`). Any other
     * background call keeps the strict hosts. Typed servers are kept either way.
     */
    private suspend fun streamHostsFor(own: PluginRef): EffectiveHosts {
        val hosts = currentHosts()
        if (own.kind == PluginRef.LIVE) return hosts.copy(anyPublicLiveHost = plugin.record.liveStreamHostsAny || plugin.record.streamHostsAny)
        val context = currentCoroutineContext()
        val player = context[InteractivePluginCall] != null && context[BackgroundPluginCall] == null
        val download = context[PluginDownloadCall] != null
        return if ((player || download) && anyVideoHostGranted()) hosts.copy(anyPublicVideoHost = true) else hosts
    }

    /** Logs each host of [stream] that only the broad video permission let through: the host alone, never a path or query. */
    private fun logAnyVideoHost(stream: PluginStream, strict: EffectiveHosts) {
        val urls = listOf(stream.url) + stream.subtitles.map { it.url } + stream.audioTracks.map { it.url }
        urls.mapNotNull { it.toHttpUrlOrNull() }
            .filter { u -> strict.userHostFor(u) == null && !(HostRules.matches(u.host, strict.declared) && strict.allowsScheme(u)) }
            .map { it.host }.distinct()
            .forEach { log("[$id] resolve: $it allowed by the broad video permission") }
    }

    /**
     * Reactive host approval for the URLs [out] (a `resolve` answer) is on -- asked only when a
     * person is waiting for it ([InteractivePluginCall], never under [BackgroundPluginCall]) and
     * [streamHostApproval] is wired. Each host [PluginOutput.undeclaredHosts] finds is put to
     * [streamHostApproval] once, the video's first; and only when the Stream would be valid with all
     * of them granted, so a "yes" never leads to a different refusal. Returns having changed nothing
     * but the approved/rejected hosts: the caller's own [PluginOutput.stream] check then decides, with
     * the hosts read afresh, exactly as it always has -- a rejected video host fails with the same
     * sentence as before, a rejected subtitle or audio host just drops that track. There is no cap
     * on how many hosts the person approves, so an approved host is always added.
     */
    private suspend fun askAboutUndeclaredHosts(out: String, own: PluginRef) {
        val decider = streamHostApproval ?: return
        val context = currentCoroutineContext()
        if (context[InteractivePluginCall] == null || context[BackgroundPluginCall] != null) return
        val hosts = streamHostsFor(own)
        val misses = PluginOutput.undeclaredHosts(out, hosts, xuper, allowDrm)
        if (misses.isEmpty()) return
        val allGranted = hosts.copy(declared = hosts.declared + misses.map { it.host })
        if (runCatching { PluginOutput.stream(out, allGranted, xuper, allowDrm) }.isFailure) return
        // The broad video permission may be offered for a movie's or an episode's video, subtitle or
        // audio host -- never a license's, never a live channel's. Once granted, those need no question.
        var anyVideoHost = false
        for (miss in misses) {
            val offer = own.kind != PluginRef.LIVE && miss.reason != HostApprovalReason.LICENSE
            if (anyVideoHost && offer) continue
            val decision = decider.decide(id, name, miss.host, miss.reason, offer)
            log("[$id] resolve: ${miss.reason} on undeclared host ${miss.host} -> $decision")
            if (decision == StreamHostDecision.APPROVED_ANY_VIDEO_HOST) { anyVideoHost = true; continue }
            if (!miss.required || decision == StreamHostDecision.APPROVED) continue
            return // rejected: the check that follows refuses the Stream with the sentence it always had
        }
    }

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> =
        seriesListing(ref).let { it.episodes to it.series }

    /**
     * The plugin's `episodes(ref)` answer, whole: its chapters, its series block and, for a plugin
     * that keeps each season as its own title, the show's seasons as siblings, each one's [SeasonRef.ref]
     * already wrapped as this plugin's series ref (so choosing it opens it like any series card).
     */
    override suspend fun seriesListing(ref: String): SeriesListing {
        val own = decodeOwn(ref)
        if (own.kind != PluginRef.SERIES || "episodes" !in caps) throw GatewayException("Esto no tiene capítulos")
        val out = callOrThrow("episodes", JSONObject.quote(own.ref), EPISODES_TIMEOUT_MS)
        val parsed = try {
            PluginOutput.episodes(out, { log("[$id] $it") }, currentHosts())
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
        val seasons = parsed.seasons.map { s ->
            SeasonRef(
                contentId = s.id, number = s.number, title = s.title, current = s.current,
                ref = PluginRef(id, s.id, PluginRef.SERIES, s.ref).encode(),
            )
        }
        return SeriesListing(episodes, series, seasons)
    }

    private suspend fun callOrThrow(function: String, argJson: String, timeoutMs: Long): String =
        PluginCalls.callOrThrow(caller, id, name, function, argJson, timeoutMs)

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

        /** `resolve` of a converted Nuvio scraper (see `resolveTimeoutMs`). */
        const val NUVIO_RESOLVE_TIMEOUT_MS = 45_000L

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

        /**
         * A live channel's already-checked [stream] as the player takes it: shared by `resolve` of a
         * live ref and by a channel's inline `stream` (`data/live/PluginLiveProvider`), so both play alike.
         */
        internal fun livePlayable(stream: PluginStream): GatewayPlayable =
            // A live channel has no length, whatever the plugin's Stream said: 0 = the player probes
            // nothing. No side audio either: a side file merged into a moving live window has nothing
            // to stay aligned with; a channel's alternate audio belongs inside its own manifest.
            playable(stream).copy(durationMs = 0L, audioTracks = emptyList())

        private fun playable(stream: PluginStream) = GatewayPlayable(
            kind = "plugin",
            url = stream.url,
            headers = stream.headers,
            mime = stream.mime,
            subtitles = stream.subtitles.map { GatewaySubtitle(it.lang, it.url, it.format) },
            // The same two fields Caracol's Widevine travels in: the player negotiates the license
            // from them, and `PluginDownloadEligibility` refuses to save anything that has one.
            drmLicenseUrl = stream.drm?.licenseUrl.orEmpty(),
            drmLicenseHeaders = stream.drm?.licenseHeaders.orEmpty(),
            expiresInSeconds = stream.expiresInSeconds,
        )

        /** Shared by search, Home rows and "Ver más", so a title reaches `SearchPlayback` the same way from all. */
        fun resultFrom(plugin: InstalledPlugin, item: PluginItem): GatewayResult {
            val kind = when (item.kind) {
                "series" -> PluginRef.SERIES
                PluginOutput.KIND_LIVE -> PluginRef.LIVE
                else -> PluginRef.MOVIE
            }
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
        val message = access.accessFor(pluginId).blockedMessage()
        return if (message != null) PluginBlockedException(message) else GatewayException("El plugin no está listo, inténtalo de nuevo")
    }
}
