package com.arkiv.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arkiv.player.data.ChapterMarker

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val identifier: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String,
    val addedAt: Long,
    /** Manual type override: "movie" | "series" | null (= automatic detection). */
    val categoryOverride: String? = null,
    /** Origin: "archive" (default) | "torrent". */
    val source: String = "archive",
    /** For torrents: the .torrent's bytes in base64 (to re-stream it). Null if it's archive. */
    val torrentData: String? = null,
    /** Sync: last-modification clock (LWW) and delete tombstone. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    /**
     * How many episodes this series had the last time its detail was opened. It's the base of
     * the "new chapters" badge: the difference against the current count is what appeared since
     * then. See [com.arkiv.player.data.newcontent.NewEpisodeCounter] for why it's counted this way and
     * not by date.
     *
     * `null` = never opened since this counter exists, and does NOT paint a badge.
     */
    val episodiosVistosEnLista: Int? = null,
    /**
     * The TMDB series this item corresponds to, when known. Saved when it gets added from
     * search; without this the link is lost and the detail screen has nobody to ask for the
     * chapters' titles. Null for items added by hand via identifier/URL.
     */
    val tmdbId: Int? = null,
    /**
     * The name TMDB knows this work by, when it could be identified. It's what the library
     * SHOWS; [title] is left with whatever the source said.
     *
     * Alongside and not overwriting it: [title] holds the portal's name ("Shin seiki evangerion
     * Temp.1") or the person's manual rename, and both would be lost if the canonical one
     * overwrote them — the day TMDB gets it wrong there would be no way back, and a manual
     * rename couldn't win over automatic identification. Renaming by hand sets this to null, so
     * what the person wrote is what's shown.
     *
     * Null = not identified (or not asked yet). See `MagisEntities.buildSeason`.
     */
    val tituloCanonico: String? = null,
    /**
     * "movie" | "tv" (same vocabulary as [com.arkiv.player.data.catalog.TmdbItem.type]), when
     * known for certain on add. Different from [categoryOverride] -which is a MANUAL override and
     * uses "series", not "tv"-: this is the type the source brought, not a person's correction.
     *
     * Lets the library tell with certainty whether it already has something (see
     * [com.arkiv.player.data.model.WorkKind]) instead of comparing by title, which is fuzzy.
     * Null when the source doesn't know it: better a gap than a made-up type.
     */
    val tipo: String? = null,
)

