package com.arkiv.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Combined row for the home's "Continue watching" row. */
data class ContinueRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    val itemThumbnailUrl: String,
    /** The item's synopsis (not the episode's); null on items added with no metadata. */
    val itemDescription: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    /**
     * Episode still and title from TMDB. Written by two different paths, not one:
     * `ensureEpisodeStills` (everything except Magis -- Ditu today, plus legacy torrent/web/archive
     * rows saved before this branch's pruning -- by asking TMDB) and, for Magis,
     * `addMagisSeason`/`addMagisSource`, with what the gateway already matched against TMDB when it
     * handed over the chapters. Null if the chapter has no row in `episode_still` (e.g. a movie) or
     * if the data couldn't be resolved.
     */
    val stillUrl: String? = null,
    val episodeTitle: String? = null,
    /**
     * Chapter numbering, for the home hero's data line (see
     * [com.arkiv.player.ui.ChapterLabel.heroLine]). `season`/`episode` are null when the
     * file name declared no numbering; then `orderIndex` decides, which does NOT mean the same
     * thing across every source — [com.arkiv.player.data.EncodedNumbering] handles that, and
     * to decide it also needs `itemId` (already above) and `section`.
     */
    val season: Int? = null,
    val episode: Int? = null,
    val orderIndex: Int = 0,
    /** The episode's section ("Temporada 1" on sources that number, "" or the folder if not). */
    val section: String = "",
    /**
     * How many live episodes the item has. Feeds into [isMovie] together with [categoryOverride];
     * not used alone, because a freshly added standalone chapter (Magis, web, catalog torrent,
     * anime) also gives 1 and is NOT a movie (see [categoryOverride]).
     */
    val episodeCount: Int = 0,
    /**
     * The item's manual override ("movie"/"series"/null), same as `items.categoryOverride`.
     * Every source with chapters writes it as "series" from the first chapter on (see
     * `MagisEntities`), precisely so [isMovie] doesn't mistake that first chapter for a movie
     * while `episodeCount` is still 1.
     */
    val categoryOverride: String? = null,
    /**
     * On-disk path of the captured frame, or null if the chapter doesn't have one yet. Wins over
     * `stillUrl` and the rest: see [com.arkiv.player.thumbnails.ThumbnailChoice].
     *
     * Does NOT come from the query: the file name is derived from the episodeId by hash, so the
     * only source of truth is the disk. The repository fills it in when mapping.
     */
    val framePath: String? = null,
) {
    /** Same rule as [LibraryRow.isMovie]: manual override if it exists, otherwise detection by count. */
    val isMovie: Boolean get() = when (categoryOverride) {
        "movie" -> true
        "series" -> false
        else -> episodeCount <= 1
    }
}

/**
 * A chapter's progress with the item it belongs to and the chapter that comes NEXT in the list.
 *
 * The "next" one comes resolved from SQL because [com.arkiv.player.data.ContinueWatchingRule] needs it to
 * offer the chapter that follows the last one you finished, and pulling each series' whole
 * chapter list into memory to figure it out would mean fetching thousands of rows to use one.
 */
data class ProgressWithNextRow(
    val episodeId: String,
    val itemId: String,
    val positionMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    /** The same item's next chapter, or null if this is the last one. */
    val siguienteEpisodeId: String?,
)

