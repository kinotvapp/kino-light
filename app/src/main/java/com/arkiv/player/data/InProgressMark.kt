package com.arkiv.player.data

import com.arkiv.player.data.db.PlaybackEntity

/**
 * What [ArkivRepository.markInProgress] wrote for one play attempt, and what was there before it.
 *
 * The early mark exists so the detail screen knows which chapter you're on even if you leave right
 * away (see `markInProgress`'s KDoc). But it's written BEFORE the source resolves, so an attempt
 * that never plays (the plugin failed, the host was refused, no network, the person left while it
 * was resolving) would leave a row behind: position 0, duration 0 and a fresh `lastPlayedAt`,
 * which is enough for "Continuar viendo" and the library's order to count it as watched. This is
 * what lets the player take exactly that back ([ArkivRepository.undoInProgress]).
 */
data class InProgressMark(
    val episodeId: String,
    /** The row before the mark, byte for byte; null when there was none. */
    val previous: PlaybackEntity?,
    /** The row the mark wrote (its `updatedAt` is Room's to fill, so it's never compared). */
    val written: PlaybackEntity,
)

/** What undoing an [InProgressMark] does to the row that's there now. See [undoAgainst]. */
sealed interface InProgressUndo {
    /** Something real wrote after the mark (a save with a duration, "watched" by hand): it stays. */
    data object Keep : InProgressUndo

    /** There was no row before the mark: it goes. */
    data object Delete : InProgressUndo

    /** There was one: it comes back exactly as it was, `lastPlayedAt` and `updatedAt` included. */
    data class Restore(val row: PlaybackEntity) : InProgressUndo
}

/**
 * The row `markInProgress` writes for [episodeId] over [existing] at [now], or null when it writes
 * nothing: an already-watched chapter keeps its row untouched (see `markInProgress`'s KDoc for the
 * three consumers of `lastPlayedAt` that can't tell a re-watch from a new watch).
 */
internal fun inProgressRow(episodeId: String, existing: PlaybackEntity?, now: Long): PlaybackEntity? {
    if (existing?.watched == true) return null
    return PlaybackEntity(
        episodeId = episodeId,
        positionMs = existing?.positionMs ?: 0L,
        durationMs = existing?.durationMs ?: 0L,
        watched = false,
        lastPlayedAt = now,
    )
}

/**
 * How to undo this mark given the row that's in the table now ([current]).
 *
 * Only a row still exactly as the mark left it is touched: if anything wrote after it -- the player
 * saved a position with a duration, the person marked it watched -- the attempt did happen and the
 * row is left alone. `updatedAt` is not part of the comparison: Room's trigger stamps it on insert,
 * so the stored row never carries the `0` the mark was built with.
 */
/**
 * Whether undoing an attempt also takes back the library item it played from.
 *
 * Picking a plugin source is what puts the title in "Mi biblioteca" (`SearchPlayback.playPlugin` →
 * `addPluginMovie`), before anything resolves. So a source that fails leaves a card behind even with
 * its history row gone: four failed sources for one movie were four cards. The item goes only when
 * this attempt is the whole of its story: the pick created it in this process ([createdByPick]),
 * there was no history row before the mark ([previous] null), and nothing of the item is left
 * after the undo -- no history row on any chapter ([itemHistoryRows]) and no download
 * ([itemDownloads]). A title that was already in the library, or that played once, is never touched.
 */
internal fun dropsUnplayedPick(createdByPick: Boolean, previous: PlaybackEntity?, itemHistoryRows: Int, itemDownloads: Int): Boolean =
    createdByPick && previous == null && itemHistoryRows == 0 && itemDownloads == 0

internal fun InProgressMark.undoAgainst(current: PlaybackEntity?): InProgressUndo {
    if (current == null || current.copy(updatedAt = written.updatedAt) != written) return InProgressUndo.Keep
    return previous?.let { InProgressUndo.Restore(it) } ?: InProgressUndo.Delete
}
