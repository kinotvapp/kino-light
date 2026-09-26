package com.arkiv.player.data.plugin

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.magis.ExpiringCache
import com.arkiv.player.data.magis.MagisCatalog
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.data.magis.MagisResult
import com.arkiv.player.data.magis.VodSearchStore
import com.arkiv.player.data.magis.filterRelevant
import com.arkiv.player.data.magis.fullTitleFallbackQueries
import com.arkiv.player.data.magis.itemImages
import com.arkiv.player.data.magis.itemTitle
import com.arkiv.player.data.magis.itemYear
import com.arkiv.player.data.magis.portalQuery
import com.arkiv.player.data.magis.searchItems
import com.arkiv.player.data.magis.seasonFromName
import com.arkiv.player.data.magis.sortBySimilarity
import com.arkiv.player.data.magis.sortSeasons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real, unmodified Magis objects the privileged functions call -- `AppGraph`'s own instances,
 * the same ones `MagisSource` is built with. Handed over as a [Lazy] so opening a runtime never
 * forces them: they need the activation credentials, and only a `kino.xuper.*` call reads them.
 */
internal class XuperNatives(
    val catalog: MagisCatalog,
    val tmdb: TmdbApi,
    /** Persistent search cache (Room). Null in the JVM tests, same as `MagisSource`'s. */
    val vodStore: VodSearchStore?,
)

/**
 * The production [PrivilegedXuperHost]. [DefaultPluginHost] is `final` (this codebase has no other
 * open production class), so every ordinary `kino.*` member -- fetch/storage/cookies/config/crypto,
 * all unchanged from any other installed plugin -- is delegated to one, by interface delegation
 * rather than inheritance. Only the 5 `xuper*` members are this class's own; the ones still
 * throwing are stubs until Tasks 6-10 replace each with the real, protected Magis call.
 */
class DefaultPrivilegedXuperHost internal constructor(
    id: String,
    http: PluginHttp,
    storage: PluginStorage,
    config: PluginConfig,
    cookies: PluginCookies?,
    hosts: EffectiveHosts,
    natives: Lazy<XuperNatives>,
) : PluginHost by DefaultPluginHost(id, http, storage, config, cookies, hosts), PrivilegedXuperHost {
    private val search = XuperSearch(natives)

    override suspend fun xuperSearch(argsJson: String): String = search.search(argsJson)
    override suspend fun xuperHome(): String = throw NotImplementedError("Task 9")
    override suspend fun xuperBrowse(ref: String, cursor: String?): String = throw NotImplementedError("Task 10")
    override suspend fun xuperEpisodes(ref: String): String = throw NotImplementedError("Task 7")
    override suspend fun xuperResolve(ref: String): String = throw NotImplementedError("Task 6")
}

/**
 * The one production decision of which [PluginHost] a plugin's runtime gets: [DefaultPrivilegedXuperHost]
 * only for the plugin [XuperPrivilege.grants], [DefaultPluginHost] for every other one.
 * `AppGraph.openPluginRuntime` calls this rather than inlining the branch, and so does
 * `XuperPrivilegeGateTest` -- the SAME function, not a reproduction of its condition, so a later
 * change to the gate (or its removal) can't leave a test green while the real gate silently breaks.
 * [xuperNatives] is only ever read by the privileged host, and only when a `kino.xuper.*` call runs.
 */
internal fun pluginHostFor(
    plugin: InstalledPlugin,
    http: PluginHttp,
    storage: PluginStorage,
    config: PluginConfig,
    cookies: PluginCookies?,
    hosts: EffectiveHosts,
    xuperNatives: Lazy<XuperNatives>,
): PluginHost = if (XuperPrivilege.grants(plugin.record)) {
    DefaultPrivilegedXuperHost(plugin.id, http, storage, config, cookies, hosts, xuperNatives)
} else {
    DefaultPluginHost(plugin.id, http, storage, config, cookies, hosts)
}

