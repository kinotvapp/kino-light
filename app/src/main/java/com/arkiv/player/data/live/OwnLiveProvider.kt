package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.Genre
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginContentSource
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PluginPlaylist
import com.arkiv.player.data.plugin.PluginStream
import com.arkiv.player.playback.PluginLiveChannel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Mis canales" ([OwnLive]): the sources the person typed, as a provider of the En vivo module.
 *
 * A CHANNEL source is one channel whose code IS the source id (the same on every linked device). A
 * PLAYLIST source goes through the same [PlaylistSource] + [groupPlaylist] plugins use (disk copy,
 * refresh hours, size/heap budgets, one parse at a time, adult groups dropped), with codes
 * `~<list key>.<entry>` that depend only on the list's URL-without-query and the entry, so a
 * reordered or refreshed list never moves a favourite. Entries whose address fails
 * [OwnSourceValidator] (private host, non-http) are dropped like plugin entries on an undeclared host.
 *
 * Nothing here reaches the network except through [fetcher] (built on the gated own-hosts client).
 * A stream opens as [LiveOpening.Plugin] with the address inline (`direct`), like a plugin playlist
 * entry, so it plays with no plugin call. The listing is rebuilt when [sources] changes (compared by
 * id and clock) or on `force`; downloads and parses are serialised under [lock].
 */
