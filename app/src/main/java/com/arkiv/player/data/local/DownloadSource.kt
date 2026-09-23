package com.arkiv.player.data.local

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
 */
object DownloadSource {
    fun sourceFor(episodeId: String): String = when (PlayerSource.kindFor(episodeId)) {
        SourceKind.MAGIS -> "magis"
        // Caracol isn't downloadable: its video comes Widevine-encrypted. "ditu" has no strategy
        // in `AppGraph.downloadStrategies`, so [canDownload] doesn't offer it (and a row that made
        // it into the queue anyway gets marked FAILED by `LocalDownloadWorker` with "Fuente no
        // soportada: ditu"). Sending it to "archive" would say it's from archive.org, which it isn't.
        SourceKind.DITU -> "ditu"
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
    fun canDownload(episodeId: String, strategies: Set<String>): Boolean =
        hasStrategy(sourceFor(episodeId), strategies)

    /** Same, with the source already in hand (`items.source`, which is what the library has). */
    fun hasStrategy(source: String, strategies: Set<String>): Boolean = source in strategies
}
