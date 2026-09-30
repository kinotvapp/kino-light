package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.Genre
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.plugin.BackgroundPluginCall
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginCaller
import com.arkiv.player.data.plugin.PluginCalls
import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.data.plugin.PluginContentSource
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginLiveCatalog
import com.arkiv.player.data.plugin.PluginLiveChannelItem
import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PluginOutput
import com.arkiv.player.data.plugin.PluginPlaylist
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.data.plugin.PluginStream
import com.arkiv.player.playback.PluginLiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * An installed plugin's `channels` (apiVersion 3) as a provider of the En vivo module. Every call
 * goes through [caller] (`AppGraph.pluginCaller`: setup gate, runtime pool, per-call timeouts,
 * "No responde" after repeated timeouts). Its answers go through [PluginOutput]. Nothing here
 * talks to the network itself. Playback is the existing plugin-live path ([LiveOpening.Plugin]):
 * a channel with a `ref` resolves through the plugin's `resolve()`, one with an inline `stream`
 * carries it as [PluginLiveChannel.direct] and plays with no plugin call (both present: the stream).
 *
 * Hosts: a channel's inline stream URL is checked against `plugin.liveHosts` (any public host
 * only when the person approved `liveStreamHosts: "any"`); everything else -- the playlist and
 * EPG declarations in `liveCategories`, a stream's side files -- against the strict `plugin.hosts`.
 * A playlist entry's `tvg-logo` is not a stream URL: it follows [PluginOutput.imageUrl] against
 * [liveHosts], exactly like any other image this plugin returns (a poster, a `liveChannels` logo)
 * -- declared hosts don't gate it, only a local address does, unless it is one of the servers the
 * person typed for this plugin.
 *
 * Categories and channel lists are kept [LIST_TTL_MS] in memory and the guide [GUIDE_TTL_MS] per
 * channel. Low-end TVs must not pay for a plugin call on every screen, and one instance serves
 * every screen at once (Home row, TV drawer, the tab): a cold listing asked twice at the same
 * time is ONE plugin call whose result (or failure) both get; a failure is never cached. An
 * inline stream lives exactly as long as the channel list it came in. A failing `guide` (absent,
 * throwing, timing out) is not asked again for [GUIDE_TTL_MS]: the guide is optional and channels
 * must keep listing. Guide calls are background calls ([BackgroundPluginCall]): their timeouts
 * never mark the whole plugin "No responde". One guide pass asks at most
 * [MAX_GUIDE_CHUNKS_PER_PASS] chunks; the rest come back as "ask later". Paging stops after
 * [PluginLiveContract.MAX_PAGES_PER_CATEGORY] pages, on a repeated cursor or on a page with
 * nothing new. One instance lives as long as the plugin's `changeKey()` (see `LiveCatalog`). A
 * channel's wrapped ref is only ever kept here and in the Room channel cache, never in
 * favourites, recents or sync.
 *
 * Playlists: the M3U lists a plugin declares in `liveCategories` are downloaded by the APP through
 * [fetcher] (the STRICT host gate, never `liveStreamHosts: "any"`), kept on disk under [cacheDir]
 * (see [PlaylistSource]) and grouped by [groupPlaylist] after the plugin's own categories, in
 * declaration order, under one budget of [PluginLiveContract.MAX_CHANNELS_PER_PROVIDER] channels
 * and [PluginLiveContract.MAX_CATEGORIES_PER_PROVIDER] categories; each entry's stream URL is
 * checked against `plugin.liveHosts`. When anything was cut, [notice] says so. A playlist channel
 * (code `~<key>.<id>`) plays straight from its entry, or through the plugin's `resolve(<entry url>)`
 * when the playlist says `resolve: true`. Its guide comes from the playlist's XMLTV, matched by
 * `tvg-id`, else by normalised name. Downloads and parses are serialised and single-flight: two
 * screens never download or parse the same playlist twice.
 */