/** Raw row to decide which series to ask about new chapters. See `SeriesToCheck`. */
data class SeriesWithProgressRow(
    val itemId: String,
    val source: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** What's been watched of an item, for the TV library's "Ya visto" section. */
data class WatchedRow(
    val itemId: String,
    val episodios: Int,
    val ultimoVistoMs: Long,
)

/** When something from an item was last played, to order the library. */
data class LastPlayedRow(
    val itemId: String,
    val ultimaMs: Long,
)

/** Summary of an item for the library grid. */
data class LibraryRow(
    val identifier: String,
    val title: String,
    /** The item's synopsis; null on the ones added with no metadata (web, standalone magnet). */
    val description: String?,
    val thumbnailUrl: String,
    val episodeCount: Int,
    val durationSeconds: Double,
    val addedAt: Long,
    val categoryOverride: String?,
    val source: String,
    /** How many episodes were shown to the user last time. Null = never. See `NewEpisodeCounter`. */
    val episodiosVistosEnLista: Int? = null,
    /**
     * The work this item IS, according to TMDB. Filled in when it's added from search, or by
     * [com.arkiv.player.data.gateway.repairMagisIdentity]'s title canonization for Magis items
     * that came in without it (0 is treated the same as absent).
     *
     * Exists here because it's the key the library is missing to group by: a standalone chapter
     * saved with the chapter's own title ("T1 - E7: Construido por los hombres") doesn't match
     * any TMDB search, so `artwork` never resolves anything for it. See [LibraryGrouping].
     */
    val tmdbId: Int? = null,
    /**
     * "tv" or "movie" per the work this item IS, verified by the gateway against TMDB. Empty when
     * nobody knows: better a gap than a made-up type.
     *
     * NOT [isMovie] and doesn't replace it: `isMovie` still decides what happens on tapping the
     * card (a movie plays directly, a series opens the list). This only tells [LibraryGrouping]
     * whether the `tmdbId` is a series', because grouping by a movie's id merges different works.
     */
    val tipo: String? = null,
) {
    /** Manual override if it exists; otherwise automatic detection (1 video = movie). */
    val isMovie: Boolean get() = when (categoryOverride) {
        "movie" -> true
        "series" -> false
        else -> episodeCount <= 1
    }
}

/** A playback with its chapter and its item, for the "Para ti" history. Read-only. */
data class HistoryRow(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long,
    val episodio: Int?,
    val itemId: String,
    val titulo: String,
    val tituloCanonico: String?,
    val tipo: String?,
    val categoryOverride: String?,
    val tmdbId: Int?,
)

/**
 * Rows changed since a cursor, for the sync engine's push side, which pages ascending by
 * `updatedAt` so a page boundary never skips a row that arrives between two pushes.
 *
 * Kept as `const val`s (one per synced table) and not inlined into the `@Query` annotations so
 * [ChangedSinceQueryTest] can run the exact same SQL against real SQLite -- same reasoning as
 * [QUERY_ACTIVE_RECOMMENDATIONS].
 */
internal const val QUERY_ITEMS_SINCE =
    "SELECT * FROM items WHERE updatedAt > :cursor ORDER BY updatedAt ASC"

/** See [QUERY_ITEMS_SINCE]. */
internal const val QUERY_EPISODES_SINCE =
    "SELECT * FROM episodes WHERE updatedAt > :cursor ORDER BY updatedAt ASC"

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: ItemEntity)

    /**
     * Records how many episodes were shown to the user, which is what turns off the "new
     * chapters" badge. Done with a point UPDATE and not with `upsertItem` on purpose: the upsert
     * is a REPLACE and would overwrite the rest of the row with whatever the caller has in memory.
     */
    @Query("UPDATE items SET episodiosVistosEnLista = :count WHERE identifier = :itemId")
    suspend fun markEpisodesSeen(itemId: String, count: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE itemId = :itemId")
    suspend fun deleteEpisodesOf(itemId: String)

    @Transaction
    suspend fun replaceItem(item: ItemEntity, episodes: List<EpisodeEntity>) {
        upsertItem(item)
        deleteEpisodesOf(item.identifier)
        upsertEpisodes(episodes)
    }

    @Query("SELECT * FROM items WHERE identifier = :itemId")
    suspend fun getItem(itemId: String): ItemEntity?

    @Query("UPDATE items SET categoryOverride = :value WHERE identifier = :itemId")
    suspend fun updateCategoryOverride(itemId: String, value: String?)

    /**
     * Rename by hand. Clears `tituloCanonico` on purpose: what the person wrote is what's shown,
     * and if the canonical one were left set, the library's query (which prefers it) would keep
     * showing TMDB's name — the rename wouldn't show up anywhere.
     */
    @Query(
        "UPDATE items SET title = :title, tituloCanonico = NULL, updatedAt = :updatedAt " +
            "WHERE identifier = :itemId",
    )
    suspend fun updateTitle(itemId: String, title: String, updatedAt: Long)

    @Query(
        """
        SELECT i.identifier, COALESCE(NULLIF(TRIM(i.tituloCanonico), ''), i.title) AS title, i.description, i.thumbnailUrl,
               (SELECT COUNT(*) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS episodeCount,
               (SELECT COALESCE(SUM(e.durationSeconds), 0) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS durationSeconds,
               i.addedAt, i.categoryOverride, i.source, i.episodiosVistosEnLista, i.tmdbId, i.tipo
        FROM items i
        WHERE i.deleted = 0
        ORDER BY i.addedAt DESC
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    /**
     * The library's series with just enough to decide which ones to ask whether a new chapter
     * came out: source, how many episodes they have and when something of theirs last played.
     *
     * The `MAX(lastPlayedAt)` is that of ANY episode of the series: whichever one you're on
     * doesn't matter, what matters is that you're watching it. `LEFT JOIN` so a series with no
     * progress shows up with 0 and the pure filter discards it, instead of disappearing here
     * (see [SeriesToCheck]).
     */
    @Query(
        """
        SELECT i.identifier AS itemId, i.source AS source,
               (SELECT COUNT(*) FROM episodes e WHERE e.itemId = i.identifier AND e.deleted = 0) AS episodios,
               COALESCE((SELECT MAX(p.lastPlayedAt) FROM playback p
                         JOIN episodes e2 ON e2.id = p.episodeId
                         WHERE e2.itemId = i.identifier AND p.deleted = 0), 0) AS ultimoVistoMs
        FROM items i
        WHERE i.deleted = 0
        """
    )
    suspend fun seriesWithProgress(): List<SeriesWithProgressRow>

    @Query("SELECT * FROM items WHERE identifier = :itemId")
    fun observeItem(itemId: String): Flow<ItemEntity?>

    // deleted = 0 on both: a deleted episode stays in the table as a tombstone (so the deletion
    // propagates through sync), but it isn't part of the series the user imported — not to list
    // it, not to count it, not to navigate to the next one.
    @Query("SELECT * FROM episodes WHERE itemId = :itemId AND deleted = 0 ORDER BY orderIndex ASC")
    fun observeEpisodes(itemId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisode(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE itemId = :itemId AND deleted = 0 ORDER BY orderIndex ASC")
    suspend fun getEpisodesOf(itemId: String): List<EpisodeEntity>

    @Query("SELECT * FROM items")
    suspend fun getAllItems(): List<ItemEntity>

    /** Soft delete: sets the tombstone; the trigger bumps updatedAt so it propagates. */
    @Query("UPDATE items SET deleted = 1 WHERE identifier = :itemId")
    suspend fun softDeleteItem(itemId: String)

    @Query("UPDATE episodes SET deleted = 1 WHERE itemId = :itemId")
    suspend fun softDeleteEpisodesOf(itemId: String)

    /**
     * A single episode. Used by `ArkivRepository.addMagisSeason` to sweep the one a save shaped
     * as a movie left over a series (see `MagisEntities.movieEpisodeId`).
     */
    @Query("UPDATE episodes SET deleted = 1 WHERE id = :episodeId")
    suspend fun softDeleteEpisode(episodeId: String)

    /** Sync push: items touched after [cursor], oldest first. See [QUERY_ITEMS_SINCE]. */
    @Query(QUERY_ITEMS_SINCE)
    suspend fun getItemsSince(cursor: Long): List<ItemEntity>

    /** Sync push: episodes touched after [cursor], oldest first. See [QUERY_EPISODES_SINCE]. */
    @Query(QUERY_EPISODES_SINCE)
    suspend fun getEpisodesSince(cursor: Long): List<EpisodeEntity>
}

/** See [QUERY_ITEMS_SINCE]. Directly exercised by [ChangedSinceQueryTest]. */
internal const val QUERY_PLAYBACK_SINCE =
    "SELECT * FROM playback WHERE updatedAt > :cursor ORDER BY updatedAt ASC"

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playback: PlaybackEntity)

    @Query("SELECT * FROM playback WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): PlaybackEntity?

    @Query("SELECT * FROM playback WHERE episodeId = :episodeId")
    fun observe(episodeId: String): Flow<PlaybackEntity?>

    /**
     * All live progress, with each one's next chapter, for
     * [com.arkiv.player.data.ContinueWatchingRule] to build the "Continue watching" row.
     *
     * Does NOT filter by `watched` or by position, on purpose: filtering here was exactly the
     * bug. The old query asked for `watched = 0`, so of a series watched daily only the ABANDONED
     * chapters survived and the row ended up offering a chapter from thirty back (Dragon Ball on
     * device, 2026-08-13: e136 finished last night, the card showed e104). To know where you're
     * at you also have to see what's finished, which is what says where you left off; the
     * filtering is done by the rule, which has the whole series' context, not the row-by-row query.
     *
     * The tiebreak by `id` in the next-chapter subselect is NOT cosmetic: two chapters with the
     * same `orderIndex` (happens when the source doesn't number) would make `> orderIndex` skip
     * over the sibling.
     */
    @Query(
        """
        SELECT p.episodeId AS episodeId, e.itemId AS itemId,
               p.positionMs AS positionMs, p.watched AS watched, p.lastPlayedAt AS lastPlayedAt,
               (SELECT e2.id FROM episodes e2
                 WHERE e2.itemId = e.itemId AND e2.deleted = 0
                   AND (e2.orderIndex > e.orderIndex
                        OR (e2.orderIndex = e.orderIndex AND e2.id > e.id))
                 ORDER BY e2.orderIndex ASC, e2.id ASC
                 LIMIT 1) AS siguienteEpisodeId
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        WHERE p.deleted = 0 AND e.deleted = 0 AND i.deleted = 0
        """
    )
    fun observeProgressWithNext(): Flow<List<ProgressWithNextRow>>

    /**
     * The screen data of the chapters [com.arkiv.player.data.ContinueWatchingRule] already chose.
     *
     * Hangs off `episodes` and NOT `playback`, with the progress in a LEFT JOIN, because the
     * chosen chapter can be one you never touched (the one after what you finished): there's no
     * `playback` row there and the card goes with the bar at zero.
     *
     * `lastPlayedAt` comes out as 0 in that case; the repository overwrites it with the anchor's,
     * which is what orders the row (see `observeContinueWatching`).
     */
    @Query(
        """
        SELECT e.id AS episodeId, e.itemId AS itemId, COALESCE(NULLIF(TRIM(i.tituloCanonico), ''), i.title) AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               i.thumbnailUrl AS itemThumbnailUrl, i.description AS itemDescription,
               COALESCE(p.positionMs, 0) AS positionMs, COALESCE(p.durationMs, 0) AS durationMs,
               COALESCE(p.lastPlayedAt, 0) AS lastPlayedAt,
               s.stillUrl AS stillUrl, s.title AS episodeTitle,
               e.season AS season, e.episode AS episode, e.orderIndex AS orderIndex,
               e.section AS section,
               (SELECT COUNT(*) FROM episodes e2 WHERE e2.itemId = e.itemId AND e2.deleted = 0) AS episodeCount,
               i.categoryOverride AS categoryOverride
        FROM episodes e
        JOIN items i ON i.identifier = e.itemId
        LEFT JOIN playback p ON p.episodeId = e.id AND p.deleted = 0
        LEFT JOIN episode_still s ON s.episodeId = e.id
        WHERE e.id IN (:episodeIds) AND e.deleted = 0 AND i.deleted = 0
        """
    )
    suspend fun continueWatchingRows(episodeIds: List<String>): List<ContinueRow>

    /**
     * Items with already-watched chapters, with how many and when the last one was.
     *
     * Twin of [observeContinueWatching] but backwards (`watched = 1`): what comes out of
     * "Continue watching" on finishing it has to land somewhere, and until now it landed nowhere.
     *
     * Doesn't do a `JOIN items`: the live-item filter is applied by `LibraryWatched.cross`, which
     * already receives the groups (and the groups already exclude the deleted ones). Adding the
     * join here would duplicate that rule in two places.
     */
    @Query(
        """
        SELECT e.itemId AS itemId, COUNT(*) AS episodios, MAX(p.lastPlayedAt) AS ultimoVistoMs
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        WHERE p.watched = 1 AND p.deleted = 0 AND e.deleted = 0
        GROUP BY e.itemId
        """
    )
    fun observeWatched(): Flow<List<WatchedRow>>

    /**
     * When ANY chapter of each item was last played, for the library's order (see
     * [com.arkiv.player.data.library.LibraryOrder]).
     *
     * Twin of [observeWatched] but WITHOUT the `watched = 1` filter: here a finished chapter
     * counts the same as one left halfway. If it only counted the finished ones, a series you're
     * watching right now wouldn't move up until you finish the chapter; if it only counted the
     * halfway ones, it would fall off the top right as you finish it.
     *
     * Doesn't do a `JOIN items`: the live-item filter is applied by whoever crosses this map
     * against the library, which already excludes the deleted ones. Same criterion as [observeWatched].
     */
    @Query(
        """
        SELECT e.itemId AS itemId, MAX(p.lastPlayedAt) AS ultimaMs
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        WHERE p.deleted = 0 AND e.deleted = 0
        GROUP BY e.itemId
        """
    )
    fun observeLastPlayed(): Flow<List<LastPlayedRow>>

    @Query("SELECT * FROM playback WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observePlaybackForItem(itemId: String): Flow<List<PlaybackEntity>>

    /** The last things played, with their item, from most recent to oldest. For "Para ti". */
    @Query(
        """
        SELECT p.episodeId AS episodeId, p.positionMs AS positionMs, p.durationMs AS durationMs,
               p.watched AS watched, p.lastPlayedAt AS lastPlayedAt, e.episode AS episodio,
               i.identifier AS itemId, i.title AS titulo, i.tituloCanonico AS tituloCanonico,
               i.tipo AS tipo, i.categoryOverride AS categoryOverride, i.tmdbId AS tmdbId
        FROM playback p
        JOIN episodes e ON e.id = p.episodeId
        JOIN items i ON i.identifier = e.itemId
        WHERE p.deleted = 0 AND e.deleted = 0 AND i.deleted = 0
        ORDER BY p.lastPlayedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentHistory(limit: Int): List<HistoryRow>

    /** Sync push: playback touched after [cursor], oldest first. See [QUERY_PLAYBACK_SINCE]. */
    @Query(QUERY_PLAYBACK_SINCE)
    suspend fun getPlaybackSince(cursor: Long): List<PlaybackEntity>
}

/** A download combined with the episode's data, to show on screen. */
data class DownloadRow(
    val episodeId: String,
    val itemId: String,
    val itemTitle: String,
    val displayName: String,
    val thumbPath: String?,
    /** The item's poster (`items.thumbnailUrl`), used as a fallback when [thumbPath] is null. */
    val itemThumbnailUrl: String,
    val state: String,
    val progress: Float,
    val localUri: String?,
    val bytes: Long,
    val source: String,
    val error: String?,
    val bytesDone: Long,
    /**
     * Where the chapter came from (`episodes.torrentData`): the page URL for web, the torrent data
     * for torrent, null for archive.org. Those sources were all deleted in this branch's pruning.
     * It has no reader today; it stays until the Phase 3 column audit because it projects the
     * `torrentData` column.
     */
    val sourceRef: String? = null,
)

/** See [QUERY_ITEMS_SINCE]. */
internal const val QUERY_MARKERS_SINCE =
    "SELECT * FROM skip_markers WHERE updatedAt > :cursor ORDER BY updatedAt ASC"

@Dao
interface SkipMarkerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(marker: SkipMarkerEntity)

    /** The WHOLE series' marker (the one set by hand in the dialog): empty `episodeId`. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun get(itemId: String): SkipMarkerEntity?

    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    fun observe(itemId: String): Flow<SkipMarkerEntity?>

    /** The chapter's and the series', in a single query. `ChapterMarker.choose` decides which wins. */
    @Query("SELECT * FROM skip_markers WHERE itemId = :itemId AND episodeId IN (:episodeId, '') AND deleted = 0")
    fun observeForChapter(itemId: String, episodeId: String): Flow<List<SkipMarkerEntity>>

    /** One row by its own key (PK). Used by [com.arkiv.player.data.ArkivRepository.getSkipMarker] to read the marker for an exact scope (chapter or whole series). */
    @Query("SELECT * FROM skip_markers WHERE id = :id")
    suspend fun getById(id: String): SkipMarkerEntity?

    /** Deletes the SERIES' marker set by hand (empty `episodeId`); chapter ones aren't touched. */
    @Query("DELETE FROM skip_markers WHERE itemId = :itemId AND episodeId = ''")
    suspend fun delete(itemId: String)

    @Query("SELECT * FROM skip_markers")
    suspend fun getAll(): List<SkipMarkerEntity>

    /** Sync push: markers touched after [cursor], oldest first. See [QUERY_MARKERS_SINCE]. */
    @Query(QUERY_MARKERS_SINCE)
    suspend fun getMarkersSince(cursor: Long): List<SkipMarkerEntity>
}

