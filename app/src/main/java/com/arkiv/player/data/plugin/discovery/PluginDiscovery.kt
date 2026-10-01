package com.arkiv.player.data.plugin.discovery

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.ManifestParser
import com.arkiv.player.data.plugin.ManifestResult
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginFetchStatusException
import com.arkiv.player.data.plugin.PluginFetcher
import com.arkiv.player.data.plugin.PluginFileTooBigException
import com.arkiv.player.data.plugin.PluginStore
import com.arkiv.player.data.plugin.ReservedPluginIds
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.writeFileAtomically
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A community plugin as the lists show it: its repo, what its manifest says, and how GitHub ranks it. */
data class DiscoveredPlugin(
    val owner: String,
    val repo: String,
    /** The manifest's `id`. */
    val id: String,
    val name: String,
    val description: String,
    val stars: Int,
    /** The manifest's entry is sealed (apiVersion 5's `sealedEntry`): the card says "Código cerrado". */
    val sealed: Boolean = false,
) {
    /** `owner/repo`: what the installer is given. */
    val address: String get() = "$owner/$repo"
}

/**
 * [FRESH]: this load's GitHub search. [CACHE]: the disk copy (of a search or of the fallback list).
 * [FALLBACK]: this load's read of the static community list, because GitHub could not answer. [NONE]: nothing.
 */
enum class DiscoveryOrigin { FRESH, CACHE, FALLBACK, NONE }

data class DiscoveryResult(val plugins: List<DiscoveredPlugin>, val origin: DiscoveryOrigin) {
    companion object {
        val NONE = DiscoveryResult(emptyList(), DiscoveryOrigin.NONE)
    }
}

interface PluginDiscoveryProvider {
    /** What is on disk, whatever its age; never touches the network, never throws. */
    fun cached(): DiscoveryResult

    /** A search when due (or [force]d and allowed), else the disk copy. Never throws but for cancellation. */
    suspend fun load(force: Boolean): DiscoveryResult
}

/** `owner/repo` lowercased for any spelling [PluginAddress.parse] accepts (URL, folder, `@ref`), or null. */
fun ownerRepoKey(address: String): String? = PluginAddress.parse(address)?.let { "${it.owner}/${it.repo}".lowercase() }

/**
 * The community list without what the person must not see twice or must not be fooled by (ruling R5):
 * a repo the [catalog] already recommends (case-insensitive; Xuper's new and legacy repo count as one); an impostor, meaning a plugin whose
 * manifest id belongs to a [catalog] entry or to [ReservedPluginIds] but whose repo is not that one
 * (dropped whatever its stars, so it can never hide the real plugin); a plugin whose manifest id is
 * taken by an installed plugin from ANOTHER repo (its install would be refused; Xuper's official new and
 * legacy repos count as one, so a legacy install keeps the new repo listed as installed); and repeats by address
 * or id (first, most stars, wins). A discovered repo that is itself installed stays: its card shows
 * "Instalado".
 */
fun dedupeDiscovered(
    discovered: List<DiscoveredPlugin>,
    catalog: List<CatalogEntry>,
    installed: List<InstalledPlugin>,
): List<DiscoveredPlugin> {
    // Xuper's new and legacy repos are one plugin: a catalog listing either one covers both.
    val xuperKeys = XuperPrivilege.OFFICIAL_REPOS.mapNotNull(::ownerRepoKey).toSet()
    val catalogKeys = catalog.mapNotNull { ownerRepoKey(it.repo) }.toSet()
        .let { keys -> if (keys.any { it in xuperKeys }) keys + xuperKeys else keys }
    // Every repo that may use an id; a catalog entry and a reserved id naming different repos both count.
    val idOwners = HashMap<String, MutableSet<String>>()
    catalog.forEach { e -> ownerRepoKey(e.repo)?.let { idOwners.getOrPut(e.id) { HashSet() }.add(it) } }
    ReservedPluginIds.OWNERS.forEach { (id, repos) ->
        repos.forEach { repo -> ownerRepoKey(repo)?.let { idOwners.getOrPut(id) { HashSet() }.add(it) } }
    }
    val seenKeys = HashSet<String>()
    val seenIds = HashSet<String>()
    return discovered.filter { d ->
        val key = d.address.lowercase()
        if (key in catalogKeys) return@filter false
        if (idOwners[d.id]?.contains(key) == false) return@filter false
        // Xuper's new and legacy repos are one plugin here too: an updating person with the legacy install
        // still sees the new official repo (its card shows "Instalado"), never an empty section.
        if (installed.any { it.id == d.id && !sameRepo(ownerRepoKey(it.record.address), key, xuperKeys) }) return@filter false
        seenKeys.add(key) && seenIds.add(d.id)
    }
}