/**
 * `kino.xuper.search`: `MagisSource.search`'s orchestration (`sortedItems` -> `titleForms` ->
 * `resultFrom`), re-homed line for line onto the same `MagisCatalog`/`TmdbApi`/`VodSearchStore` and
 * the same `MagisSearch.kt` helpers -- same portal queries in the same order, same caches, same
 * ranking, same fallback, same season filter, same telemetry. What differs is only the packaging:
 *  - the answer is the plugin envelope, each result a plugin item instead of a `GatewayResult`;
 *  - a failed portal call keeps its [MagisResult] (instead of `MagisSource.explain`'s sentence), so
 *    Task 4's `toPluginError()` sees the real code.
 * `XuperSearchParityTest` replays searches recorded against the real portal through both this and
 * `MagisSource` and requires the same results.
 *
 * [search]'s argument is a [GatewaySearchQuery]'s fields: `{"q", "type", "season", "episode",
 * "tmdbId"}` -- `type` in its vocabulary ("movie"/"tv"/"anime"), with the plugin contract's
 * "series" read as "tv". Each item is a plugin `SourceItem` (`id` = contentId, `ref` = the same
 * `magis1:` ref MagisSource mints, `title`, `kind`, `year`, `poster`/`backdrop` when the portal has
 * them) plus two Xuper-only fields the contract reader ignores, `season` and `episodeCount`, so
 * nothing MagisSource's result carried is lost on the way.
 */
internal class XuperSearch(private val natives: Lazy<XuperNatives>) {
    private val lock = Mutex()
    private val searches = ExpiringCache<String, List<JSONObject>>(TTL_MS, cap = 32)

    /** A portal call that answered with no data; carries the portal's own result for [envelopeOf]. */
    private class PortalFailure(val result: MagisResult<*>) : Exception(result.toString())