/** See [QUERY_ITEMS_SINCE]. */
internal const val QUERY_LIVE_FAVORITES_SINCE =
    "SELECT * FROM live_favorites WHERE updatedAt > :cursor ORDER BY updatedAt ASC"

@Dao
interface LiveFavoriteDao {
    @Query("SELECT * FROM live_favorites WHERE deleted = 0 ORDER BY numero")
    fun flowAll(): Flow<List<LiveFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(f: LiveFavoriteEntity)

    // Doesn't touch `updatedAt` here: leaving it alone is what lets the SyncTriggers UPDATE
    // trigger's guard (`WHEN NEW.updatedAt = OLD.updatedAt`) fire and reseal it with a fresh clock
    // -- same pattern as `softDeleteItem`. Nothing reads that clock anymore (see SyncTriggers),
    // but the trigger still runs on every local write.
    @Query("UPDATE live_favorites SET deleted = 1 WHERE code = :code")
    suspend fun delete(code: String)

    @Query("SELECT EXISTS(SELECT 1 FROM live_favorites WHERE code = :code AND deleted = 0)")
    suspend fun isFavorite(code: String): Boolean

    // --- Sync (same pattern as skip_markers) ---
    @Query("SELECT * FROM live_favorites")
    suspend fun getAll(): List<LiveFavoriteEntity>

