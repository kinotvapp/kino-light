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
) {
    /** `owner/repo`: what the installer is given. */
    val address: String get() = "$owner/$repo"
}

enum class DiscoveryOrigin { FRESH, CACHE, NONE }

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
 * a repo the [catalog] already recommends (case-insensitive); an impostor, meaning a plugin whose
 * manifest id belongs to a [catalog] entry or to [ReservedPluginIds] but whose repo is not that one
 * (dropped whatever its stars, so it can never hide the real plugin); a plugin whose manifest id is
 * taken by an installed plugin from ANOTHER repo (its install would be refused); and repeats by address
 * or id (first, most stars, wins). A discovered repo that is itself installed stays: its card shows
 * "Instalado".
 */
fun dedupeDiscovered(
    discovered: List<DiscoveredPlugin>,
    catalog: List<CatalogEntry>,
    installed: List<InstalledPlugin>,
): List<DiscoveredPlugin> {
    val catalogKeys = catalog.mapNotNull { ownerRepoKey(it.repo) }.toSet()
    // Every repo that may use an id; a catalog entry and a reserved id naming different repos both count.
    val idOwners = HashMap<String, MutableSet<String>>()
    catalog.forEach { e -> ownerRepoKey(e.repo)?.let { idOwners.getOrPut(e.id) { HashSet() }.add(it) } }
    ReservedPluginIds.OWNERS.forEach { (id, repo) -> ownerRepoKey(repo)?.let { idOwners.getOrPut(id) { HashSet() }.add(it) } }
    val seenKeys = HashSet<String>()
    val seenIds = HashSet<String>()
    return discovered.filter { d ->
        val key = d.address.lowercase()
        if (key in catalogKeys) return@filter false
        if (idOwners[d.id]?.contains(key) == false) return@filter false
        if (installed.any { it.id == d.id && ownerRepoKey(it.record.address) != key }) return@filter false
        seenKeys.add(key) && seenIds.add(d.id)
    }
}

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
 * [cacheFile]: `{"schema":1,"fetchedAt":ms,"blockedUntil":ms,"plugins":[{owner,repo,id,name,description,stars}]}`,
 * written atomically; anything unreadable is treated as absent.
 */
class PluginDiscovery(
    private val transport: GithubTransport,
    private val fetcher: PluginFetcher,
    private val cacheFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = INTERVAL_MS,
    manifestConcurrency: Int = 4,
) : PluginDiscoveryProvider {
    private val mutex = Mutex()
    private val manifestPermits = Semaphore(manifestConcurrency)
    private val completedLoads = AtomicLong()
    @Volatile private var lastResult: DiscoveryResult = DiscoveryResult.NONE
    @Volatile private var lastAttemptAt: Long? = null

    private data class DiskState(val fetchedAt: Long, val blockedUntil: Long, val plugins: List<DiscoveredPlugin>) {
        fun asResult(): DiscoveryResult = if (fetchedAt > 0) DiscoveryResult(plugins, DiscoveryOrigin.CACHE) else DiscoveryResult.NONE
    }

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
        if (now < state.blockedUntil && state.blockedUntil - now <= MAX_BACKOFF_MS) return cache
        // A copy dated in the future (the clock moved backwards) is stale, never fresh forever.
        if (!force && state.fetchedAt > 0 && now - state.fetchedAt in 0L until intervalMs) return cache
        lastAttemptAt?.let { if (now - it in 0L until MIN_ATTEMPT_SPACING_MS) return cache }
        lastAttemptAt = now
        val response = try {
            transport.search()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return cache
        }
        if (response.code == 403 || response.code == 429) {
            writeState(state.copy(blockedUntil = backoffUntil(now, response.headers)))
            return cache
        }
        if (response.code != 200) return cache
        val repos = GithubSearchParser.parse(response.body ?: return cache) ?: return cache
        val previous = state.plugins.associateBy { it.address.lowercase() }
        val plugins = filterByManifest(repos, previous)
        writeState(DiskState(fetchedAt = now, blockedUntil = 0, plugins = plugins))
        return DiscoveryResult(plugins, DiscoveryOrigin.FRESH)
    }

    private suspend fun filterByManifest(repos: List<DiscoveredRepo>, previous: Map<String, DiscoveredPlugin>): List<DiscoveredPlugin> =
        coroutineScope {
            repos.map { r -> async { manifestPermits.withPermit { pluginOf(r, previous[r.key]) } } }.awaitAll().filterNotNull()
        }

    /**
     * Ruling R7: a transient failure (offline, timeout, 5xx) keeps [previous] with today's stars; a
     * verdict about the repo (404, 410, 451, too big, invalid, too new, `discoverable: false`) drops it.
     */
    private suspend fun pluginOf(r: DiscoveredRepo, previous: DiscoveredPlugin?): DiscoveredPlugin? {
        val bytes = try {
            withTimeout(MANIFEST_TIMEOUT_MS) {
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
        return DiscoveredPlugin(r.owner, r.repo, m.id, m.name, m.description, r.stars)
    }

    private fun readState(): DiskState? = runCatching {
        if (!cacheFile.isFile) return@runCatching null
        val o = JSONObject(cacheFile.readText())
        if (o.optInt("schema") != SCHEMA) return@runCatching null
        val array = o.optJSONArray("plugins") ?: JSONArray()
        val plugins = (0 until minOf(array.length(), DiscoveryRules.MAX_RESULTS)).mapNotNull { i -> array.optJSONObject(i)?.let(::pluginFromJson) }
        DiskState(o.optLong("fetchedAt", 0L).coerceAtLeast(0L), o.optLong("blockedUntil", 0L).coerceAtLeast(0L), plugins)
    }.getOrNull()

    private fun pluginFromJson(o: JSONObject): DiscoveredPlugin? {
        val owner = o.opt("owner") as? String ?: return null
        val repo = o.opt("repo") as? String ?: return null
        if (ownerRepoKey("$owner/$repo") == null) return null
        val id = (o.opt("id") as? String)?.takeIf { ManifestParser.ID.matches(it) } ?: return null
        val name = (o.opt("name") as? String)?.takeIf { it.isNotBlank() }?.take(ManifestParser.MAX_NAME_CHARS) ?: return null
        val description = ((o.opt("description") as? String) ?: "").take(ManifestParser.MAX_DESCRIPTION_CHARS)
        return DiscoveredPlugin(owner, repo, id, name, description, o.optInt("stars", 0).coerceAtLeast(0))
    }

    /** A disk that refuses costs only the cache: the answer is still returned. */
    private fun writeState(state: DiskState) {
        val json = JSONObject().put("schema", SCHEMA).put("fetchedAt", state.fetchedAt).put("blockedUntil", state.blockedUntil)
            .put("plugins", JSONArray(state.plugins.map {
                JSONObject().put("owner", it.owner).put("repo", it.repo).put("id", it.id)
                    .put("name", it.name).put("description", it.description).put("stars", it.stars)
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