class PluginLiveProvider(
    private val plugin: InstalledPlugin,
    private val caller: PluginCaller,
    /** The Room channel cache row for a code of THIS provider (written by `LiveViewModel`), for [open]. */
    private val cached: suspend (code: String) -> LiveChannelCacheEntity? = { null },
    /** Playlist and EPG downloads: `PluginPlaylistFetcher(PluginStreamHttp.client(base, plugin.hosts))`. */
    private val fetcher: LivePlaylistFetcher = LivePlaylistFetcher { _, _, _ -> throw java.io.IOException("sin descargas") },
    /** The plugin's data dir; playlists are kept under `live/`. Null = memory only. */
    private val cacheDir: java.io.File? = null,
    /** Every plugin's data dir lives here (`plugin-data`): the live caches of all of them share one ceiling. Null = none. */
    private val allCachesRoot: java.io.File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
    /**
     * After the playlists were regrouped: every playlist channel now kept, as channel cache rows
     * (`LiveChannelCacheDao.replacePlaylistRows`), so the cross-provider search never finds one
     * that is gone. A failure here is logged, never a failed listing.
     */
    private val syncCache: suspend (rows: List<LiveChannelCacheEntity>) -> Unit = {},
    /**
     * The plugin's strict hosts NOW, read each time an answer is checked: a host approved
     * reactively during `liveCategories`/`liveChannels` must count for the URLs that call just
     * returned. AppGraph reads the registry; by default, [plugin]'s own snapshot. (Playlist and EPG
     * downloads, and the entries they list, keep this instance's snapshot: the app fetches those,
     * not the plugin, so no prompt is ever raised for them.)
     */
    private val currentHosts: () -> com.arkiv.player.data.plugin.EffectiveHosts = { plugin.hosts },
) : LiveChannelProvider {
    private val pluginId = plugin.id
    /** For playlist entries (app-fetched, never behind a prompt): the snapshot, not re-read per entry. */
    private val liveHosts = plugin.liveHosts
    private val allowDrm = "drm" in plugin.manifest.capabilities
    override val id: String = LiveChannelKeys.pluginProvider(pluginId)
    override val name: String = plugin.manifest.name
    override val color: Long = PluginColors.parse(plugin.manifest.color)

    /** One category's listing: its channels and their inline streams by code, gone together at [expiresAt]. */
    private class ChannelList(val expiresAt: Long, val channels: List<LiveChannel>, val direct: Map<String, GatewayPlayable>)

    private val lock = Mutex()
    /** The whole `liveCategories` answer; its playlists are Task 8's. */
    private var catalogCache: Pair<Long, PluginLiveCatalog>? = null
    private val channelsCache = HashMap<String, ChannelList>()
    /** The plugin categories (not playlist groups) listed at least once by this instance; read lock-free by [hasUnloadedCategories]. */
    private val listedCategories: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Whether `liveCategories` ever answered for this instance. */
    @Volatile private var categoriesKnown = false
    private val guideCache = HashMap<String, Pair<Long, List<LiveProgram>>>()
    private var guideOffUntil = 0L
    /** Listings being fetched, by key: a second asker waits for the same answer. */
    private val inFlight = HashMap<String, CompletableDeferred<Any?>>()
    /** Channel codes a guide pass is asking for right now, and that pass's completion. */
    private val guideInFlight = HashMap<String, CompletableDeferred<Unit>>()

    /** Cancelled by [close]: every public call runs linked to it through [owned]. */
    private val work = Job()

    /**
     * The module dropped this instance (its plugin changed, was switched off or uninstalled):
     * listings, downloads and guide passes still running end with a [CancellationException]
     * (their asker itself is not cancelled; the module already carries the replacement), and
     * every later call is refused the same way, without asking the plugin.
     */
    override fun close() {
        work.cancel(CancellationException("$id closed"))
    }

    /** Runs [block] in the caller's coroutine, cancelled early when this provider is [close]d. */
    private suspend fun <T> owned(block: suspend () -> T): T {
        if (work.isCancelled) throw CancellationException("$id closed")
        return coroutineScope {
            val call = coroutineContext.job
            val handle = work.invokeOnCompletion { call.cancel(CancellationException("$id closed")) }
            try { block() } finally { handle.dispose() }
        }
    }

    /** Declared playlists by cache key (`PlaylistSource.cacheKey`: URL without its query), in declaration order (under [lock]). */
    private val sources = LinkedHashMap<String, PlaylistSource>()
    /** Each playlist's grouping by key, rebuilt when a list or the plugin's categories change (under [lock]). */
    private val groups = LinkedHashMap<String, PlaylistGroups>()
    @Volatile private var pluginCategories: List<ProviderCategory> = emptyList()
    /** The plugin categories and parsed lists (by key) the current [groups] were built from; the same again regroups nothing. */
    private var groupedCategories: List<ProviderCategory>? = null
    private var groupedResults: Map<String, M3uResult?> = emptyMap()
    private var groupedPlaylists: List<PluginPlaylist> = emptyList()
    /** A playlist's parsed guide by key, until its time (under [lock]); null = no guide right now. */
    private val playlistGuides = HashMap<String, Pair<Long, XmltvGuide?>>()
    /** Serialises downloads and parses: a second screen waits and then reuses what the first got. */
    private val playlistLock = Mutex()
    /** Test seam, handed to every [PlaylistSource]: runs between a list's encoding sniff and its read. */
    @Volatile internal var playlistReadHook: (java.io.File) -> Unit = {}
    private val _notice = MutableStateFlow<String?>(null)
    /** "Lista recortada: <kept> de <total> canales" when the caps cut this provider's playlists, else null. */
    override val notice: StateFlow<String?> = _notice

    override fun initialCategory(): String? =
        catalogCache?.second?.categories?.firstOrNull()?.id ?: groups.values.firstNotNullOfOrNull { it.categories.firstOrNull() }?.id

    override fun hasGuide(): Boolean =
        GUIDE_FUNCTION in plugin.record.exports || catalogCache?.second?.playlists?.any { it.epgUrl.isNotEmpty() } == true

    /**
     * [includeAdults] is unused: a plugin's own adult categories and channels never reach here
     * (`PluginOutput` drops every `adult` entry), so there is nothing behind the 18+ lock to show.
     */
    override suspend fun categories(includeAdults: Boolean): List<ProviderCategory> = owned { categoriesNow() }

    private suspend fun categoriesNow(): List<ProviderCategory> {
        val catalog = catalog()
        lock.withLock {
            pluginCategories = catalog.categories.map { ProviderCategory(it.id, it.title, Genre.of(it.genre, it.title)) }
            categoriesKnown = true
            reconcile(catalog.playlists)
        }
        ensurePlaylists(null)
        return lock.withLock {
            (pluginCategories + groups.values.flatMap { it.categories }).take(PluginLiveContract.MAX_CATEGORIES_PER_PROVIDER)
        }
    }

    /**
     * [sources] follow the declared [playlists] by cache key: a rotated token in a URL keeps its
     * source (and its saved copy and parsed list); the missing ones (and their groups and guides)
     * are dropped, and their files deleted by the next [ensurePlaylists]. Two declarations with
     * the same key (same URL but for the query) are one playlist: the first wins. Under [lock].
     */
    private fun reconcile(playlists: List<PluginPlaylist>) {
        val kept = LinkedHashMap<String, PlaylistSource>()
        playlists.forEach { pl ->
            val key = PlaylistSource.cacheKey(pl.url)
            if (key in kept) { log("[$pluginId] playlist ${pl.url.take(100)}: same list as an earlier one but for its query, ignored"); return@forEach }
            kept[key] = sources[key]?.takeIf { it.adopt(pl) } ?: PlaylistSource(
                pl, fetcher, cacheDir, clock, { log("[$pluginId] $it") },
                entryAllowed = { PluginOutput.allowsUrl(it, liveHosts) },
                allCachesRoot = allCachesRoot,
            ).also { s -> s.beforeRead = { f -> playlistReadHook(f) } }
        }
        val keys = kept.keys
        sources.clear()
        sources.putAll(kept)
        groups.keys.retainAll(keys)
        playlistGuides.keys.retainAll(keys)
    }

    /**
     * Brings every playlist's grouping up to date, downloading or re-reading only what is due
     * ([forceKey]'s playlist is downloaded again), then regroups them in order under one budget.
     * Single-flight per [forceKey]; serialised by [playlistLock].
     */
    private suspend fun ensurePlaylists(forceKey: String?) {
        shared(PLAYLISTS_KEY + forceKey.orEmpty()) {
            playlistLock.withLock {
                val list = lock.withLock { sources.values.toList() }
                // Files of playlists no longer declared go, and the live/ dir stays within its budget.
                cacheDir?.let { dir ->
                    val keys = list.map { it.key }.toSet()
                    withContext(Dispatchers.IO) {
                        PlaylistSource.pruneLiveDir(dir, keys, PlaylistSource.MAX_LIVE_CACHE_BYTES) { log("[$pluginId] $it") }
                    }
                }
                // Each list gets only the channels the earlier ones left: never 10 × 5000 parsed entries held.
                var left = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER
                val results = list.map { s ->
                    val r = s.entries(force = s.key == forceKey, maxEntries = left)
                    left = (left - (r?.entries?.size ?: 0)).coerceAtLeast(0)
                    s to r
                }
                val cats = lock.withLock { pluginCategories }
                val byKey = LinkedHashMap<String, M3uResult?>().apply { results.forEach { (s, r) -> put(s.key, r) } }
                val declared = list.map { it.playlist }
                val same = lock.withLock {
                    cats == groupedCategories && declared == groupedPlaylists && byKey.keys.toList() == groupedResults.keys.toList() &&
                        byKey.all { (k, r) -> r === groupedResults[k] }
                }
                if (same) return@withLock
                val built = withContext(Dispatchers.Default) { regroup(cats, results) }
                lock.withLock {
                    // A list parsed again may have other entries: its guide is matched again.
                    byKey.forEach { (k, r) -> if (r !== groupedResults[k]) playlistGuides.remove(k) }
                    groups.clear()
                    groups.putAll(built)
                    groupedCategories = cats
                    groupedPlaylists = declared
                    groupedResults = byKey
                }
                syncPlaylistRows(built.values)
            }
        }
    }

    private suspend fun syncPlaylistRows(built: Collection<PlaylistGroups>) {
        val now = clock()
        val rows = built.flatMap { g ->
            g.byCategory.flatMap { (category, channels) ->
                channels.map { LiveChannelCacheEntity(it.code, category, it.name, it.number, it.logo, now, provider = id, ref = it.ref) }
            }
        }
        try {
            syncCache(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[$pluginId] channel cache not updated: ${e.message}")
        }
    }

    private fun regroup(cats: List<ProviderCategory>, results: List<Pair<PlaylistSource, M3uResult?>>): Map<String, PlaylistGroups> {
        val out = LinkedHashMap<String, PlaylistGroups>()
        var categoriesLeft = PluginLiveContract.MAX_CATEGORIES_PER_PROVIDER - cats.size
        var channelsLeft = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER
        for ((source, result) in results) {
            if (result == null) continue
            val g = groupPlaylist(
                result, source.key, id, pluginId, source.playlist, liveHosts,
                entryAllowed = { PluginOutput.allowsUrl(it, liveHosts) },
                maxCategories = categoriesLeft, maxChannels = channelsLeft,
            )
            out[source.key] = g
            categoriesLeft -= g.categories.size
            channelsLeft -= g.kept
            if (g.skipped > 0 || g.hidden > 0) log("[$pluginId] playlist ${source.key}: ${g.kept} kept, ${g.skipped} skipped, ${g.hidden} hidden")
        }
        _notice.value = trimNotice(out.values)
        return out
    }

    private suspend fun catalog(): PluginLiveCatalog {
        lock.withLock { freshCatalog()?.let { return it } }
        return shared(CATALOG_KEY) {
            lock.withLock { freshCatalog()?.let { return@shared it } }
            val out = PluginCalls.callOrThrow(caller, pluginId, name, "liveCategories", "null", PluginLiveContract.CATEGORIES_TIMEOUT_MS)
            // Strict hosts: a declared playlist and its guide are downloaded by the app, never under "any".
            val catalog = PluginOutput.liveCategories(out, currentHosts()) { log("[$pluginId] $it") }
            if (catalog.categories.isNotEmpty() || catalog.playlists.isNotEmpty()) {
                lock.withLock { catalogCache = (clock() + LIST_TTL_MS) to catalog }
            }
            catalog
        }
    }

    private fun freshCatalog(): PluginLiveCatalog? = catalogCache?.takeIf { clock() < it.first }?.second

    override suspend fun channels(categoryId: String, force: Boolean): List<LiveChannel> = owned { channelsNow(categoryId, force) }

    private suspend fun channelsNow(categoryId: String, force: Boolean): List<LiveChannel> {
        if (categoryId.startsWith(PLAYLIST_CATEGORY_PREFIX)) return playlistChannels(categoryId, force)
        if (!force) lock.withLock { freshList(categoryId)?.let { return it.channels } }
        return shared(CHANNELS_KEY + categoryId) {
            if (!force) lock.withLock { freshList(categoryId)?.let { return@shared it.channels } }
            fetchChannels(categoryId)
        }
    }

    /** A playlist category's channels; [force] ("Recargar") downloads that playlist again. */
    private suspend fun playlistChannels(categoryId: String, force: Boolean): List<LiveChannel> {
        val key = categoryId.split(':').getOrNull(1).orEmpty()
        if (force) {
            if (lock.withLock { sources.isEmpty() }) categories(includeAdults = false)
            ensurePlaylists(key)
        } else if (lock.withLock { key !in groups }) {
            categories(includeAdults = false)
        }
        return lock.withLock { groups[key]?.byCategory?.get(categoryId).orEmpty() }
    }

    private fun freshList(categoryId: String): ChannelList? {
        val entry = channelsCache[categoryId] ?: return null
        if (clock() < entry.expiresAt) return entry
        channelsCache.remove(categoryId)
        return null
    }

    private suspend fun fetchChannels(categoryId: String): List<LiveChannel> {
        val items = ArrayList<PluginLiveChannelItem>()
        val seenIds = HashSet<String>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null
        for (page in 1..PluginLiveContract.MAX_PAGES_PER_CATEGORY) {
            val arg = JSONObject().put("categoryId", categoryId).put("cursor", cursor ?: JSONObject.NULL).toString()
            val out = PluginCalls.callOrThrow(caller, pluginId, name, "liveChannels", arg, PluginLiveContract.CHANNELS_TIMEOUT_MS)
            val p = PluginOutput.liveChannels(out, currentHosts().copy(anyPublicLiveHost = plugin.record.liveStreamHostsAny || plugin.record.streamHostsAny), allowDrm) { log("[$pluginId] $it") }
            val fresh = p.items.filter { seenIds.add(it.id) }
            items += fresh
            val next = p.next ?: break
            // A page with nothing new, or a cursor already used, is a loop: stop instead of spending the cap.
            if (fresh.isEmpty() || !seenCursors.add(next)) break
            if (page == PluginLiveContract.MAX_PAGES_PER_CATEGORY) log("[$pluginId] liveChannels: stopped after $page pages")
            cursor = next
        }
        val all = items.map { it.toChannel() }
        val direct = items.mapNotNull { item -> item.stream?.let { item.id to PluginContentSource.livePlayable(it) } }.toMap()
        lock.withLock {
            listedCategories += categoryId
            if (all.isNotEmpty()) channelsCache[categoryId] = ChannelList(clock() + LIST_TTL_MS, all, direct)
            else channelsCache.remove(categoryId)
        }
        return all
    }

    /**
     * Runs [fetch] once for everyone asking [key] at the same time: the first asker fetches, the
     * others wait for its value or its failure. Nothing is kept once it ends, so the next call after
     * a failure asks again. A cancelled first asker hands the fetch to a waiter still interested.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, fetch: suspend () -> T): T {
        while (true) {
            val (flight, owner) = lock.withLock {
                inFlight[key]?.let { it to false } ?: (CompletableDeferred<Any?>().also { inFlight[key] = it } to true)
            }
            if (!owner) {
                try {
                    return flight.await() as T
                } catch (e: CancellationException) {
                    // The fetcher was cancelled, not us (else ensureActive throws): take it over.
                    currentCoroutineContext().ensureActive()
                    lock.withLock { if (inFlight[key] === flight) inFlight.remove(key) }
                    continue
                }
            }
            try {
                return fetch().also { flight.complete(it) }
            } catch (e: CancellationException) {
                flight.cancel()
                throw e
            } catch (e: Throwable) {
                flight.completeExceptionally(e)
                throw e
            } finally {
                withContext(NonCancellable) { lock.withLock { if (inFlight[key] === flight) inFlight.remove(key) } }
            }
        }
    }

    /**
     * Every parsed playlist entry that was kept and every listed category's channels, straight from
     * memory. Not [owned]: it asks nothing, so a closed instance just answers what it had.
     */
    override suspend fun knownChannels(): List<LiveChannel> = lock.withLock {
        groups.values.flatMap { g -> g.byCategory.values.flatten() } + channelsCache.values.flatMap { it.channels }
    }.distinctBy { it.code }

    /** True until `liveCategories` answered and each of the plugin's own categories was listed once. */
    override fun hasUnloadedCategories(): Boolean =
        !categoriesKnown || pluginCategories.any { it.id !in listedCategories }

    override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> = owned { guideNow(channels) }

    private suspend fun guideNow(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        val mine = channels.filter { it.provider == id }
        val (fromPlaylists, fromPlugin) = mine.partition { it.code.startsWith(PluginLiveContract.RESERVED_ID_PREFIX) }
        val (out, later) = when {
            fromPlugin.isEmpty() -> HashMap<String, List<LiveProgram>>() to emptyList()
            // No `guide` export recorded: never asked; its channels simply have no programmes.
            GUIDE_FUNCTION !in plugin.record.exports -> fromPlugin.associateTo(HashMap()) { it.liveCode to emptyList<LiveProgram>() } to emptyList()
            else -> pluginGuide(fromPlugin)
        }
        if (fromPlaylists.isEmpty()) return out to later
        val all = HashMap(out)
        all.putAll(playlistGuide(fromPlaylists))
        return all to later
    }

    /**
     * Playlist channels' programmes from their playlist's XMLTV, parsed once per [GUIDE_TTL_MS] for
     * all the list's kept entries (a failure is not retried before then). Matched by `tvg-id`, else
     * by normalised name against the guide's display names. Every asked channel gets an answer.
     */
    private suspend fun playlistGuide(channels: List<LiveChannel>): Map<String, List<LiveProgram>> {
        val out = HashMap<String, List<LiveProgram>>()
        for ((key, asked) in channels.distinctBy { it.code }.groupBy { playlistKey(it.code) }) {
            if (lock.withLock { key !in groups }) runCatching { categories(includeAdults = false) }
                .onFailure { if (it is CancellationException) throw it }
            val g = playlistGuideOf(key)
            val entries = lock.withLock { groups[key]?.entries }.orEmpty()
            val nameToId = HashMap<String, String>()
            g?.displayNames?.forEach { (gid, names) -> names.forEach { nameToId.putIfAbsent(XmltvParser.normaliseName(it), gid) } }
            for (c in asked) {
                val e = entries[c.code]
                val programmes = if (g == null || e == null) emptyList()
                else g.programmes[e.tvgId] ?: nameToId[XmltvParser.normaliseName(e.name)]?.let { g.programmes[it] }.orEmpty()
                out[c.liveCode] = programmes.map { LiveProgram(it.title, it.startMs / 1000, it.endMs / 1000, it.description) }
            }
        }
        return out
    }

    private suspend fun playlistGuideOf(key: String): XmltvGuide? {
        lock.withLock { playlistGuides[key]?.takeIf { clock() < it.first }?.let { return it.second } }
        return shared(EPG_KEY + key) {
            lock.withLock { playlistGuides[key]?.takeIf { clock() < it.first }?.let { return@shared it.second } }
            val (source, entries) = lock.withLock { sources.values.firstOrNull { it.key == key } to groups[key]?.entries?.values }
            if (source == null || entries == null) return@shared null
            val now = clock()
            val ids = entries.mapNotNullTo(HashSet()) { e -> e.tvgId.takeIf { it.isNotBlank() } }
            val names = entries.mapNotNullTo(HashSet()) { e -> XmltvParser.normaliseName(e.name).takeIf { it.isNotEmpty() } }
            val from = now - GUIDE_BEHIND_MS
            // Not under playlistLock: a 50 MB guide parse must not hold up the channel lists. Single-flight per key already.
            val guide = source.guide(ids, names, from, from + PluginLiveContract.MAX_GUIDE_WINDOW_MS, force = false)
            lock.withLock { playlistGuides[key] = (now + GUIDE_TTL_MS) to guide }
            guide
        }
    }

    private suspend fun pluginGuide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        val now = clock()
        val out = HashMap<String, List<LiveProgram>>()
        val ask: List<LiveChannel>
        val waiting = ArrayList<Pair<LiveChannel, CompletableDeferred<Unit>>>()
        val later: List<String>
        val pass = CompletableDeferred<Unit>()
        lock.withLock {
            val missing = ArrayList<LiveChannel>()
            channels.distinctBy { it.code }.forEach { c ->
                val hit = guideCache[c.code]?.takeIf { now < it.first }
                val running = guideInFlight[c.code]
                when {
                    hit != null -> out[c.liveCode] = hit.second
                    running != null -> waiting += c to running
                    else -> missing += c
                }
            }
            if (now < guideOffUntil) return out to emptyList()
            val cap = MAX_GUIDE_CHUNKS_PER_PASS * PluginLiveContract.MAX_GUIDE_CHANNELS
            ask = missing.take(cap)
            later = missing.drop(cap).map { it.liveCode }
            ask.forEach { guideInFlight[it.code] = pass }
        }
        try {
            askGuide(ask, now, out)
        } finally {
            pass.complete(Unit)
            withContext(NonCancellable) { lock.withLock { ask.forEach { if (guideInFlight[it.code] === pass) guideInFlight.remove(it.code) } } }
        }
        // Another pass was already asking for these: take what it got (nothing, if it failed).
        waiting.map { it.second }.distinct().forEach { it.await() }
        lock.withLock {
            waiting.forEach { (c, _) -> guideCache[c.code]?.takeIf { now < it.first }?.let { out[c.liveCode] = it.second } }
        }
        // Beyond this pass's cap: worth LiveViewModel's delayed retry. What the plugin answered is final.
        return out to later
    }

    private suspend fun askGuide(ask: List<LiveChannel>, now: Long, out: MutableMap<String, List<LiveProgram>>) {
        val from = now - GUIDE_BEHIND_MS
        val to = from + PluginLiveContract.MAX_GUIDE_WINDOW_MS
        for (chunk in ask.chunked(PluginLiveContract.MAX_GUIDE_CHANNELS)) {
            val ids = chunk.map { it.code }
            val arg = JSONObject().put("channelIds", JSONArray(ids)).put("from", from).put("to", to).toString()
            val answer = try {
                // Background: the guide is optional, its timeouts must not switch the plugin off for search, Home and play.
                withContext(BackgroundPluginCall) {
                    PluginCalls.callOrThrow(caller, pluginId, name, GUIDE_FUNCTION, arg, PluginLiveContract.GUIDE_TIMEOUT_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("[$pluginId] guide unavailable for ${GUIDE_TTL_MS / 60_000} min: ${e.message}")
                lock.withLock { guideOffUntil = now + GUIDE_TTL_MS }
                return
            }
            val byId = PluginOutput.guide(answer, ids.toSet(), from, to) { log("[$pluginId] $it") }.groupBy { it.channelId }
            lock.withLock {
                chunk.forEach { c ->
                    val programs = byId[c.code].orEmpty().map { LiveProgram(it.title, it.startMs / 1000, it.endMs / 1000, it.description) }
                    guideCache[c.code] = (now + GUIDE_TTL_MS) to programs
                    out[c.liveCode] = programs
                }
            }
        }
    }

    override suspend fun open(channel: LiveChannel): LiveOpening = owned { openNow(channel) }

    private suspend fun openNow(channel: LiveChannel): LiveOpening {
        require(channel.provider == id) { "channel ${channel.liveCode} is not $id's" }
        if (channel.code.startsWith(PluginLiveContract.RESERVED_ID_PREFIX)) return openPlaylistEntry(channel)
        directOf(channel.code)?.let { return opening(channel, inMemory(channel.code), it) }
        val known = channel.takeIf { it.ref != null }
            ?: inMemory(channel.code)?.takeIf { it.ref != null }
            ?: cached(channel.code)?.takeIf { it.ref != null }?.let { LiveChannel(it.code, it.nombre, it.numero, it.logo, provider = id, ref = it.ref) }
            ?: rescan(channel.code)
            ?: throw GatewayException("No se encontró el canal en $name")
        // A rescan may have found it as an inline-stream channel.
        directOf(channel.code)?.let { return opening(channel, known, it) }
        if (known.ref == null) throw GatewayException("No se encontró el canal en $name")
        return opening(channel, known, null)
    }

    /** A playlist channel: straight from its entry, or through the plugin's `resolve(<entry url>)` for a `resolve` playlist. */
    private suspend fun openPlaylistEntry(channel: LiveChannel): LiveOpening {
        val key = playlistKey(channel.code)
        // Known only by code (after a restart, from a favourite): list the playlists first.
        if (lock.withLock { key !in groups }) categories(includeAdults = false)
        val (entry, source) = lock.withLock { groups[key]?.entries?.get(channel.code) to sources.values.firstOrNull { it.key == key } }
        if (entry == null || source == null) throw GatewayException("No se encontró el canal en $name")
        val known = LiveChannel(channel.code, entry.name, entry.number, PluginOutput.imageUrl(entry.logo, liveHosts).ifEmpty { null }, provider = id)
        if (source.playlist.resolve) {
            return opening(channel, known.copy(ref = PluginRef(pluginId, channel.code, PluginRef.LIVE, entry.url).encode()), null)
        }
        val entryHeaders = PluginOutput.headersOf(JSONObject(entry.headers as Map<*, *>))
        // The list's streamHeaders (a User-Agent, a Referer) under the entry's own: a header the entry names wins,
        // whatever its spelling ("user-agent" replaces "User-Agent").
        val headers = LinkedHashMap<String, String>()
        source.playlist.streamHeaders.forEach { (k, v) -> if (entryHeaders.keys.none { it.equals(k, ignoreCase = true) }) headers[k] = v }
        headers.putAll(entryHeaders)
        val stream = PluginStream(url = entry.url, headers = headers)
        return opening(channel, known, PluginContentSource.livePlayable(stream))
    }

    private fun playlistKey(code: String) = code.substring(PluginLiveContract.RESERVED_ID_PREFIX.length).substringBefore('.')

    /** [channel] as the player takes it: [playable] set plays directly, else [known]'s ref resolves. */
    private fun opening(channel: LiveChannel, known: LiveChannel?, playable: GatewayPlayable?): LiveOpening {
        // A channel rebuilt from a bare live code (companion, deep link) has its code as name: prefer the listed one.
        val title = channel.name.takeIf { it.isNotBlank() && it != channel.code } ?: known?.name ?: channel.name
        return LiveOpening.Plugin(
            PluginLiveChannel(
                episodeId = PluginIds.liveEpisodeId(pluginId, channel.code),
                pluginId = pluginId,
                ref = if (playable != null) "" else known!!.ref!!,
                title = title,
                logo = (channel.logo ?: known?.logo).orEmpty(),
                direct = playable,
            ),
            adult = channel.adult || known?.adult == true,
        )
    }

    private suspend fun directOf(code: String): GatewayPlayable? = lock.withLock { liveLists().firstNotNullOfOrNull { it.direct[code] } }

    private suspend fun inMemory(code: String): LiveChannel? = lock.withLock {
        liveLists().firstNotNullOfOrNull { list -> list.channels.firstOrNull { it.code == code } }
    }

    /** The channel lists still within their TTL; expired ones (and their inline streams) are dropped. Under [lock]. */
    private fun liveLists(): Collection<ChannelList> {
        val now = clock()
        channelsCache.values.removeAll { now >= it.expiresAt }
        return channelsCache.values
    }

    /** Last resort for a channel known only by code: list at most [MAX_RESCAN_CATEGORIES] categories. */
    private suspend fun rescan(code: String): LiveChannel? {
        for (category in categories(includeAdults = false).take(MAX_RESCAN_CATEGORIES)) {
            channels(category.id).firstOrNull { it.code == code }?.let { return it }
        }
        return null
    }

    /** A stream-only channel carries no ref: reopening it after a restart rescans and finds its stream again. */
    private fun PluginLiveChannelItem.toChannel() = LiveChannel(
        code = id, name = title, number = number, logo = logo.ifBlank { null },
        provider = this@PluginLiveProvider.id,
        ref = ref.takeIf { it.isNotEmpty() }?.let { PluginRef(pluginId, id, PluginRef.LIVE, it).encode() },
    )

    companion object {
        const val LIST_TTL_MS = 60 * 60 * 1000L
        const val GUIDE_TTL_MS = 30 * 60 * 1000L
        /** The guide window starts this far back, so the programme on air now is in it. */
        const val GUIDE_BEHIND_MS = 2 * 60 * 60 * 1000L
        const val MAX_RESCAN_CATEGORIES = 10
        /** Chunks of [PluginLiveContract.MAX_GUIDE_CHANNELS] one guide pass asks at most (200 channels). */
        const val MAX_GUIDE_CHUNKS_PER_PASS = 4
        /** The optional plugin export for programmes; asked only when the installed record lists it. */
        private const val GUIDE_FUNCTION = "guide"
        private const val CATALOG_KEY = "catalog"
        private const val CHANNELS_KEY = "channels:"
        private const val PLAYLISTS_KEY = "playlists:"
        private const val EPG_KEY = "epg:"
        /** Category ids of playlist groups (`pl:<key>:<hash>`); a plugin's own ids can't hold `:`. */
        private const val PLAYLIST_CATEGORY_PREFIX = "pl:"
    }
}