    /** Sync push: favorites touched after [cursor], oldest first. See [QUERY_LIVE_FAVORITES_SINCE]. */
    @Query(QUERY_LIVE_FAVORITES_SINCE)
    suspend fun getLiveFavoritesSince(cursor: Long): List<LiveFavoriteEntity>

    /** By-PK read, for [com.arkiv.player.data.sync.SyncApply]'s local-row lookup before merging. */
    @Query("SELECT * FROM live_favorites WHERE code = :code")
    suspend fun get(code: String): LiveFavoriteEntity?
}

/** See [QUERY_ITEMS_SINCE]. */
internal const val QUERY_LIVE_RECENTS_SINCE =
    "SELECT * FROM live_recents WHERE updatedAt > :cursor ORDER BY updatedAt ASC"

@Dao
interface LiveRecentDao {
    @Query("SELECT * FROM live_recents ORDER BY vistoAt DESC LIMIT :limit")
    fun flowRecent(limit: Int = 20): Flow<List<LiveRecentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(r: LiveRecentEntity)

    // --- Sync (same pattern as skip_markers) ---
    @Query("SELECT * FROM live_recents")
    suspend fun getAll(): List<LiveRecentEntity>

    /** Sync push: recents touched after [cursor], oldest first. See [QUERY_LIVE_RECENTS_SINCE]. */
    @Query(QUERY_LIVE_RECENTS_SINCE)
    suspend fun getLiveRecentsSince(cursor: Long): List<LiveRecentEntity>

