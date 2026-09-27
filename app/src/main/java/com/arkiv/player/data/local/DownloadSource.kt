package com.arkiv.player.data.local

import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.PlayerSource
import com.arkiv.player.playback.SourceKind

/**
 * The `downloads.source` that corresponds to an episode, i.e. which
 * [AppGraph.downloadStrategies][com.arkiv.player.AppGraph] strategy knows how to download it.
 *
 * Derived from the id's prefix with the SAME [PlayerSource.kindFor] the player uses, so as not to
 * invent a second way to decide where an episode came from.
 *
 * The `when` is exhaustive on purpose: the bug that motivated extracting this was an `else ->
 * "archive"` that swallowed Magis chapters, which then fell into archive.org's strategy and died
 * with "This episode has no downloadable file". With the full list, a new source doesn't compile
 * until someone decides who downloads it.
 *
 * Plugin episodes are decided by two predicates the callers pass from `AppGraph` (both read the
 * installed records, never a manifest id, and both fail closed by default):
 *  - `isXuperPlugin` (`AppGraph.isXuperPlugin`): the recognized Xuper install → [XUPER], its own
 *    privileged strategy, unchanged since before third-party downloads existed.
 *  - `pluginDownloads` (`AppGraph.pluginDownloads`): a usable plugin that declared the `download`
 *    capability (apiVersion 2) → [PLUGIN_DOWNLOAD], the generic strategy that resolves through the
 *    plugin's own `resolve()`.
 *  Every other plugin episode stays `"plugin"`, which has no strategy: no button, nothing queued.
 */
object DownloadSource {
    /**
     * The recognized Xuper plugin's titles. Its strategy is `MagisDownloadStrategy` behind
     * `XuperPluginDownloadStrategy`'s re-check. Wins over [PLUGIN_DOWNLOAD] even if Xuper's manifest
     * declares `download`: its path is privileged and must not change.
     */
    const val XUPER = "xuper"

    /**
     * Any other plugin that declared the `download` capability: `PluginDownloadStrategy`, which
     * re-checks the capability when the download runs (a queued row can outlive the install).
     */
    const val PLUGIN_DOWNLOAD = "plugin-download"

    /**
     * Same as the one-argument [sourceFor], except that a plugin episode is routed by the plugin
     * it belongs to: the recognized Xuper install ([isXuperPlugin]) maps to [XUPER]; a usable plugin
     * that declared `download` ([pluginDownloads]) maps to [PLUGIN_DOWNLOAD]; any other plugin's
     * episode stays `"plugin"`, which has no strategy. [pluginDownloads] defaults to "no plugin":
     * a caller that leaves it out gets the pre-SDK-2 behaviour, downloads for Xuper only.
     */
    fun sourceFor(
        episodeId: String,
        isXuperPlugin: (pluginId: String) -> Boolean,
        pluginDownloads: (pluginId: String) -> Boolean = { false },
    ): String {
        val source = sourceFor(episodeId)
        if (source != PLUGIN) return source
        val pluginId = PluginIds.pluginIdOfEpisode(episodeId) ?: return source
        return pluginSource(pluginId, isXuperPlugin, pluginDownloads) ?: source
    }

    /**
     * [sourceFor] for a library row's `items.source` (`"magis"`, `"ditu"`, `"plugin:<id>"`…): a
     * plugin item is routed by the same two predicates; anything else is returned as-is.
     */
    fun sourceForItem(
        itemSource: String,
        isXuperPlugin: (pluginId: String) -> Boolean,
        pluginDownloads: (pluginId: String) -> Boolean = { false },
    ): String {
        val pluginId = PluginIds.pluginIdOfSource(itemSource) ?: return itemSource
        return pluginSource(pluginId, isXuperPlugin, pluginDownloads) ?: itemSource
    }

    /** The strategy key [pluginId]'s titles download under, or null when they have none. Xuper first. */
    private fun pluginSource(
        pluginId: String,
        isXuperPlugin: (pluginId: String) -> Boolean,
        pluginDownloads: (pluginId: String) -> Boolean,
    ): String? = when {
        isXuperPlugin(pluginId) -> XUPER
        pluginDownloads(pluginId) -> PLUGIN_DOWNLOAD
        else -> null
    }

    /**
     * Fails closed for plugins: every plugin episode, Xuper's included, is `"plugin"` (no strategy).
     * Callers that can meet a plugin episode use the overload with the plugin predicates.
     */
    fun sourceFor(episodeId: String): String = when (PlayerSource.kindFor(episodeId)) {
        SourceKind.MAGIS -> "magis"
        // Caracol isn't downloadable: its video comes Widevine-encrypted. "ditu" has no strategy
        // in `AppGraph.downloadStrategies`, so [canDownload] doesn't offer it (and a row that made
        // it into the queue anyway gets marked FAILED by `LocalDownloadWorker` with "Fuente no
        // soportada: ditu"). Sending it to "archive" would say it's from archive.org, which it isn't.
        SourceKind.DITU -> "ditu"
        // "plugin" has no strategy in `AppGraph.downloadStrategies`, so [canDownload] hides every
        // download button for it. Which plugins DO download ([XUPER], [PLUGIN_DOWNLOAD]) is decided
        // by the overload with the plugin predicates.
        SourceKind.PLUGIN -> PLUGIN
        // UNKNOWN (ids from removed sources), LOCAL and LIVE have no download strategy. "archive"
        // is the value this branch has always persisted for them in `downloads.source`; it stays
        // until the Phase 3 audit.
        SourceKind.UNKNOWN, SourceKind.LOCAL, SourceKind.LIVE -> "archive"
    }

    /**
     * Whether the person can be offered to download this episode: there's a registered strategy
     * for its source. [strategies] are the keys of `AppGraph.downloadStrategies`.
     *
     * Exists so as not to show an option that will fail: without a strategy, `LocalDownloadWorker`
     * marks the row FAILED with "Fuente no soportada", AFTER the screen already said "Guardando".
     * Decided by the strategy and not by the source's name: a new source with no strategy stays
     * hidden on its own.
     */
    fun canDownload(
        episodeId: String,
        strategies: Set<String>,
        isXuperPlugin: (pluginId: String) -> Boolean,
        pluginDownloads: (pluginId: String) -> Boolean = { false },
    ): Boolean = hasStrategy(sourceFor(episodeId, isXuperPlugin, pluginDownloads), strategies)

    /** Same, with the source already in hand (`items.source`, which is what the library has). */
    fun hasStrategy(source: String, strategies: Set<String>): Boolean = source in strategies

    private const val PLUGIN = "plugin"
}
