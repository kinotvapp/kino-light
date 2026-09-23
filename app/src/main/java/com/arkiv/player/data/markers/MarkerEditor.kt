package com.arkiv.player.data.markers

import com.arkiv.player.data.ChapterMarker
import com.arkiv.player.data.db.SkipMarkerDao
import com.arkiv.player.data.db.SkipMarkerEntity

/**
 * Hand correction of times, for the episode being watched or for the whole series.
 *
 * Exists because the automatic source **genuinely gets it wrong**: for a Dragon Ball episode it
 * returned the credits labeled as opening, there's no reliable way to detect that from here, and
 * that's why [ChapterMarker.SOURCE_MANUAL] always wins over [ChapterMarker.SOURCE_AUTO].
 *
 * And it exists **per episode** because until now nobody did: the two manual paths there were
 * (`DetailViewModel.saveSkipMarker` and `PlayerViewModel.updateMarker`) always wrote with
 * `episodeId = ""`, i.e. a SERIES manual, which by [ChapterMarker.choose]'s precedence overrode
 * the correct automatic one for every other episode. Fixing one broke the other twenty, and
 * `choose`'s `manualCapitulo` branch was unreachable.
 *
 * Each method **merges with whatever was already in that same row** (the episode's or the
 * series', depending on `episodeId`): the source gets one field wrong at a time, so fixing the
 * opening can't erase an ending that was already right.
 */
class MarkerEditor(
    private val dao: SkipMarkerDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** "The opening ends here". `openingStartMs` stays at 0 if there was nothing: the opening starts at the beginning. */
    suspend fun setOpeningEnd(itemId: String, episodeId: String, positionMs: Long) {
        val previous = dao.getById(ChapterMarker.idFor(itemId, episodeId))
        save(itemId, episodeId, previous?.openingStartMs ?: 0L, positionMs, previous?.endingStartMs)
    }

    /** "The ending starts here". */
    suspend fun setEndingStart(itemId: String, episodeId: String, positionMs: Long) {
        val previous = dao.getById(ChapterMarker.idFor(itemId, episodeId))
        save(itemId, episodeId, previous?.openingStartMs, previous?.openingEndMs, positionMs)
    }

    /**
     * "This episode has no intro or outro".
     *
     * On an episode it does NOT delete the row: it leaves it manual and with no times. With no
     * times, [ChapterMarker.choose] ignores it and draws no buttons.
     *
     * On the series it does delete, which is what the sheet's dialog always did: there's nothing
     * automatic there that could come back.
     */
    suspend fun clear(itemId: String, episodeId: String) {
        if (episodeId.isEmpty()) dao.delete(itemId) else save(itemId, episodeId, null, null, null)
    }

    private suspend fun save(
        itemId: String,
        episodeId: String,
        openingStartMs: Long?,
        openingEndMs: Long?,
        endingStartMs: Long?,
    ) {
        dao.upsert(
            SkipMarkerEntity(
                id = ChapterMarker.idFor(itemId, episodeId),
                itemId = itemId,
                episodeId = episodeId,
                openingStartMs = openingStartMs,
                openingEndMs = openingEndMs,
                endingStartMs = endingStartMs,
                updatedAt = clock(),
                origen = ChapterMarker.SOURCE_MANUAL,
            ),
        )
    }
}