    /** By-PK read, for [com.arkiv.player.data.sync.SyncApply]'s local-row lookup before merging. */
    @Query("SELECT * FROM live_recents WHERE code = :code")
    suspend fun get(code: String): LiveRecentEntity?

    /**
     * One-time 2026-08-14 purge: adult channels that stayed recorded from BEFORE
     * `abrirCanalActual` stopped recording them. They showed up in the home's "Canales en vivo"
     * row, in plain sight of anyone.
     *
     * Deletes EVERYTHING and not just the adult ones because the device has no way to know which
     * ones were: the recents store code and name, not the category. And it costs nothing — the
     * cloud was already left clean, so the next sync repopulates the list with the legitimate ones.
     */
    @Query("DELETE FROM live_recents")
    suspend fun deleteAll()
}

@Dao
interface LiveChannelCacheDao {
    @Query("SELECT * FROM live_channels_cache WHERE categoria = :category ORDER BY numero")
    suspend fun byCategory(category: Int): List<LiveChannelCacheEntity>

    /**
     * Cached rows of a specific list of channels (by `code`), with no category filter -- to
     * enrich with logo/number data that arrives from another source that carries no category of
     * its own (the home row's "recents", see `recentChannelsForHome` in
     * `ui/live/RecentLiveChannels.kt`). Can return more than one row per `code` (a channel can be
     * cached in several of the portal's categories): logo/number don't change between categories,
     * so the caller doesn't care which one it gets.
     */
    @Query("SELECT * FROM live_channels_cache WHERE code IN (:codes)")
    suspend fun byCodes(codes: List<String>): List<LiveChannelCacheEntity>

