package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.gateway.MAGIS_SERIES
import java.util.Locale

/** Whether tapping it opens chapters instead of playing. Wider than `CatalogItem.isSeries`
 *  (teleplay only): same set search uses. */
val CatalogItem.isMagisSeries: Boolean get() = type in MAGIS_SERIES

/**
 * The search result search's Magis paths already know how to play or open
 * (`SearchPlayback.playMagis`, `MagisSeasonDialog`, `TvMagisSeasonContent`): same keys
 * `MagisSource.resultFrom` fills, so a home card goes through exactly the code a search result does.
 */
fun CatalogItem.toGatewayResult(): GatewayResult = GatewayResult(
    source = "magis",
    title = title.ifBlank { id },
    ref = ref,
    kind = if (isMagisSeries) "series" else "movie",
    extra = buildMap {
        put("content_id", id)
        put("program_type", type)
        put("episode_count", episodeCount.toString())
        poster?.takeIf { it.isNotBlank() }?.let { put("poster", it) }
        backdrop?.takeIf { it.isNotBlank() }?.let { put("backdrop", it) }
    },
)

/** "Película  ·  Acción, Drama  ·  ★ 7.7" -- omitting what the portal didn't send. */
fun CatalogItem.homeMeta(): String = listOfNotNull(
    if (isMagisSeries) "Serie" else "Película",
    MagisHomeClassifier.genreLabels(this).take(3).joinToString(", ").ifBlank { null },
    score?.let { String.format(Locale.US, "★ %.1f", it) },
).joinToString("  ·  ")

/** What both homes' hero shows when nothing is in progress: the first title of the first row. */
fun magisFeatured(rows: List<MagisHomeRow>?): CatalogItem? = rows?.firstOrNull()?.shown?.firstOrNull()
