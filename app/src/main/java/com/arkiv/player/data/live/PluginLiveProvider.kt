package com.arkiv.player.data.live

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
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.playback.PluginLiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 */
class PluginLiveProvider(
    private val plugin: InstalledPlugin,
    private val caller: PluginCaller,
    /** The Room channel cache row for a code of THIS provider (written by `LiveViewModel`), for [open]. */
    private val cached: suspend (code: String) -> LiveChannelCacheEntity? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
) : LiveChannelProvider {
    private val pluginId = plugin.id
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
    private val guideCache = HashMap<String, Pair<Long, List<LiveProgram>>>()
    private var guideOffUntil = 0L
    /** Listings being fetched, by key: a second asker waits for the same answer. */
    private val inFlight = HashMap<String, CompletableDeferred<Any?>>()
    /** Channel codes a guide pass is asking for right now, and that pass's completion. */
    private val guideInFlight = HashMap<String, CompletableDeferred<Unit>>()

    override fun initialCategory(): String? = catalogCache?.second?.categories?.firstOrNull()?.id

    override fun hasGuide(): Boolean = "guide" in plugin.record.exports

    /**
     * [includeAdults] is unused: a plugin's own adult categories and channels never reach here
     * (`PluginOutput` drops every `adult` entry), so there is nothing behind the 18+ lock to show.
     */
    override suspend fun categories(includeAdults: Boolean): List<ProviderCategory> =
        catalog().categories.map { ProviderCategory(it.id, it.title) }

    private suspend fun catalog(): PluginLiveCatalog {
        lock.withLock { freshCatalog()?.let { return it } }
        return shared(CATALOG_KEY) {
            lock.withLock { freshCatalog()?.let { return@shared it } }
            val out = PluginCalls.callOrThrow(caller, pluginId, name, "liveCategories", "null", PluginLiveContract.CATEGORIES_TIMEOUT_MS)
            // Strict hosts: a declared playlist and its guide are downloaded by the app, never under "any".
            val catalog = PluginOutput.liveCategories(out, plugin.hosts) { log("[$pluginId] $it") }
            if (catalog.categories.isNotEmpty() || catalog.playlists.isNotEmpty()) {
                lock.withLock { catalogCache = (clock() + LIST_TTL_MS) to catalog }
            }
            catalog
        }
    }

    private fun freshCatalog(): PluginLiveCatalog? = catalogCache?.takeIf { clock() < it.first }?.second

    override suspend fun channels(categoryId: String, force: Boolean): List<LiveChannel> {
        if (!force) lock.withLock { freshList(categoryId)?.let { return it.channels } }
        return shared(CHANNELS_KEY + categoryId) {
            if (!force) lock.withLock { freshList(categoryId)?.let { return@shared it.channels } }
            fetchChannels(categoryId)
        }
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
            val p = PluginOutput.liveChannels(out, liveHosts, allowDrm) { log("[$pluginId] $it") }
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

    override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        val now = clock()
        val out = HashMap<String, List<LiveProgram>>()
        val ask: List<LiveChannel>
        val waiting = ArrayList<Pair<LiveChannel, CompletableDeferred<Unit>>>()
        val later: List<String>
        val pass = CompletableDeferred<Unit>()
        lock.withLock {
            val missing = ArrayList<LiveChannel>()
            channels.filter { it.provider == id }.distinctBy { it.code }.forEach { c ->
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
                    PluginCalls.callOrThrow(caller, pluginId, name, "guide", arg, PluginLiveContract.GUIDE_TIMEOUT_MS)
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

    override suspend fun open(channel: LiveChannel): LiveOpening {
        require(channel.provider == id) { "channel ${channel.liveCode} is not $id's" }
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
        private const val CATALOG_KEY = "catalog"
        private const val CHANNELS_KEY = "channels:"
    }
}