    @Query("DELETE FROM live_channels_cache WHERE categoria = :category")
    suspend fun clear(category: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(rows: List<LiveChannelCacheEntity>)

    @Transaction
    suspend fun replace(category: Int, rows: List<LiveChannelCacheEntity>) {
        clear(category)
        save(rows)
    }
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET state = :state, error = :error WHERE episodeId = :episodeId")
    suspend fun updateState(episodeId: String, state: String, error: String?)

    /**
     * Progress WITHOUT touching `state`. This query used to also write the state, and since the
     * progress callback arrives several times a second, the (web) staging phase could never stay
     * `staging`: the first tick returned it to `downloading`. The state is handled by whoever
     * knows the phase (the worker and the strategy), not the byte counter.
     */
    @Query(
        "UPDATE downloads SET progress = :progress, bytesDone = :bytesDone, bytes = :bytes " +
            "WHERE episodeId = :episodeId"
    )
    suspend fun updateProgress(episodeId: String, progress: Float, bytesDone: Long, bytes: Long)

    /** Reason for the last stumble without changing the state (a row that's going to retry on its own). */
    @Query("UPDATE downloads SET error = :error WHERE episodeId = :episodeId")
    suspend fun setError(episodeId: String, error: String?)

    /**
     * Fixes an already-saved row's `source` (i.e. its strategy). Needed for rows that got queued
     * with the wrong strategy: "Reintentar" keeps the row as-is, so without this they'd keep
     * failing the same way forever. See `DownloadSource`.
     */
    @Query("UPDATE downloads SET source = :source WHERE episodeId = :episodeId")
    suspend fun updateSource(episodeId: String, source: String)

    /**
     * Writes the BARE path to `filePath` (not a `file://` in `localUri`): `localUri` is the
     * historical format the system's `DownloadManager` used to leave and stays only for old rows.
     * Whoever resolves "where's the file?" for the two columns —and checks that it exists— is
     * `LocalLibrary.fileFor`, the ONLY reader of this.
     */
    @Query(
        "UPDATE downloads SET state = 'completed', progress = 1.0, filePath = :filePath, error = NULL " +
            "WHERE episodeId = :episodeId"
    )
    suspend fun markCompleted(episodeId: String, filePath: String)

    @Query("UPDATE downloads SET sizeConfirmed = 1, state = 'queued', error = NULL WHERE episodeId = :episodeId")
    suspend fun markConfirmed(episodeId: String)

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)