/** Whether two lowercased `owner/repo` keys name the same plugin: equal, or both official Xuper repos ([xuperKeys]). */
private fun sameRepo(a: String?, b: String, xuperKeys: Set<String>): Boolean = a == b || (a in xuperKeys && b in xuperKeys)

/**
 * Finds community plugins (spec 2026-09-28 §1): one GitHub search through [transport] (the only call
 * to [GithubApi.HOST]), then each result's `kino-plugin.json` through [fetcher] (raw.githubusercontent.com,
 * the installer's host), keeping repos whose manifest is valid for this build and not
 * `"discoverable": false`.
 *
 * GitHub is never asked more than it allows: a search at most every [intervalMs] unless forced, never
 * two within [MIN_ATTEMPT_SPACING_MS] whoever asks, and none at all until the backoff a 403/429 set
 * ([backoffUntil], persisted with the list so a restart honours it). Loads are single-flight. Every
 * failure answers with the last good list ([DiscoveryOrigin.CACHE]) or nothing ([DiscoveryOrigin.NONE]).
 *
 * Fallback: when the search fails (any non-success) or finds nothing valid, and there is no list of its
 * own to show, the static community list [fallback] (published next to the recommended catalog, same
 * hosts) is read instead and its repos go through the SAME manifest checks ([DiscoveryOrigin.FALLBACK]).
 * It is cached like a search, but marked, so it never holds GitHub off for [intervalMs]: the next load
 * past the spacing and any backoff asks GitHub again. A cached fallback list older than [intervalMs] is
 * read again when GitHub still fails. At most one fallback read per [MIN_ATTEMPT_SPACING_MS].
 *
 * Telemetry: when the search failed and there was no search list to show, [report] gets ONE event per
 * instance (the app holds one, so once per app start), with the failure class only (see [DiscoveryFailure]).
 *
 * [cacheFile]: `{"schema":1,"fetchedAt":ms,"blockedUntil":ms,"fallback":bool,"plugins":[{owner,repo,id,name,description,stars}]}`,
 * written atomically; anything unreadable is treated as absent.
 */
