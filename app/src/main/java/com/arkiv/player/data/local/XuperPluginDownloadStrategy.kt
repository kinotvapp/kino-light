package com.arkiv.player.data.local

import com.arkiv.player.data.plugin.PluginIds
import java.io.File

/**
 * The [DownloadSource.XUPER] strategy: the Xuper plugin's chapters, downloaded by [inner]
 * (`MagisDownloadStrategy`, which resolves the saved ref through `contentSource` -- the Xuper
 * plugin's own `PluginContentSource` for a `plg1:` ref -- and fetches the file with the headers the
 * resolve returned).
 *
 * Re-checks [isXuperPlugin] at download time, not only when the row was queued: a queued row can
 * outlive the install it was queued for (Xuper uninstalled and another repo's plugin installed under
 * the same manifest id). Only the recognized Xuper install ever downloads; any other plugin's episode
 * that reaches this key fails without resolving anything.
 */
class XuperPluginDownloadStrategy(
    private val inner: DownloadStrategy,
    private val isXuperPlugin: (pluginId: String) -> Boolean,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val pluginId = PluginIds.pluginIdOfEpisode(episodeId)
        if (pluginId == null || !isXuperPlugin(pluginId)) {
            return DownloadOutcome.Failed("Solo los títulos de Xuper se pueden descargar")
        }
        return inner.download(episodeId, alreadyConfirmed, targetDir, onProgress)
    }

    override suspend fun clearLeftovers(episodeId: String, targetDir: File) =
        inner.clearLeftovers(episodeId, targetDir)
}
