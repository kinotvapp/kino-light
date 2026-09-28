package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginOutput
import com.arkiv.player.data.plugin.PluginPlaylist
import com.arkiv.player.data.plugin.PluginRef
import java.security.MessageDigest

/** Group titles hidden in every playlist (lowercased, trimmed), on top of the playlist's own `hideGroups`. */
internal val ADULT_GROUPS = setOf("adultos", "adulto", "adult", "adults", "xxx", "18+", "+18", "for adults", "porn", "porno")

internal fun sha1Hex(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * One playlist as a provider's categories and channels. [entries] maps each kept channel code to its
 * M3U entry (for playback and the guide). [kept] channels were kept out of [total] valid, visible
 * ones; [skipped] = broken lines plus entries on a host the plugin may not play; [hidden] = entries
 * in a hidden or adult group (never shown, never counted in [total]). Both include what the parse
 * already dropped ([M3uResult.hidden], [M3uResult.refused]). [cut] = the parse stopped at its time
 * budget, so the list is cut whatever the counts say.
 */
internal data class PlaylistGroups(
    val categories: List<ProviderCategory>,
    val byCategory: Map<String, List<LiveChannel>>,
    val entries: Map<String, M3uEntry>,
    val kept: Int,
    val total: Int,
    val skipped: Int,
    val hidden: Int,
    val cut: Boolean = false,
)

/** "Lista recortada: <kept> de <total> canales" when the caps or a parse's time budget cut any of [groups], else null. */
internal fun trimNotice(groups: Collection<PlaylistGroups>): String? {
    val kept = groups.sumOf { it.kept }
    val total = groups.sumOf { it.total }
    return if (total > kept || groups.any { it.cut }) "Lista recortada: $kept de ${maxOf(kept, total)} canales" else null
}

/**
 * One parsed playlist as categories and channels of [providerId]. Pure.
 *
 * - Codes: `~<key>.<tvg-id>` when the tvg-id is a valid item id and this is the list's first entry
 *   with it, else `~<key>.<sha1(url|name) first 16 hex>`, so the same entry keeps its code across
 *   refreshes, and a later copy of a tvg-id never moves the first one's favourites and recents.
 * - Categories: `pl:<key>:<sha1(group) first 10 hex>` (a plugin's own ids can't hold `:`); a
 *   blank group is "Sin categoría"; past [maxCategories] - 1 groups, the rest share "Otros"
 *   (`pl:<key>:otros`), so the list never exceeds [maxCategories].
 * - A hidden or adult group is dropped and counted in [PlaylistGroups.hidden]; an entry whose URL
 *   fails [entryAllowed] is dropped and counted in [PlaylistGroups.skipped].
 * - At most [maxChannels] channels; with no category budget left nothing is kept.
 * - With `playlist.resolve`, each channel carries a LIVE ref of its entry URL for the plugin's `resolve`.
 * - A channel's `tvg-logo` is checked with [PluginOutput.imageUrl] against [hosts], exactly like any
 *   other plugin image (a plugin's poster, backdrop, `liveChannels` logo): the plugin's declared
 *   hosts don't gate it, only the local-address rule and the servers the person typed do. Pass
 *   [PluginLiveProvider]'s `liveHosts`, the same value already used for that plugin's other images.
 */
internal fun groupPlaylist(
    result: M3uResult, key: String, providerId: String, pluginId: String, playlist: PluginPlaylist, hosts: EffectiveHosts,
    entryAllowed: (String) -> Boolean, maxCategories: Int, maxChannels: Int,
): PlaylistGroups {
    // Index of each tvg-id's first entry in the parsed list, whatever the loop below drops.
    val firstWithTvg = HashMap<String, Int>()
    result.entries.forEachIndexed { i, e -> firstWithTvg.putIfAbsent(e.tvgId, i) }
    val categories = LinkedHashMap<String, ProviderCategory>()
    val byCategory = LinkedHashMap<String, MutableList<LiveChannel>>()
    val entries = LinkedHashMap<String, M3uEntry>()
    val otrosId = "pl:$key:otros"
    var hidden = 0
    var dropped = 0
    var skipped = result.skipped + result.refused
    var kept = 0
    for ((i, e) in result.entries.withIndex()) {
        val group = e.group.trim().ifEmpty { "Sin categoría" }
        if (PlaylistSource.isHiddenGroup(group, playlist)) { hidden++; continue }
        if (!entryAllowed(e.url)) { skipped++; dropped++; continue }
        if (kept >= maxChannels || maxCategories <= 0) continue
        val idPart = e.tvgId.takeIf { PluginOutput.ID.matches(it) && firstWithTvg[it] == i } ?: sha1Hex("${e.url}|${e.name}").take(16)
        val code = "~$key.$idPart"
        // The very same url and name twice: one channel, the copy counted as skipped.
        if (code in entries) { skipped++; dropped++; continue }
        val catId = "pl:$key:${sha1Hex(group).take(10)}"
        val category = categories[catId]
            ?: if (categories.size < maxCategories - 1) ProviderCategory(catId, group).also { categories[catId] = it }
            else categories.getOrPut(otrosId) { ProviderCategory(otrosId, "Otros") }
        val ref = if (playlist.resolve) PluginRef(pluginId, code, PluginRef.LIVE, e.url).encode() else null
        byCategory.getOrPut(category.id) { ArrayList() } +=
            LiveChannel(code, e.name, e.number, PluginOutput.imageUrl(e.logo, hosts).ifEmpty { null }, provider = providerId, ref = ref)
        entries[code] = e
        kept++
    }
    return PlaylistGroups(
        categories.values.toList(), byCategory, entries, kept, result.total - hidden - dropped, skipped,
        hidden + result.hidden, cut = result.stoppedEarly,
    )
}