    suspend fun search(argsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val ctx = queryOf(JSONObject(argsJson))
            val items = JSONArray()
            for (item in sortedItems(ctx)) itemFrom(item, ctx)?.let { items.put(it) }
            JSONObject().put("ok", true).put("data", items).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            envelopeOf(e)
        }
    }

    /** `as? String`, not `optString`: Android's org.json turns a JSON `null` into the text "null". */
    private fun queryOf(args: JSONObject) = GatewaySearchQuery(
        q = args.opt("q") as? String ?: "",
        type = when (val type = (args.opt("type") as? String).orEmpty().ifBlank { "movie" }) {
            "series" -> "tv"
            else -> type
        },
        season = args.optInt("season", 0),
        episode = args.optInt("episode", 0),
        tmdbId = args.optInt("tmdbId", 0),
    )

    private fun envelopeOf(e: Throwable): String {
        val (code, message) = when (val r = (e as? PortalFailure)?.result) {
            is MagisResult.PortalError -> r.toPluginError()
            is MagisResult.RedError -> r.toPluginError()
            // Same fallback text MagisSource.search shows for an error with no message.
            else -> PluginErrors.UNAVAILABLE to (e.message ?: "error de Xuper")
        }
        return JSONObject().put("ok", false).put("code", code).put("message", message).toString()
    }

    // --- MagisSource.sortedItems ---------------------------------------------------------------

    private suspend fun sortedItems(ctx: GatewaySearchQuery): List<JSONObject> {
        val catalog = natives.value.catalog
        val vodStore = natives.value.vodStore
        val forms = titleForms(ctx)
        // One portal query per title form's head, deduped; see MagisSource.sortedItems.
        val queries = forms.map { portalQuery(it) }.distinctBy { it.lowercase() }
        val seen = HashSet<String>()
        val pool = mutableListOf<JSONObject>()
        var lastError: Throwable? = null
        suspend fun fetchInto(qs: List<String>) {
            for (q in qs) {
                val key = q.lowercase()
                // In-memory first, then the persistent cache (survives process death), then the portal.
                val part = lock.withLock { searches[key] }
                    ?: vodStore?.read(key)?.also { lock.withLock { searches[key] = it } }
                    ?: try {
                        val r = catalog.search(q)
                        val data = r.getOrNull() ?: throw PortalFailure(r)
                        searchItems(data).also {
                            lock.withLock { searches[key] = it }
                            vodStore?.write(key, it)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                        null
                    }
                if (part != null) for (item in part) if (seen.add(item.optString("contentId"))) pool.add(item)
            }
        }
        fetchInto(queries)
        // Only an error if EVERY query failed; a title present under one form is still a good result.
        val firstPassError = lastError
        if (pool.isEmpty() && firstPassError != null) throw firstPassError

        // The request's own title first, then only real matches (the portal pads a miss with look-alikes).
        var items = filterRelevant(sortBySimilarity(pool, forms), forms)

        // Second chance with the FULL titles, only when nothing relevant came back.
        val fullQueries = if (items.isEmpty()) fullTitleFallbackQueries(forms, queries) else emptyList()
        if (fullQueries.isNotEmpty()) {
            fetchInto(fullQueries)
            items = filterRelevant(sortBySimilarity(pool, forms), forms)
        }

        val isSeries = ctx.type == "tv" || ctx.type == "anime"
        if (isSeries && ctx.season > 0) {
            // The requested season only; if NONE matches, all of them.
            val matching = items.filter {
                it.optString("programType") !in MagisRef.SERIES ||
                    seasonFromName(itemTitle(it)) == ctx.season
            }
            if (matching.isNotEmpty()) items = matching
        }
        // Same "0 fuentes" telemetry, same text, as MagisSource.
        if (items.isEmpty() && ctx.tmdbId > 0) {
            com.arkiv.player.crash.Crash.report(
                com.arkiv.player.crash.NoSourcesFound(
                    "0 sources · \"${ctx.q}\" tmdb=${ctx.tmdbId} type=${ctx.type} S${ctx.season}E${ctx.episode} " +
                        "poolEmpty=${pool.isEmpty()} queries=$queries fullTitleRetry=$fullQueries forms=$forms",
                ),
                "no-sources",
            )
        }
        return sortSeasons(items)
    }

    // --- MagisSource.titleForms ----------------------------------------------------------------

    /** The asked title plus TMDB's es-MX, original, English and other Spanish titles; see MagisSource.titleForms. */
    private suspend fun titleForms(ctx: GatewaySearchQuery): List<String> {
        if (ctx.tmdbId <= 0) return listOf(ctx.q)
        val type = if (ctx.type == "movie") "movie" else "tv"
        val detail = runCatching { natives.value.tmdb.detail(type, ctx.tmdbId) }.getOrNull()
        val forms = mutableListOf(ctx.q)
        detail?.title?.takeIf { it.isNotBlank() }?.let { forms.add(it) }
        detail?.originalTitle?.takeIf { it.isNotBlank() }?.let { forms.add(it) }
        detail?.englishTitle?.takeIf { it.isNotBlank() }?.let { forms.add(it) }
        detail?.spanishTitles?.take(MAX_SPANISH_FORMS)?.forEach { forms.add(it) }
        return forms.distinctBy { it.trim().lowercase() }
    }

    // --- MagisSource.resultFrom, as a plugin item ----------------------------------------------

    private fun itemFrom(item: JSONObject, ctx: GatewaySearchQuery): JSONObject? {
        val contentId = item.optString("contentId").takeIf { it.isNotBlank() } ?: return null
        val programType = item.optString("programType").ifBlank { "movie" }
        val title = itemTitle(item)
        val isSeries = programType in MagisRef.SERIES
        val season = if (isSeries) seasonFromName(title) else ctx.season
        val episodeCount = item.opt("volumnCount")?.toString()?.toIntOrNull()
            ?: item.opt("updateCount")?.toString()?.toIntOrNull()
            ?: 0
        val out = JSONObject()
            .put("id", contentId)
            .put("ref", MagisRef(contentId, programType, ctx.episode).encode())
            .put("title", title.ifBlank { contentId })
            .put("kind", if (isSeries) "series" else "movie")
            .put("year", itemYear(item))
            .put("season", season)
            .put("episodeCount", episodeCount)
        for ((key, url) in itemImages(item)) out.put(key, url)
        return out
    }

    private companion object {
        /** MagisSource's in-memory search cache lifetime. */
        const val TTL_MS = 6 * 60 * 60 * 1000L
        /** MagisSource's cap on extra Spanish title variants per search. */
        const val MAX_SPANISH_FORMS = 3
    }
}
