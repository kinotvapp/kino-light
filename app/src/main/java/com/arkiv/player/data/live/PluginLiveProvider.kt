package com.arkiv.player.data.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.liveCode
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
 * channel. Low-end TVs must not pay for a plugin call on every screen. A failing `guide` (absent,
 * throwing, timing out) is not asked again for [GUIDE_TTL_MS]: the guide is optional and channels
 * must keep listing. Paging stops after [PluginLiveContract.MAX_PAGES_PER_CATEGORY] pages, on a
 * repeated cursor or on a page with nothing new. One instance lives as long as the plugin's
 * `changeKey()` (see `LiveCatalog`). A channel's wrapped ref is only ever kept here and in the
 * Room channel cache, never in favourites, recents or sync.
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

    private val lock = Mutex()
    /** The whole `liveCategories` answer; its playlists are Task 8's. */
    private var catalogCache: Pair<Long, PluginLiveCatalog>? = null
    private val channelsCache = HashMap<String, Pair<Long, List<LiveChannel>>>()
    /** Inline streams by channel code, as last listed. */
    private val direct = HashMap<String, GatewayPlayable>()
    private val guideCache = HashMap<String, Pair<Long, List<LiveProgram>>>()
    private var guideOffUntil = 0L

    override fun initialCategory(): String? = catalogCache?.second?.categories?.firstOrNull()?.id

    override fun hasGuide(): Boolean = "guide" in plugin.record.exports

    override suspend fun categories(includeAdults: Boolean): List<ProviderCategory> =
        catalog().categories.map { ProviderCategory(it.id, it.title) }

    private suspend fun catalog(): PluginLiveCatalog {
        lock.withLock { catalogCache?.takeIf { clock() < it.first }?.let { return it.second } }
        val out = PluginCalls.callOrThrow(caller, pluginId, name, "liveCategories", "null", PluginLiveContract.CATEGORIES_TIMEOUT_MS)
        // Strict hosts: a declared playlist and its guide are downloaded by the app, never under "any".
        val catalog = PluginOutput.liveCategories(out, plugin.hosts) { log("[$pluginId] $it") }
        if (catalog.categories.isNotEmpty() || catalog.playlists.isNotEmpty()) {
            lock.withLock { catalogCache = (clock() + LIST_TTL_MS) to catalog }
        }
        return catalog
    }

    override suspend fun channels(categoryId: String, force: Boolean): List<LiveChannel> {
        if (!force) lock.withLock { channelsCache[categoryId]?.takeIf { clock() < it.first }?.let { return it.second } }
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
        lock.withLock {
            items.forEach { item ->
                val stream = item.stream
                if (stream != null) direct[item.id] = PluginContentSource.livePlayable(stream) else direct.remove(item.id)
            }
            if (all.isNotEmpty()) channelsCache[categoryId] = (clock() + LIST_TTL_MS) to all
        }
        return all
    }

    override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        val now = clock()
        val out = HashMap<String, List<LiveProgram>>()
        val ask = ArrayList<LiveChannel>()
        lock.withLock {
            channels.filter { it.provider == id }.distinctBy { it.code }.forEach { c ->
                val hit = guideCache[c.code]?.takeIf { now < it.first }
                if (hit != null) out[c.liveCode] = hit.second else ask += c
            }
            if (now < guideOffUntil) return out to emptyList()
        }
        val from = now - GUIDE_BEHIND_MS
        val to = from + PluginLiveContract.MAX_GUIDE_WINDOW_MS
        for (chunk in ask.chunked(PluginLiveContract.MAX_GUIDE_CHANNELS)) {
            val ids = chunk.map { it.code }
            val arg = JSONObject().put("channelIds", JSONArray(ids)).put("from", from).put("to", to).toString()
            val answer = try {
                PluginCalls.callOrThrow(caller, pluginId, name, "guide", arg, PluginLiveContract.GUIDE_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("[$pluginId] guide unavailable for ${GUIDE_TTL_MS / 60_000} min: ${e.message}")
                lock.withLock { guideOffUntil = now + GUIDE_TTL_MS }
                break
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
        // A plugin answers what it has: nothing is worth LiveViewModel's delayed retry (that exists for Xuper).
        return out to emptyList()
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

    private suspend fun directOf(code: String): GatewayPlayable? = lock.withLock { direct[code] }

    private suspend fun inMemory(code: String): LiveChannel? = lock.withLock {
        channelsCache.values.firstNotNullOfOrNull { (_, list) -> list.firstOrNull { it.code == code } }
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
    }
}
