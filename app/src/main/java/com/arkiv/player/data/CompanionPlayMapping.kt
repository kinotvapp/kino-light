package com.arkiv.player.data

import com.arkiv.player.companion.CompanionPlayItem
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind

/**
 * Pure reconstruction of a [CompanionPlayItem] from the fields a library row holds, so the phone
 * can hand a title to the TV. Split out of [ArkivRepository.companionPlayItem] so it is unit-
 * testable without Room. Null when the id has no source the TV could re-resolve.
 *
 * `contentId` for Magis is the item id minus the `magis:` prefix ([MagisEntities.PREFIX]); Caracol
 * derives its own from the `ref`, so it is left blank there. `live:<code>` needs no row at all.
 */
fun buildCompanionPlayItem(
    episodeId: String,
    ref: String?,
    itemIdentifier: String,
    title: String,
    season: Int?,
    episode: Int?,
    poster: String,
    /** The plugin title is the official Xuper plugin's (`XuperPrivilege.grants` on its installed record). */
    officialXuper: Boolean = false,
): CompanionPlayItem? {
    if (episodeId.startsWith(PlayerSource.LIVE_PREFIX)) {
        return CompanionPlayItem(
            kind = CompanionPlayItem.KIND_LIVE,
            liveCode = episodeId.removePrefix(PlayerSource.LIVE_PREFIX),
        )
    }
    val kind = when (PlayerSource.kindFor(episodeId)) {
        SourceKind.MAGIS -> CompanionPlayItem.KIND_MAGIS
        // The paired device can't be assumed to have the same plugin installed: not offered, except
        // for the official Xuper plugin, whose titles travel as the Magis ref they wrap.
        SourceKind.PLUGIN -> return if (officialXuper) xuperPluginPlayItem(ref, title, season, poster) else null
        else -> return null
    }
    if (ref.isNullOrBlank()) return null
    return CompanionPlayItem(
        kind = kind,
        ref = ref,
        contentId = if (kind == CompanionPlayItem.KIND_MAGIS) itemIdentifier.removePrefix(MagisEntities.PREFIX) else "",
        title = title,
        season = season ?: 0,
        episode = episode ?: 0,
        poster = poster,
    )
}

/**
 * An official Xuper plugin title as the [CompanionPlayItem.KIND_MAGIS] play it was before Xuper's
 * rows moved to the plugin path (0.9.41 lost this). Its library ref is a [PluginRef] whose inner
 * `ref` is the bridge's own [MagisRef] (`kino.xuper.*` hands those out and takes them back), so the
 * TV gets exactly what a native Magis title sends: it re-mints a `magis:` title and resolves it
 * through ITS OWN Xuper plugin (`LegacyXuperRefSource`) -- or its native Magis source, on a TV not
 * updated yet. A TV without a usable Xuper plugin refuses in the ack ([com.arkiv.player.companion.xuperPlayRefusal]).
 *
 * Null when the inner ref isn't a Magis one: nothing the TV could re-resolve.
 */
private fun xuperPluginPlayItem(ref: String?, title: String, season: Int?, poster: String): CompanionPlayItem? {
    val inner = ref?.let { PluginRef.decode(it) }?.ref ?: return null
    val magis = MagisRef.decode(inner) ?: return null
    return CompanionPlayItem(
        kind = CompanionPlayItem.KIND_MAGIS,
        ref = inner,
        contentId = magis.contentId,
        title = title,
        season = season ?: 0,
        episode = if (magis.isSeries) magis.episode else 0,
        poster = poster,
    )
}
