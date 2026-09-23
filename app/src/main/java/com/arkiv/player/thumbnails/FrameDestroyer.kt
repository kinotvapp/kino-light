package com.arkiv.player.thumbnails

import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity

/**
 * Destroys the frame (file + row) of a chapter that ended up watched.
 *
 * A chapter can end up watched via the manual toggle or the automatic one by progress
 * ([com.arkiv.player.data.ArkivRepository]). Up to Task 5 it could also arrive via LAN or cloud
 * sync (`ArkivRepository.mergeFromSync`/`CloudSyncManager`, deleted in that pruning) when ANOTHER
 * device had already watched it; without cloud sync that path no longer exists, but this class is
 * still the only place that knows how to delete a frame: it's instantiated once in `AppGraph` and
 * shared with whoever needs it, instead of every path repeating the same two lines.
 *
 * `store` is nullable with a default of null for the same reason as in `ArkivRepository`: not to
 * break call sites that build these classes without a configured store (there, deleting a file
 * simply doesn't apply; the Room row still gets deleted).
 */
class FrameDestroyer(
    private val store: FrameStore? = null,
    private val dao: EpisodeFrameDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Can (and needs to) be called more than necessary without checking first whether the frame
     * exists: `savePlayback` fires it on EVERY player tick (~5 s, as long as `watched` stays
     * `true`) with no guard of its own, so this repeats many times per chapter.
     *
     * `FrameStore.delete` (uses `File.delete()`, doesn't throw if there's no file) was always
     * safe to call redundantly, and still runs on every call. The ROW is not: writing the tombstone
     * on every call would stamp `updatedAt` with the local clock on every tick, and this row lives
     * in `episode_frame`, the table that triggers [EpisodeFrameDao.observeAll] -the only Flow
     * that notices a new frame for "Continue watching" (see its own KDoc)-, so rewriting it every
     * ~5 sustained seconds for the rest of the chapter would needlessly invalidate that home row --
     * none of this is an edge case, it's the most common path (see below). (Until Task 5 this would
     * also have re-queued the row for the push to PocketBase; that push -and PocketBase itself-
     * were removed entirely in that pruning, so it no longer applies, but the reason not to
     * over-write still stands because of the Flow invalidation.) That's why the row is read first
     * ([EpisodeFrameDao.getIncludingDeleted], which also sees tombstones), and if it's ALREADY a
     * tombstone (`deleted == 1`) it's left alone: the seal (`upsert` with a fresh `updatedAt`)
     * happens exactly ONCE, the one that makes the live-to-deleted transition.
     *
     * The file really is deleted, but the ROW doesn't disappear: a tombstone is left (`deleted = 1`,
     * fresh `updatedAt`) instead of a `DELETE` -until Task 5 that was so the deletion would travel
     * through sync; without cloud sync there's nobody left to tell, but the tombstone is kept
     * anyway because it's still the signal [EpisodeFrameDao.getIncludingDeleted] uses to avoid
     * over-writing (see above)-. `positionMs`/`capturedAt` stay at 0 and `remoteUrl` at null on
     * purpose: once the frame is deleted those fields mean nothing (nobody reads them off a row
     * with `deleted = 1`), and keeping the previous values would require reading the row before
     * overwriting it, for data nothing uses.
     */
    suspend fun destroy(episodeId: String) {
        // The file is ALWAYS deleted, even if the row is already a tombstone: there could be an
        // orphaned JPEG left over (e.g. a capture that ran right before the tombstone arrived via
        // sync from another device).
        store?.delete(episodeId)
        val current = dao.getIncludingDeleted(episodeId)
        if (current?.deleted == 1) return // already sealed: don't rewrite updatedAt again
        dao.upsert(
            EpisodeFrameEntity(
                episodeId = episodeId,
                positionMs = 0,
                capturedAt = 0,
                updatedAt = now(),
                deleted = 1,
                remoteUrl = null,
            )
        )
    }

    /**
     * Same deletion but for EVERYTHING. `LibraryWiper` used it for the logout wipe -otherwise the
     * new identity would be left with the JPEGs from the scenes the previous person watched-;
     * `LibraryWiper` was deleted entirely in Task 9 (sub-project 2B) along with the rest of
     * accounts, and today this method has no production caller (only its test). Left in because it
     * documents the only physical deletion -without a tombstone- that exists in this class, in case
     * a full wipe is ever needed again.
     *
     * Unlike [destroy], this one IS a physical `DELETE` (`dao.deleteAll`) and does NOT leave
     * tombstones: without cloud sync on this branch (see the class's own KDoc) there's nobody left
     * to notify of the deletion, so there's no need to leave a trace.
     *
     * Not a `forEach` over [destroy] on purpose: the wipe deletes `items` and `episodes` in the
     * same sweep, and both the directory and the table are emptied in a single pass.
     */
    suspend fun destroyAll() {
        store?.deleteAll()
        dao.deleteAll()
    }
}
