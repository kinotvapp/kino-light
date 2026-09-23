package com.arkiv.player.data.gateway

import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.SeasonChapter

/**
 * Repairs, once and silently, a Magis item that was saved with no identity.
 *
 * Why it's needed: the series' `tmdbId` -- which the chapter's real name, its thumbnail and its
 * synopsis hang off of -- gets written when the season is SAVED, not when it's opened. Items that
 * came in when the gateway still couldn't identify the series were left with nothing, and
 * reopening the screen never fixed them. Without this they'd have to be deleted and re-added by
 * hand, one by one.
 *
 * It's best-effort end to end: if the gateway doesn't answer, if the ref expired, or if the
 * series still can't be identified, the screen draws exactly as it did before. Never throws.
 *
 * Skips itself when there's nothing to do (the item isn't Magis's, already has its `tmdbId`, or
 * saved no ref), so calling it on every detail-screen open costs not even one network call.
 */
suspend fun repairMagisIdentity(
    repository: ArkivRepository,
    api: ContentSource,
    itemId: String,
): Boolean {
    val ref = repository.magisRefToRepair(itemId) ?: return false
    val (chapters, series) = runCatching { api.episodesWithSeries(ref) }.getOrElse { return false }

    val tmdbId = series?.tmdbId?.takeIf { it > 0 }
    val enriched = chapters.filter {
        it.still != null || it.tmdbTitle != null || it.overview != null
    }
    // Nothing to apply: the gateway still can't identify this series (or the portal changed it).
    // Bails out without writing, so as not to mark something as repaired when it isn't.
    if (tmdbId == null && enriched.isEmpty()) return false

    repository.applyMagisIdentity(
        itemId,
        tmdbId,
        // The name TMDB knows the series by: the half that was missing. Repairing just the
        // `tmdbId` fixed the thumbnails and chapter names, but the card kept saying
        // "Shin seiki evangerion Temp.1" forever.
        tituloCanonico = series?.title,
        chapters = chapters.map {
            SeasonChapter(
                number = it.number, title = it.title, ref = it.ref,
                still = it.still, tmdbTitle = it.tmdbTitle, overview = it.overview,
            )
        },
    )
    android.util.Log.w(
        "ArkivGw",
        "repaired the identity of $itemId: tmdb=$tmdbId · ${enriched.size}/${chapters.size} chapters with metadata",
    )
    return true
}
