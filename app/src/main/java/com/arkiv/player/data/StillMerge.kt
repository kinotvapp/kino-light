package com.arkiv.player.data

import com.arkiv.player.data.db.EpisodeStillEntity

/**
 * How the `episode_still` row that was ALREADY saved is combined with the one that just resolved.
 *
 * It exists because two different sources write to that table with different data: Magis fills it
 * when saving the season (`MagisEntities.seasonStills`, with what the gateway matched
 * against TMDB) and `ArkivRepository.ensureEpisodeStills` fills it for everything else (Ditu
 * today, and legacy torrent/web/archive rows) by asking TMDB.
 * `EpisodeStillDao.upsertAll` is a REPLACE, so the second write overwrites the **entire** first
 * row: without this merge, a TMDB timeout while opening the detail screen left `stillUrl`, `title`
 * and `overview` null on top of what Magis had already saved correctly, and since the row still
 * ended up written (marking "already asked"), that series stayed without images forever.
 *
 * The rule is field-by-field, not "new row or old row": the two sources complement each other. The
 * gateway only asks TMDB for es-MX, so it can bring a still and a name but no synopsis; TMDB
 * queried from here can bring the English-fallback synopsis. With a per-row rule, the second write
 * would wipe out the first one's good half.
 *
 * Pure/JVM (no Room, no network) so it can be tested, same as [MagisEntities].
 */
object StillMerge {

    /**
     * The new one wins **if it brings something**; if it comes empty or null, what was already
     * saved wins.
     *
     * [fetchedAt] always keeps [updated]'s: the date says "when it was last asked", not "when the
     * data is from", and it was just asked.
     */
    fun merge(previous: EpisodeStillEntity?, updated: EpisodeStillEntity): EpisodeStillEntity {
        if (previous == null) return updated
        return updated.copy(
            stillUrl = updated.stillUrl?.takeIf { it.isNotBlank() } ?: previous.stillUrl,
            title = updated.title?.takeIf { it.isNotBlank() } ?: previous.title,
            overview = updated.overview?.takeIf { it.isNotBlank() } ?: previous.overview,
        )
    }

    /**
     * [merge] for a whole batch: each new row against whatever had the same `episodeId`.
     *
     * Both of the table's write paths use it, which is exactly the point: Magis's
     * (`ArkivRepository.addMagisSource`/`addMagisSeason`, via `MagisEntities.seasonStills`)
     * also goes through here. Without that, saving a season would hand the table the raw REPLACE:
     * `seasonStills` leaves null every field the gateway didn't resolve, so if the gateway
     * only brought the still, the name and synopsis `ensureEpisodeStills` had completed before were
     * silently lost -- and since the row still existed, its early cutoff kept them from ever being
     * filled again.
     *
     * [previous] comes as a map (not a list) because the caller already has it indexed by
     * `episodeId`, which is the table's PK.
     */
    fun mergeAll(
        previous: Map<String, EpisodeStillEntity>,
        updated: List<EpisodeStillEntity>,
    ): List<EpisodeStillEntity> = updated.map { merge(previous[it.episodeId], it) }
}