internal class OwnLiveProvider(
    private val sources: suspend () -> List<OwnLiveSourceEntity>,
    private val fetcher: LivePlaylistFetcher,
    private val cacheDir: File?,
    private val allCachesRoot: File?,
    private val syncCache: suspend (List<LiveChannelCacheEntity>) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.w("KinoOwnLive", it) },
) : LiveChannelProvider {
    override val id: String = OwnLive.PROVIDER
    override val name: String = OwnLive.NAME
    override val color: Long = OwnLive.COLOR

    private class Snapshot(
        val builtAt: Long,
        val signature: List<Pair<String, Long>>,
        val categories: List<ProviderCategory>,
        val byCategory: Map<String, List<LiveChannel>>,
        val singles: Map<String, OwnLiveSourceEntity>,
        val entries: Map<String, M3uEntry>,
        val playlistOf: Map<String, OwnLiveSourceEntity>,
    )

    /** Nothing that reaches the log may carry an address: a list's URL holds its account and password. */
    private val safeLog: (String) -> Unit = { log(redactUrls(it)) }

    private val lock = Mutex()
    private val playlistSources = HashMap<String, PlaylistSource>()
    private val guides = HashMap<String, Pair<Long, XmltvGuide?>>()
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var guideAvailable = false
    private val _notice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> get() = _notice

    override fun initialCategory(): String? = snapshot?.categories?.firstOrNull()?.id
    override fun hasGuide(): Boolean = guideAvailable
    override fun hasUnloadedCategories(): Boolean = snapshot == null

    override suspend fun categories(includeAdults: Boolean): List<ProviderCategory> = build(force = false).categories

    override suspend fun channels(categoryId: String, force: Boolean): List<LiveChannel> =
        build(force).byCategory[categoryId].orEmpty()

    override suspend fun knownChannels(): List<LiveChannel> = snapshot?.byCategory?.values?.flatten().orEmpty()

    override suspend fun open(channel: LiveChannel): LiveOpening {
        val snap = build(force = false)
        val single = snap.singles[channel.code]
        val entry = snap.entries[channel.code]
        val owner = single ?: snap.playlistOf[channel.code]
        val url = single?.url ?: entry?.url ?: throw GatewayException("No se encontró el canal en $name")
        val headers = LinkedHashMap<String, String>().apply {
            owner?.userAgent?.let { put("User-Agent", it) }
            owner?.referer?.let { put("Referer", it) }
            entry?.headers?.let { putAll(it) }
        }
        val title = when {
            single != null -> single.name
            entry != null -> entry.name
            else -> channel.name
        }
        val logo = channel.logo ?: single?.logo
        // Only a playlist entry carries a KODIPROP ClearKey (M3uParser); a single manually-added
        // channel has no such field to read.
        val drm = entry?.drmKey?.takeIf { it.isNotEmpty() }
            ?.let { com.arkiv.player.data.plugin.PluginDrm(clearKeyId = entry.drmKeyId, clearKey = it) }
        val playable = PluginContentSource.livePlayable(PluginStream(url = url, headers = headers, drm = drm))
        return LiveOpening.Plugin(
            PluginLiveChannel(
                episodeId = PluginIds.liveEpisodeId(OwnLive.PLUGIN_ID, channel.code),
                pluginId = OwnLive.PLUGIN_ID,
                ref = "",
                title = title,
                logo = logo.orEmpty(),
                direct = playable,
            ),
            adult = channel.adult,
        )
    }

    override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> {
        val snap = build(force = false)
        val out = HashMap<String, List<LiveProgram>>()
        val mine = channels.filter { it.provider == id }.distinctBy { it.code }
        for ((sourceId, asked) in mine.groupBy { snap.playlistOf[it.code]?.id }) {
            val guide = sourceId?.let { guideOf(it, asked.mapNotNull { c -> snap.entries[c.code] }) }
            val nameToId = HashMap<String, String>()
            guide?.displayNames?.forEach { (gid, names) -> names.forEach { nameToId.putIfAbsent(XmltvParser.normaliseName(it), gid) } }
            for (c in asked) {
                val e = snap.entries[c.code]
                val programmes = if (guide == null || e == null) emptyList()
                else guide.programmes[e.tvgId] ?: nameToId[XmltvParser.normaliseName(e.name)]?.let { guide.programmes[it] }.orEmpty()
                out[c.liveCode] = programmes.map { LiveProgram(it.title, it.startMs / 1000, it.endMs / 1000, it.description) }
            }
        }
        return out to emptyList()
    }

    private suspend fun guideOf(sourceId: String, entries: List<M3uEntry>): XmltvGuide? {
        val source = lock.withLock {
            guides[sourceId]?.takeIf { clock() < it.first }?.let { return it.second }
            playlistSources[sourceId]
        }
        if (source == null || source.playlist.epgUrl.isBlank()) return null
        val now = clock()
        val ids = entries.mapNotNullTo(HashSet()) { it.tvgId.takeIf { id -> id.isNotBlank() } }
        val names = entries.mapNotNullTo(HashSet()) { XmltvParser.normaliseName(it.name).takeIf { n -> n.isNotEmpty() } }
        val from = now - GUIDE_BEHIND_MS
        val guide = try {
            source.guide(ids, names, from, from + PluginLiveContract.MAX_GUIDE_WINDOW_MS, force = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeLog("guide of $sourceId failed: ${e.message}")
            null
        }
        lock.withLock { guides[sourceId] = (now + GUIDE_TTL_MS) to guide }
        return guide
    }

    private fun entryAllowed(url: String) = OwnSourceValidator.checkUrl(url) is OwnUrlCheck.Ok

    private fun headersOf(s: OwnLiveSourceEntity): Map<String, String> = buildMap {
        s.userAgent?.let { put("User-Agent", it) }
        s.referer?.let { put("Referer", it) }
    }

    private suspend fun build(force: Boolean): Snapshot = lock.withLock {
        val list = sources()
        val signature = list.map { it.id to it.updatedAt }
        snapshot?.takeIf { !force && it.signature == signature && clock() - it.builtAt < SNAPSHOT_TTL_MS }?.let { return@withLock it }

        val singles = list.filter { it.kind == "CHANNEL" }
        val lists = list.filter { it.kind == "PLAYLIST" }
        val categories = ArrayList<ProviderCategory>()
        val byCategory = LinkedHashMap<String, List<LiveChannel>>()

        singles.groupBy { it.groupName.orEmpty().trim() }.forEach { (group, items) ->
            val categoryId = "own:" + sha1Hex(group).take(10)
            categories += ProviderCategory(categoryId, group.ifEmpty { "Canales sueltos" }, Genre.infer(group))
            byCategory[categoryId] = items.mapIndexed { i, s -> LiveChannel(s.id, s.name, i + 1, s.logo, provider = id) }
        }

        val listIds = lists.map { it.id }.toSet()
        playlistSources.keys.retainAll(listIds)
        guides.keys.retainAll(listIds)
        var categoriesLeft = PluginLiveContract.MAX_CATEGORIES_PER_PROVIDER - categories.size
        var channelsLeft = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER - singles.size
        val built = ArrayList<PlaylistGroups>()
        val entries = HashMap<String, M3uEntry>()
        val playlistOf = HashMap<String, OwnLiveSourceEntity>()
        for (src in lists) {
            val declared = PluginPlaylist(
                url = src.url,
                headers = headersOf(src),
                epgUrl = src.epgUrl.orEmpty(),
                refreshHours = src.refreshHours.takeIf { it > 0 } ?: PluginLiveContract.DEFAULT_REFRESH_HOURS,
            )
            // `adopt` says false when the address moved to another list key: that is a different list, so a fresh
            // source (and no guide of the old one), not the old one under a new declaration.
            val source = playlistSources[src.id]?.takeIf { it.adopt(declared) } ?: PlaylistSource(
                declared, fetcher, cacheDir, clock, safeLog, entryAllowed = ::entryAllowed, allCachesRoot = allCachesRoot,
            ).also {
                playlistSources[src.id] = it
                guides.remove(src.id)
            }
            if (categoriesLeft <= 0 || channelsLeft <= 0) break
            val result = try {
                source.entries(force, maxEntries = channelsLeft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                safeLog("playlist ${src.id} failed: ${e.message}")
                null
            } ?: continue
            val g = groupPlaylist(
                result, source.key, id, OwnLive.PLUGIN_ID, source.playlist, EffectiveHosts(emptyList()),
                ::entryAllowed, categoriesLeft, channelsLeft,
            )
            categoriesLeft -= g.categories.size
            channelsLeft -= g.kept
            built += g
            g.categories.forEach { c ->
                categories += if (lists.size > 1) c.copy(name = "${src.name} · ${c.name}") else c
            }
            byCategory.putAll(g.byCategory)
            entries.putAll(g.entries)
            g.entries.keys.forEach { playlistOf[it] = src }
        }
        guideAvailable = lists.any { !it.epgUrl.isNullOrBlank() }
        _notice.value = trimNotice(built)
        val snap = Snapshot(clock(), signature, categories, byCategory, singles.associateBy { it.id }, entries, playlistOf)
        snapshot = snap
        mirrorIntoSearchCache(built)
        snap
    }

    /** Playlist channels as channel-cache rows, so the cross-provider search finds them. A failure is logged, never a failed listing. */
    private suspend fun mirrorIntoSearchCache(built: Collection<PlaylistGroups>) {
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
            safeLog("search cache not updated: ${e.message}")
        }
    }

    private companion object {
        /** How long a listing is trusted before asking [PlaylistSource] again (it re-downloads only past the list's own refresh time). */
        const val SNAPSHOT_TTL_MS = 5 * 60 * 1000L
        const val GUIDE_TTL_MS = 10 * 60 * 1000L
        const val GUIDE_BEHIND_MS = 2 * 60 * 60 * 1000L
    }
}

/** Every address in [message] replaced: a log line about a list must never carry its credentials. */
internal fun redactUrls(message: String): String = message.replace(Regex("""https?://\S+"""), "<url>")