    /**
     * Origin of everything ALREADY downloaded on the device, so as not to download the same
     * chapter twice when the series ended up saved under two different items (see
     * [com.arkiv.player.data.local.DuplicateDownloadPolicy], which is the one that decides). The
     * `torrentFileIndex` comes from `episodes` because the episodeId only carries the infohash,
     * not the file chosen within the torrent.
     */
    @Query(
        """
        SELECT d.episodeId AS episodeId, e.torrentFileIndex AS torrentFileIndex
        FROM downloads d
        JOIN episodes e ON e.id = d.episodeId
        WHERE d.state = 'completed'
        """
    )
    suspend fun completedOrigins(): List<com.arkiv.player.data.local.EpisodeOrigin>

    /**
     * Files still referenced by OTHER rows. Happens when the worker adopts a twin's file instead
     * of re-downloading it: deleting that file when removing either of the two rows would leave
     * the other saying "Listo" over something that's no longer there (see
     * `DuplicateDownloadPolicy.deletablePaths`).
     *
     * Only looks at `filePath` and not the historical `localUri`: whoever adopts a file always
     * goes through `markCompleted`, which writes `filePath`. A `localUri` can only be the ADOPTED
     * side, and that side is already protected because the adopter copied that same path to its
     * `filePath`.
     */
    @Query("SELECT filePath FROM downloads WHERE filePath IS NOT NULL AND episodeId != :exceptEpisodeId")
    suspend fun filePathsReferencedByOthers(exceptEpisodeId: String): List<String>

    @Query(
        """
        SELECT d.episodeId AS episodeId, e.itemId AS itemId, COALESCE(NULLIF(TRIM(i.tituloCanonico), ''), i.title) AS itemTitle,
               e.displayName AS displayName, e.thumbPath AS thumbPath,
               i.thumbnailUrl AS itemThumbnailUrl,
               d.state AS state, d.progress AS progress, d.localUri AS localUri, d.bytes AS bytes,
               d.source AS source, d.error AS error, d.bytesDone AS bytesDone,
               e.torrentData AS sourceRef
        FROM downloads d
        JOIN episodes e ON e.id = d.episodeId
        JOIN items i ON i.identifier = e.itemId
        ORDER BY d.createdAt DESC
        """
    )
    fun observeDownloadRows(): Flow<List<DownloadRow>>
}

@Dao
interface ArtworkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artwork: ArtworkEntity)

    @Query("SELECT * FROM artwork WHERE itemId = :itemId")
    suspend fun get(itemId: String): ArtworkEntity?

    @Query("SELECT * FROM artwork")
    fun observeAll(): Flow<List<ArtworkEntity>>
}

