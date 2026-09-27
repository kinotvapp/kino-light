package com.arkiv.player.ui.catalog

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginOutput

/** The badge a plugin's live channel wears on its card, phone and TV: the wording the native "Canales en vivo" row uses. */
const val LIVE_BADGE = "EN VIVO"

/** A plugin result is a live channel when the plugin said `kind: "live"` (apiVersion 2). Same rule on phone and TV. */
fun GatewayResult.isLiveChannel(): Boolean = kind == PluginOutput.KIND_LIVE

/** [LIVE_BADGE] for a live channel, null for a movie or series (those wear the plugin's name or nothing). */
fun liveBadge(result: GatewayResult): String? = if (result.isLiveChannel()) LIVE_BADGE else null