@Entity(
    tableName = "episodes",
    indices = [Index("itemId")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val section: String,
    val displayName: String,
    val orderIndex: Int,
    val durationSeconds: Double,
    val thumbPath: String?,
    val originalPath: String?,
    val originalFormat: String?,
    val originalSize: Long,
    val derivativePath: String?,
    val derivativeFormat: String?,
    val derivativeSize: Long,
    /**
     * Season and chapter, set by the source when it builds the episode (see
     * `MagisEntities`/`DituEntities`). With this and the item's `tmdbId`, TMDB can be asked for
     * the chapter's real title: neither Magis nor Caracol return the episode name, only its
     * number. Null when the source doesn't provide them, and in rows saved before v16.
     */
    val season: Int? = null,
    val episode: Int? = null,
    /** For torrents: the file's index inside the torrent. Null if it's archive. */
    val torrentFileIndex: Int? = null,
    /**
     * For series where each episode is its own torrent (e.g. catalog anime):
     * THIS episode's .torrent bytes in base64. Null = use the item's torrent.
     */
    val torrentData: String? = null,
    /** Sync: last-modification clock (LWW) and delete tombstone. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(tableName = "playback")
data class PlaybackEntity(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    /** Sync: last-modification clock (LWW) and delete tombstone. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/**
 * Intro/outro times. The key is derived (`"<itemId>|<episodeId>"`, see
 * [com.arkiv.player.data.ChapterMarker.idFor]) because sync pushes each collection by ONE
 * natural field and a composite key would break that mechanism.
 *
 * Empty [episodeId] = applies to the whole series: it's the marker set by hand in the dialog.
 *
 * [origen] tells apart what was set BY HAND from what AniSkip brought on its own: see
 * [com.arkiv.player.data.ChapterMarker.choose]. The default is MANUAL on purpose -- what already
 * exists and whatever a person writes counts as manual without having to remember to set it.
 */
@Entity(tableName = "skip_markers")
data class SkipMarkerEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val episodeId: String = "",
    val openingStartMs: Long?,
    val openingEndMs: Long?,
    val endingStartMs: Long?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val origen: String = ChapterMarker.SOURCE_MANUAL,
)

/**
 * TMDB art per item (local, not synced). `backdropsJson` is a JSON list of landscape backdrop
 * URLs; the home uses the 1st for the card and a random one for the hero. An item with no match
 * is left with empty backdrops (`"[]"`) but with a row, so as not to re-search on every load.
 */
@Entity(tableName = "artwork")
data class ArtworkEntity(
    @PrimaryKey val itemId: String,
    val tmdbId: Int? = null,
    val tmdbType: String? = null,
    val backdropsJson: String = "[]",
    val fetchedAt: Long = 0,
) {
    val backdrops: List<String>
        get() = runCatching {
            val arr = org.json.JSONArray(backdropsJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
}

/**
 * Catalog search history (anime/movies). Composite PK (query, kind): the same text searched in
 * different tabs (movie/tv/anime) doesn't overwrite itself.
 */
@Entity(tableName = "search_history", primaryKeys = ["query", "kind"])
data class SearchHistoryEntity(
    val query: String,
    val kind: String,
    val atMs: Long,
)

/**
 * A title opened from the search, to be able to come back to it without searching again.
 *
 * The PK is the derived id [com.arkiv.player.data.SearchHistoryPolicy.titleId] builds: it locks
 * the identity rule into one place and lets REPLACE do the dedup.
 */
@Entity(tableName = "recent_titles")
data class RecentTitleEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val tmdbId: Int?,
    val anilistId: Long?,
    val title: String,
    val posterUrl: String,
    val year: String,
    val atMs: Long,
)

/**
 * A download to the device's OWN storage. `source` records where it came from: `"magis"` for
 * today's Magis downloads, `"archive"` as the fallback this branch has always written for
 * sources without a real download strategy (see `DownloadSource.sourceFor`), and legacy `"torrent"`/
 * `"web"` values left in rows saved before this branch's pruning.
 *
 * `variant` is still NOT NULL (and today it's always `""`) because SQLite can't change a column's
 * nullability with ALTER TABLE, and rebuilding the table isn't worth it for a field that only
 * archive.org's removed "original"/"derivative" quality ever wrote to.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val variant: String,              // always "" today; only archive.org ever filled it, and that source is gone
    val state: String,                // see LocalDownloadState
    val progress: Float,              // 0..1
    val localUri: String?,            // historical: the file:// the system's DownloadManager left
    val bytes: Long,                  // known total size (0 if not known yet)
    val source: String = "archive",   // "magis" | "ditu" | "archive" (fallback) | legacy "torrent"/"web"
    val filePath: String? = null,     // final file's absolute path
    val bytesDone: Long = 0,
    // Orphaned since the NUC pruning (Task 8): nothing reads or writes it anymore (it was the
    // web->NUC bridge, deleted in that same task). The field -and the physical column- is left
    // as-is, with no migration to remove it: in SQLite that requires rebuilding the whole
    // `downloads` table (which does have real user data), unlike the three fully-orphaned tables
    // that did get dropped in MIGRATION_28_29. See the Task 8 fix round's report (a disclosed
    // follow-up, not attempted since it's riskier than dropping the three tables).
    val stagingItemId: Long? = null,
    val error: String? = null,
    val createdAt: Long = 0,
    val sizeConfirmed: Boolean = false, // the user already accepted the size gate
)

/**
 * A live TV channel marked as a favorite. `deleted` is the live un-favorite mechanism: unfavoriting
 * sets it (`LiveFavoriteDao.delete`), and every read filters on it (`flowAll`, `isFavorite`).
 * `updatedAt` is left over from this branch's two removed cloud-sync paths (see
 * [com.arkiv.player.data.db.SyncTriggers]) and has no reader today.
 */
@Entity(tableName = "live_favorites")
data class LiveFavoriteEntity(
    @PrimaryKey val code: String,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

/** Last channels watched. Carries no tombstone: it's pruned by age, not deleted by hand. */
@Entity(tableName = "live_recents")
data class LiveRecentEntity(
    @PrimaryKey val code: String,
    val nombre: String,
    val vistoAt: Long,
    val updatedAt: Long = 0,
)

/**
 * Local cache of the channel catalog, so the section opens instantly and keeps showing the
 * grid even when the gateway is slow or down. **Doesn't travel through sync**: it's rebuildable
 * cache, not user data, and putting it in the snapshot would mean sending 1,000 rows between
 * devices for nothing.
 *
 * Composite PK `(code, categoria)`, NOT just `code`: the same channel can be in several of the
 * portal's categories (e.g. "Deportes" and "Todos"). With a PK on `code` alone, caching category
 * B would overwrite (`REPLACE`) the rows of channels also in A, leaving them with `categoria = B`
 * -- and going back to A from cache (gateway down), those channels would disappear from the grid
 * (finding F5 of the final review). It self-healed as soon as the gateway answered again, but the
 * cache exists precisely for when it does NOT answer.
 */
@Entity(tableName = "live_channels_cache", primaryKeys = ["code", "categoria"])
data class LiveChannelCacheEntity(
    val code: String,
    val categoria: Int,
    val nombre: String,
    val numero: Int,
    val logo: String?,
    val guardadoAt: Long,
)

/**
 * A chapter's still (official frame), resolved from TMDB. Local and NOT synced, same as
 * [ArtworkEntity]: it's derivable cache, not user data. Goes in its own table and not as a
 * column of `episodes` on purpose — that table has sync triggers, and touching 49 rows per
 * series would push them to the cloud for no reason.
 *
 * [stillUrl] null = already queried and TMDB had no image; the row stays anyway so as not to re-ask.
 */
@Entity(tableName = "episode_still")
data class EpisodeStillEntity(
    @PrimaryKey val episodeId: String,
    val stillUrl: String? = null,
    val fetchedAt: Long = 0,
    /**
     * The chapter's title per TMDB. Cached here and not in `episodes` for the same reason as
     * [stillUrl]: it's derivable data, and that table has sync triggers.
     * Null = already queried and there was no title (or the row predates v16).
     */
    val title: String? = null,
    /**
     * The chapter's synopsis per TMDB. Lives here and not in `episodes` for the same reason as
     * [stillUrl] and [title]: it's derivable data and that table has sync triggers. Null = couldn't be resolved.
     */
    val overview: String? = null,
)

/**
 * The captured frame of a chapter. The JPEG is NOT here: it lives in `filesDir/frames/` (see
 * [com.arkiv.player.thumbnails.FrameStore]) and this row is the index.
 *
 * `updatedAt` and `deleted` exist since day one even though phase 1 doesn't sync: they're the
 * clock and the tombstone phase 2 will use, and adding them later would force another migration.
 */
@Entity(tableName = "episode_frame")
data class EpisodeFrameEntity(
    @PrimaryKey val episodeId: String,
    /** Which point of the chapter the frame is from. */
    val positionMs: Long,
    val capturedAt: Long,
    val updatedAt: Long = 0,
    val deleted: Int = 0,
    /**
     * URL of the file that the now-removed cloud upload filled in when a row was adopted from
     * another device and the JPEG hadn't been downloaded yet. Null = the frame is local (captured
     * here) or already downloaded. Unused today: nothing writes a non-null value anymore, now that
     * the cloud sync is gone.
     */
    val remoteUrl: String? = null,
    /**
     * 1 = this row was adopted from ANOTHER device (written by `CloudSyncManager.mergeFrame`, in
     * this branch's now-removed cloud sync); 0 = it was born here (captured by
     * [com.arkiv.player.thumbnails.FrameCapturer] or sealed by the destructor).
     *
     * Exists so that whoever RECEIVES a frame doesn't upload it again. An adopted row kept the
     * `updatedAt` of the other device, which was past this device's push cursor, so the next pass
     * would re-push it; and since the JPEG was already on disk by then, the push would re-upload
     * the SAME BYTES. That made each frame cross the network twice, changed the file's name on the
     * server without changing `updatedAt` (leaving a third device with a `remoteUrl` that 404s
     * forever), and, if the echo arrived after a fresh capture of the original, made the record
     * REGRESS to the old frame.
     *
     * It's a LOCAL field that never traveled to PocketBase. Remembering it in memory wasn't
     * enough -the later push could land in a different process run- nor was comparing against the
     * last adopted `updatedAt`: with another device's clock running ahead, a legitimate local
     * capture would fall below that mark and its bytes would never be uploaded.
     *
     * This whole mechanism is dormant in this branch: there's no cloud sync to adopt a row from,
     * so `origenRemoto` is always 0 today. It stays until the Phase 3 column audit.
     */
    val origenRemoto: Int = 0,
)

/**
 * A recommendation generated ON THE DEVICE by
 * [com.arkiv.player.data.recommendations.ForYouGenerator], with Kilo's free models, from the
 * local history, for the home's "Para ti" row. The app DOES write here directly
 * (`RecommendationDao.replace`, called from `AppGraph.forYouGenerator`): there's no PocketBase
 * or sync behind it -- `CloudSyncManager` doesn't exist in this branch.
 *
 * The local key is [id] (the already-resolved source's id, see
 * `com.arkiv.player.data.recommendations.RecommendationSaving.itemIdFor`) and **NOT** [orden]:
 * each generation RECREATES the whole list instead of reusing identity across batches (a port of
 * `arkiv-api/src/arkiv_api/recomendaciones/almacen.py::guardar`) -- `RecommendationDao.replace`
 * buries (`deleted=true`) the current ones with the SAME `updatedAt` and only then inserts the
 * new ones. Two different generations can share the same `orden` (0..9) with different `id`s; if
 * `orden` were the PK, the new row's `upsert` (`OnConflictStrategy.REPLACE`) would overwrite the
 * old row with that same `orden` even if they were completely different works.
 */
@Entity(tableName = "recomendaciones")
data class RecommendationEntity(
    @PrimaryKey val id: String,
    /** Can come in as 0 (a candidate with no confirmed `tmdbId`): a legitimate value, not an absence. */
    val tmdbId: Int,
    /** "movie" | "tv". */
    val tipo: String,
    val titulo: String,
    /** Can come in empty, same as [tmdbId]. */
    val posterUrl: String,
    /** The phrase explaining why it's recommended (e.g. "porque terminaste Dragon Ball"). */
    val porque: String,
    /**
     * The source already resolved for playback, built on the device by the verification cascade
     * (see [com.arkiv.player.data.recommendations.ForYouVerification]).
     */
    val ref: String,
    /** 0..9 position to order the row. NOT identity -- see the class's KDoc. */
    val orden: Int,
    val generadoAt: Long,
    /** Sync: last-modification clock (LWW) and delete tombstone. */
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)
