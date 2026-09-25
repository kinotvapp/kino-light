package com.arkiv.player.data

import com.arkiv.player.companion.CompanionPlayItem
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
): CompanionPlayItem? {
    if (episodeId.startsWith(PlayerSource.LIVE_PREFIX)) {
        return CompanionPlayItem(
            kind = CompanionPlayItem.KIND_LIVE,
            liveCode = episodeId.removePrefix(PlayerSource.LIVE_PREFIX),
        )
    }
    val kind = when (PlayerSource.kindFor(episodeId)) {
        SourceKind.MAGIS -> CompanionPlayItem.KIND_MAGIS
        SourceKind.DITU -> CompanionPlayItem.KIND_DITU
        // The paired device can't be assumed to have the same plugin installed: not offered in v1.
        SourceKind.PLUGIN -> return null
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