@Dao
interface EpisodeStillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stills: List<EpisodeStillEntity>)

    @Query("SELECT * FROM episode_still WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    suspend fun forItem(itemId: String): List<EpisodeStillEntity>

    @Query("SELECT * FROM episode_still WHERE episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observeForItem(itemId: String): Flow<List<EpisodeStillEntity>>
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history WHERE kind = :kind ORDER BY atMs DESC LIMIT :limit")
    suspend fun recent(kind: String, limit: Int = 20): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE kind = :kind ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(kind: String, limit: Int = 10): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE kind = :kind AND lower(query) = lower(:query)")
    suspend fun deleteOne(kind: String, query: String)

    @Query("DELETE FROM search_history WHERE kind = :kind")
    suspend fun clearKind(kind: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface RecentTitleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentTitleEntity)

    @Query("SELECT * FROM recent_titles ORDER BY atMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<RecentTitleEntity>>

    @Query("DELETE FROM recent_titles WHERE id = :id")
    suspend fun deleteOne(id: String)

    @Query("DELETE FROM recent_titles")
    suspend fun clear()

    /** Deletes whatever's past the cap. Each row drags along a poster URL: worth pruning. */
    @Query("DELETE FROM recent_titles WHERE id NOT IN (SELECT id FROM recent_titles ORDER BY atMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface EpisodeFrameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(frame: EpisodeFrameEntity)

    @Query("SELECT * FROM episode_frame WHERE episodeId = :episodeId AND deleted = 0")
    suspend fun get(episodeId: String): EpisodeFrameEntity?

    /**
     * Same as [get] but WITHOUT the `deleted = 0` filter: needed by `FrameDestroyer.destroy`
     * to be idempotent -- it needs to know whether the row is ALREADY a tombstone (and skip
     * rewriting it) or is only now going from live to deleted.
     *
     * Don't use it for anything else: every other caller DOES want a deleted row to count as
     * "there's no frame".
     */
    @Query("SELECT * FROM episode_frame WHERE episodeId = :episodeId")
    suspend fun getIncludingDeleted(episodeId: String): EpisodeFrameEntity?

    /**
     * Rows (not deleted) of an item's chapters, for a series' detail. Same shape as
     * [EpisodeStillDao.observeForItem]: the repository only uses it as the Flow's TRIGGER (see
     * `ArkivRepository.observeEpisodeFrames`), not as the path's source.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0 AND episodeId IN (SELECT id FROM episodes WHERE itemId = :itemId)")
    fun observeForItem(itemId: String): Flow<List<EpisodeFrameEntity>>

    /**
     * ALL live rows, as the TRIGGER for the "Continue watching" Flow.
     *
     * Exists because of a hole in the home screen: `PlaybackDao.observeContinueWatching` touches
     * `playback`, `episodes`, `items` and `episode_still`, but not `episode_frame`. Since Room
     * invalidates by table, the frame that [com.arkiv.player.thumbnails.FrameCapturer] saves (file
     * + `episode_frame` row) never notified that query: the card stayed on the TMDB still until
     * something else changed.
     *
     * Returns the whole list and not a `COUNT`: the content doesn't matter -the repository only
     * uses it as a signal that "something changed in `episode_frame`"- but a row query has the
     * same shape as [observeForItem] and doesn't hide the real cost.
     */
    @Query("SELECT * FROM episode_frame WHERE deleted = 0")
    fun observeAll(): Flow<List<EpisodeFrameEntity>>

    /**
     * Takes ALL rows in one go, for the logout wipe: there's no chapter list to walk there (the
     * `items`/`episodes` get deleted in the same sweep) and deleting one by one would require
     * reading first what's about to be deleted.
     */
    @Query("DELETE FROM episode_frame")
    suspend fun deleteAll()
}

/**
 * Single source of truth of [RecommendationDao.observeActive]'s "active" query: it's used by the
 * real `@Query` below AND by `RecommendationQueryTest` (which runs it against real SQLite over
 * JDBC, see its KDoc). A Room `@Query` only accepts compile-time constants, so a `const val` is
 * the minimum that lets both parts read the SAME string instead of keeping two hand-maintained
 * copies that can silently drift apart -- which is exactly what used to happen: the test had its
 * own copy of the SQL, and removing the `WHERE deleted = 0` here didn't make it fail.
 */
internal const val QUERY_ACTIVE_RECOMMENDATIONS =
    "SELECT * FROM recomendaciones WHERE deleted = 0 ORDER BY orden ASC"

@Dao
interface RecommendationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: RecommendationEntity)

    /**
     * By `id` (the local key, see [RecommendationEntity]). No `deleted` filter: the query also
     * returns what [replace] already retired with a tombstone.
     */
    @Query("SELECT * FROM recomendaciones WHERE id = :id")
    suspend fun get(id: String): RecommendationEntity?

    /**
     * The active recommendations, in the order
     * [com.arkiv.player.data.recommendations.ForYouGenerator] built, without what's already
     * marked as a tombstone. It's the source of the home's "Para ti" row.
     */
    @Query(QUERY_ACTIVE_RECOMMENDATIONS)
    fun observeActive(): Flow<List<RecommendationEntity>>

    /** How many the "Para ti" row would show right now (same filter as [observeActive]). */
    @Query("SELECT COUNT(*) FROM recomendaciones WHERE deleted = 0")
    suspend fun countActive(): Int

    @Query("UPDATE recomendaciones SET deleted = 1, updatedAt = :now WHERE deleted = 0")
    suspend fun retireActive(now: Long)

    /**
     * Changes the whole row set in one go: it's never left halfway between the old batch and the
     * new one. They're retired with a tombstone and not deleted, same as the rest of the tables
     * with `deleted`.
     */
    @Transaction
    suspend fun replace(new: List<RecommendationEntity>, now: Long) {
        retireActive(now)
        new.forEach { upsert(it) }
    }
}