class PluginDiscovery(
    private val transport: GithubTransport,
    private val fetcher: PluginFetcher,
    private val cacheFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = INTERVAL_MS,
    manifestConcurrency: Int = 4,
    private val scanDeadlineMs: Long = SCAN_DEADLINE_MS,
    /** Milliseconds on a clock that never jumps; only for the scan budget ([clock] dates the cache). */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The static community list read when GitHub cannot answer; null: no fallback. */
    private val fallback: CommunityFallback? = null,
    /** Receives the telemetry extras of a failed search (see the class KDoc). */
    private val report: (extras: Map<String, String>) -> Unit = { extras ->
        com.arkiv.player.crash.Crash.report(com.arkiv.player.crash.DiscoveryFailed(FAILURE_MESSAGE), "discovery", extras)
    },
) : PluginDiscoveryProvider {
    private val mutex = Mutex()
    private val manifestPermits = Semaphore(manifestConcurrency)
    private val completedLoads = AtomicLong()
    @Volatile private var lastResult: DiscoveryResult = DiscoveryResult.NONE
    @Volatile private var lastAttemptAt: Long? = null
    @Volatile private var lastFallbackAt: Long? = null
    private val reported = AtomicBoolean(false)

    private data class DiskState(
        val fetchedAt: Long,
        val blockedUntil: Long,
        val plugins: List<DiscoveredPlugin>,
        /** The list came from the fallback, not from a GitHub search. */
        val fromFallback: Boolean = false,
    ) {
        fun asResult(): DiscoveryResult = if (fetchedAt > 0) DiscoveryResult(plugins, DiscoveryOrigin.CACHE) else DiscoveryResult.NONE
    }

    /** Why the search did not give a list, for [report]; [serverDateMs] is the search answer's `Date`, when there was one. */
    private class Failure(val kind: String, val error: Throwable? = null, val serverDateMs: Long? = null)

    /** What the fallback did: [status] is `ok`, `failed` or `unused`; [result] is set only when it gave plugins. */
    private class FallbackOutcome(val status: String, val result: DiscoveryResult? = null, val serverDateMs: Long? = null)

    override fun cached(): DiscoveryResult = readState()?.asResult() ?: DiscoveryResult.NONE

    override suspend fun load(force: Boolean): DiscoveryResult {
        val seen = completedLoads.get()
        return mutex.withLock {
            // Single flight: a load that finished while this one waited for the lock is the answer.
            if (completedLoads.get() != seen) return@withLock lastResult
            val result = withContext(Dispatchers.IO) { loadLocked(force) }
            lastResult = result
            completedLoads.incrementAndGet()
            result
        }
    }

    private suspend fun loadLocked(force: Boolean): DiscoveryResult {
        val now = clock()
        val state = readState() ?: DiskState(0, 0, emptyList())
        val cache = state.asResult()
        // A backoff further away than any GitHub could set (a clock that ran ahead when the 403/429
        // arrived, a cache restored from another device) is ignored, never a block until some far date.
        if (now < state.blockedUntil && state.blockedUntil - now <= MAX_BACKOFF_MS) {
            return withoutSearch(state, now, Failure(DiscoveryFailure.RATE_LIMITED))
        }
        // A copy dated in the future (the clock moved backwards) is stale, never fresh forever. A fallback
        // copy never counts as fresh here: GitHub is asked again as soon as spacing and backoff allow.
        if (!force && !state.fromFallback && state.fetchedAt > 0 && now - state.fetchedAt in 0L until intervalMs) return cache
        lastAttemptAt?.let { if (now - it in 0L until MIN_ATTEMPT_SPACING_MS) return withoutSearch(state, now, null) }
        lastAttemptAt = now
        val response = try {
            transport.search()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return withoutSearch(state, now, Failure(DiscoveryFailure.of(e), e))
        }
        val serverDate = response.headers["date"]?.let { runCatching { okhttp3.Headers.headersOf("Date", it).getDate("Date")?.time }.getOrNull() }
        if (response.code == 403 || response.code == 429) {
            val blocked = state.copy(blockedUntil = backoffUntil(now, response.headers))
            writeState(blocked)
            return withoutSearch(blocked, now, Failure(DiscoveryFailure.ofStatus(response.code), serverDateMs = serverDate))
        }
        if (response.code != 200) return withoutSearch(state, now, Failure(DiscoveryFailure.ofStatus(response.code), serverDateMs = serverDate))
        val repos = response.body?.let(GithubSearchParser::parse)
            ?: return withoutSearch(state, now, Failure(DiscoveryFailure.PARSE, serverDateMs = serverDate))
        val previous = state.plugins.associateBy { it.address.lowercase() }
        val plugins = filterByManifest(repos, previous)
        val searched = DiskState(fetchedAt = now, blockedUntil = 0, plugins = plugins)
        writeState(searched)
        // The search worked but nothing in it can be shown (every manifest unreadable or invalid): the fallback may still have some.
        if (plugins.isEmpty()) tryFallback(searched, now).result?.let { return it }
        return DiscoveryResult(plugins, DiscoveryOrigin.FRESH)
    }

    /**
     * The answer when this load got no search list: the cache when it holds a search list (any age) or a
     * fallback list younger than [intervalMs]; else the fallback, else whatever the cache has. [failure]
     * (null when the search was only skipped for spacing) is reported when no search list was there to show.
     */
    private suspend fun withoutSearch(state: DiskState, now: Long, failure: Failure?): DiscoveryResult {
        val cache = state.asResult()
        if (cache.plugins.isNotEmpty() && !state.fromFallback) return cache
        val freshFallback = cache.plugins.isNotEmpty() && now - state.fetchedAt in 0L until intervalMs
        val outcome = if (freshFallback) FallbackOutcome("cached") else tryFallback(state, now)
        // A fallback read that did not work still leaves the older fallback list on screen, when there is one.
        val status = if (outcome.result == null && cache.plugins.isNotEmpty()) "cached" else outcome.status
        if (failure != null) reportOnce(failure, status, now, outcome.serverDateMs)
        return outcome.result ?: cache
    }

    /** Reads the fallback list (at most once per [MIN_ATTEMPT_SPACING_MS]) and caches it, marked, keeping [state]'s backoff. */
    private suspend fun tryFallback(state: DiskState, now: Long): FallbackOutcome {
        val source = fallback ?: return FallbackOutcome("unused")
        lastFallbackAt?.let { if (now - it in 0L until MIN_ATTEMPT_SPACING_MS) return FallbackOutcome("unused") }
        lastFallbackAt = now
        val answer = try {
            source.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FallbackOutcome("failed")
        }
        val repos = answer.repos ?: return FallbackOutcome("failed", serverDateMs = answer.serverDateMs)
        val plugins = filterByManifest(repos, state.plugins.associateBy { it.address.lowercase() })
        if (plugins.isEmpty()) return FallbackOutcome("failed", serverDateMs = answer.serverDateMs)
        writeState(DiskState(fetchedAt = now, blockedUntil = state.blockedUntil, plugins = plugins, fromFallback = true))
        return FallbackOutcome("ok", DiscoveryResult(plugins, DiscoveryOrigin.FALLBACK), answer.serverDateMs)
    }

    private fun reportOnce(failure: Failure, fallbackStatus: String, now: Long, fallbackDateMs: Long?) {
        if (!reported.compareAndSet(false, true)) return
        val extras = LinkedHashMap<String, String>()
        extras["failure"] = failure.kind
        extras["fallback"] = fallbackStatus
        if (failure.kind == DiscoveryFailure.TLS) {
            extras["clock"] = DiscoveryFailure.clock(now, failure.serverDateMs ?: fallbackDateMs)
            extras["cert_time"] = (failure.error?.let(DiscoveryFailure::certTimeRejected) ?: false).toString()
        }
        try {
            report(extras)
        } catch (e: Exception) {
            // Telemetry never breaks discovery.
        }
    }

    /**
     * The whole scan ends within [scanDeadlineMs] (measured on [monotonicMs], never the wall [clock]):
     * each manifest gets at most the budget left, and a repo reached once it is spent counts as a
     * timeout, so hanging raw hosts cannot keep the first screen searching for a minute and more.
     */
    private suspend fun filterByManifest(repos: List<DiscoveredRepo>, previous: Map<String, DiscoveredPlugin>): List<DiscoveredPlugin> {
        val deadline = monotonicMs() + scanDeadlineMs
        return coroutineScope {
            repos.map { r ->
                async { manifestPermits.withPermit { pluginOf(r, previous[r.key], deadline - monotonicMs()) } }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Ruling R7: a transient failure (offline, timeout, 5xx, no scan budget left) keeps [previous] with
     * today's stars; a verdict about the repo (404, 410, 451, too big, invalid, too new,
     * `discoverable: false`) drops it. [budgetMs] is what is left of the scan's deadline.
     */
    private suspend fun pluginOf(r: DiscoveredRepo, previous: DiscoveredPlugin?, budgetMs: Long): DiscoveredPlugin? {
        if (budgetMs <= 0) return previous?.copy(stars = r.stars)
        val bytes = try {
            withTimeout(minOf(MANIFEST_TIMEOUT_MS, budgetMs)) {
                fetcher.fetch(PluginAddress(r.owner, r.repo).rawUrl(PluginStore.MANIFEST_FILE), ManifestParser.MAX_BYTES + 1)
            }
        } catch (e: TimeoutCancellationException) {
            return previous?.copy(stars = r.stars)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: PluginFileTooBigException) {
            return null
        } catch (e: PluginFetchStatusException) {
            // 410 Gone and 451 Unavailable For Legal Reasons are verdicts; 5xx, 429 and the rest may pass.
            return if (e.code == 410 || e.code == 451) null else previous?.copy(stars = r.stars)
        } catch (e: IOException) {
            return previous?.copy(stars = r.stars)
        } catch (e: Exception) {
            return null
        }
        if (bytes.size > ManifestParser.MAX_BYTES) return null
        val m = (ManifestParser.parse(bytes.toString(Charsets.UTF_8)) as? ManifestResult.Valid)?.manifest ?: return null
        if (!m.discoverable) return null
        return DiscoveredPlugin(r.owner, r.repo, m.id, m.name, m.description, r.stars, sealed = m.entrySealed)
    }

    private fun readState(): DiskState? = runCatching {
        if (!cacheFile.isFile) return@runCatching null
        val o = JSONObject(cacheFile.readText())
        if (o.optInt("schema") != SCHEMA) return@runCatching null
        val array = o.optJSONArray("plugins") ?: JSONArray()
        val plugins = (0 until minOf(array.length(), DiscoveryRules.MAX_RESULTS)).mapNotNull { i -> array.optJSONObject(i)?.let(::pluginFromJson) }
        DiskState(o.optLong("fetchedAt", 0L).coerceAtLeast(0L), o.optLong("blockedUntil", 0L).coerceAtLeast(0L), plugins, o.optBoolean("fallback", false))
    }.getOrNull()

    private fun pluginFromJson(o: JSONObject): DiscoveredPlugin? {
        val owner = o.opt("owner") as? String ?: return null
        val repo = o.opt("repo") as? String ?: return null
        if (ownerRepoKey("$owner/$repo") == null) return null
        val id = (o.opt("id") as? String)?.takeIf { ManifestParser.ID.matches(it) } ?: return null
        val name = (o.opt("name") as? String)?.takeIf { it.isNotBlank() }?.take(ManifestParser.MAX_NAME_CHARS) ?: return null
        val description = ((o.opt("description") as? String) ?: "").take(ManifestParser.MAX_DESCRIPTION_CHARS)
        return DiscoveredPlugin(owner, repo, id, name, description, o.optInt("stars", 0).coerceAtLeast(0), sealed = o.optBoolean("sealed", false))
    }

    /** A disk that refuses costs only the cache: the answer is still returned. */
    private fun writeState(state: DiskState) {
        val json = JSONObject().put("schema", SCHEMA).put("fetchedAt", state.fetchedAt).put("blockedUntil", state.blockedUntil)
            .put("fallback", state.fromFallback)
            .put("plugins", JSONArray(state.plugins.map {
                JSONObject().put("owner", it.owner).put("repo", it.repo).put("id", it.id)
                    .put("name", it.name).put("description", it.description).put("stars", it.stars)
                    .apply { if (it.sealed) put("sealed", true) }
            }))
        try {
            writeFileAtomically(cacheFile, json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // Kept in memory for this load only.
        } catch (e: SecurityException) {
            // Same.
        }
    }

    companion object {
        const val INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val MIN_ATTEMPT_SPACING_MS = 60 * 1000L
        const val DEFAULT_BACKOFF_MS = 15 * 60 * 1000L
        const val MIN_BACKOFF_MS = 60 * 1000L
        const val MAX_BACKOFF_MS = 24 * 60 * 60 * 1000L
        const val MANIFEST_TIMEOUT_MS = 10 * 1000L
        const val SCAN_DEADLINE_MS = 20 * 1000L
        /** The telemetry event's constant message: one GitHub issue collects every device. */
        const val FAILURE_MESSAGE = "discovery: github search failed"
        private const val SCHEMA = 1

        /**
         * When GitHub may be asked again after a 403/429 at [now]: `Retry-After` (seconds) wins, then
         * `X-RateLimit-Reset` (epoch seconds), else [DEFAULT_BACKOFF_MS]; always within
         * [MIN_BACKOFF_MS]..[MAX_BACKOFF_MS] of [now]. [headers] names are lowercase.
         */
        fun backoffUntil(now: Long, headers: Map<String, String>): Long {
            // Clamped in seconds first, so an absurd value can never overflow the millisecond maths.
            val maxSeconds = MAX_BACKOFF_MS / 1000
            val nowSeconds = now / 1000
            val retryAfter = headers["retry-after"]?.trim()?.toLongOrNull()?.coerceIn(0L, maxSeconds)
            val reset = headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()
                ?.coerceIn(nowSeconds - maxSeconds, nowSeconds + maxSeconds)
            val candidate = when {
                retryAfter != null -> now + retryAfter * 1000
                reset != null -> reset * 1000
                else -> now + DEFAULT_BACKOFF_MS
            }
            return candidate.coerceIn(now + MIN_BACKOFF_MS, now + MAX_BACKOFF_MS)
        }
    }
}
