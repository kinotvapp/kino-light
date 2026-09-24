package com.arkiv.player.data.gateway

import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.ui.catalog.PlaySource

/**
 * Translates a search result into the model the screen already uses.
 *
 * `source` is set by the two search sources: `MagisSource` (com.arkiv.player.data.magis) with
 * `"magis"` and `DituSource` (com.arkiv.player.data.ditu) with `"ditu"`; `plugin:<id>` is set by
 * `PluginContentSource`. Any other value returns `null`: this APK wouldn't know what to do with it.
 *
 * The magnet and the page URL are NOT filled in: everything is resolved at playback time, from
 * [GatewayResult.ref].
 */
fun GatewayResult.toPlaySource(): PlaySource? = when (source) {
    // Magis takes the whole result: its `ref` is all it needs to resolve, and there's no prior
    // app type to map it to.
    "magis" -> PlaySource.Magis(this)

    // Caracol, same: its `ref` (`ditu1:<contentType>:<contentId>`, see `DituRef`) is enough to
    // resolve and to list episodes.
    "ditu" -> PlaySource.Ditu(this)

    // An installed plugin: `PluginContentSource` sets `source = "plugin:<id>"` and puts its display
    // name and color in `extra`. A malformed id is ignored like any unknown source.
    // "archive"/"torrent"/"web": removed from this branch; ignored, same as any unknown source.
    else -> PluginIds.pluginIdOfSource(source)?.let { id ->
        PlaySource.Plugin(
            pluginId = id,
            pluginName = extra["pluginName"].orEmpty().ifBlank { id },
            color = PluginColors.parse(extra["color"]),
            result = this,
        )
    }
}
