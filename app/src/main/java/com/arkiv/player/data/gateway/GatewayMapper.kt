package com.arkiv.player.data.gateway

import com.arkiv.player.data.plugin.PluginColors
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.ui.catalog.PlaySource

/**
 * Translates a search result into the model the screen already uses.
 *
 * `source` is set by the search sources: `PluginContentSource` with `plugin:<id>` (Xuper included,
 * and Caracol too as the `caracol-tv` plugin). Any other value returns `null`:
 * this APK wouldn't know what to do with it — `"magis"`/`"ditu"` among them, the deleted native
 * sources', which nothing emits any more.
 *
 * The magnet and the page URL are NOT filled in: everything is resolved at playback time, from
 * [GatewayResult.ref].
 */
fun GatewayResult.toPlaySource(): PlaySource? = when (source) {
    // An installed plugin: `PluginContentSource` sets `source = "plugin:<id>"` and puts its display
    // name and color in `extra`. A malformed id is ignored like any unknown source.
    // "archive"/"torrent"/"web"/"ditu": gone from this branch; ignored, same as any unknown source.
    else -> PluginIds.pluginIdOfSource(source)?.let { id ->
        PlaySource.Plugin(
            pluginId = id,
            pluginName = extra["pluginName"].orEmpty().ifBlank { id },
            color = PluginColors.parse(extra["color"]),
            result = this,
        )
    }
}