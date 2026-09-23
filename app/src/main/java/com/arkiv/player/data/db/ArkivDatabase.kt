package com.arkiv.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ItemEntity::class,
        EpisodeEntity::class,
        PlaybackEntity::class,
        DownloadEntity::class,
        SkipMarkerEntity::class,
        ArtworkEntity::class,
        SearchHistoryEntity::class,
        RecentTitleEntity::class,
        EpisodeStillEntity::class,
        EpisodeFrameEntity::class,
        LiveFavoriteEntity::class,
        LiveRecentEntity::class,
        LiveChannelCacheEntity::class,
        RecommendationEntity::class,
    ],
    version = 29,
    exportSchema = false,
)
abstract class ArkivDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun downloadDao(): DownloadDao
    abstract fun skipMarkerDao(): SkipMarkerDao
    abstract fun artworkDao(): ArtworkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun recentTitleDao(): RecentTitleDao
    abstract fun episodeStillDao(): EpisodeStillDao
    abstract fun episodeFrameDao(): EpisodeFrameDao
    abstract fun liveFavoriteDao(): LiveFavoriteDao
    abstract fun liveRecentDao(): LiveRecentDao
    abstract fun liveChannelCacheDao(): LiveChannelCacheDao
    abstract fun recommendationDao(): RecommendationDao

    companion object {
        @Volatile
        private var instance: ArkivDatabase? = null

        /** v1 -> v2: adds the opening/ending marker table (preserves data). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skip_markers (" +
                        "itemId TEXT NOT NULL PRIMARY KEY, " +
                        "openingStartMs INTEGER, openingEndMs INTEGER, endingStartMs INTEGER)",
                )
            }
        }

        /** v2 -> v3: timestamp on markers (for last-write-wins sync). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skip_markers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 -> v4: manual category override (movie/series) per item. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN categoryOverride TEXT")
            }
        }

        /** v4 -> v5: torrent support (origin + .torrent data + file index). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN source TEXT NOT NULL DEFAULT 'archive'")
                db.execSQL("ALTER TABLE items ADD COLUMN torrentData TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN torrentFileIndex INTEGER")
            }
        }

        /** v5 -> v6: per-episode torrent (series where each chapter is its own torrent, e.g. anime). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN torrentData TEXT")
            }
        }

        /**
         * v6 -> v7: sync metadata. Adds `updatedAt` (LWW clock) + `deleted` (tombstone) to
         * items/episodes/playback (skip_markers already had updatedAt; only +deleted).
         *
         * Triggers: auto-set `updatedAt` on every LOCAL write (so no write "forgets" to mark
         * itself dirty), but RESPECT an explicit value (the cloud merge writes the remote
         * updatedAt ≠ 0, and the trigger leaves it). Room doesn't validate triggers, so they don't
         * interfere with its schema.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("items", "episodes", "playback")) {
                    db.execSQL("ALTER TABLE $t ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $t ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE skip_markers ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")

                val now = "CAST(strftime('%s','now') AS INTEGER)*1000"
                // Seal EXISTING rows with the current time so they get pushed on the first sync
                // (if they were left at updatedAt=0, the push by `updatedAt > cursor` would never
                // pick them up). Done BEFORE creating the triggers so as not to fire them en masse.
                for (t in listOf("items", "episodes", "playback", "skip_markers")) {
                    db.execSQL("UPDATE $t SET updatedAt = $now")
                }
                // (table, PK column)
                for ((t, pk) in listOf("items" to "identifier", "episodes" to "id", "playback" to "episodeId", "skip_markers" to "itemId")) {
                    // Local INSERT (updatedAt stayed at 0) -> seal with the current time.
                    db.execSQL(
                        "CREATE TRIGGER trg_${t}_ins AFTER INSERT ON $t WHEN NEW.updatedAt = 0 " +
                            "BEGIN UPDATE $t SET updatedAt = $now WHERE $pk = NEW.$pk; END",
                    )
                    // Local UPDATE (the writer didn't move updatedAt) -> seal. The cloud merge does move it => skipped.
                    db.execSQL(
                        "CREATE TRIGGER trg_${t}_upd AFTER UPDATE ON $t WHEN NEW.updatedAt = OLD.updatedAt " +
                            "BEGIN UPDATE $t SET updatedAt = $now WHERE $pk = NEW.$pk; END",
                    )
                }
            }
        }

        /**
         * v7 -> v8: seals (updatedAt = now) the rows left at updatedAt=0. Needed for devices that
         * migrated to v7 BEFORE v6->v7's sealing ran: without this, their existing rows
         * (updatedAt=0) would never get picked up by the push (`updatedAt > cursor`) and the
         * library wouldn't upload. Idempotent (only touches updatedAt=0).
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = "CAST(strftime('%s','now') AS INTEGER)*1000"
                for (t in listOf("items", "episodes", "playback", "skip_markers")) {
                    db.execSQL("UPDATE $t SET updatedAt = $now WHERE updatedAt = 0")
                }
            }
        }

        /** v8 -> v9: local table of TMDB art (backdrops per item). Not synced. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS artwork (" +
                        "itemId TEXT NOT NULL PRIMARY KEY, " +
                        "tmdbId INTEGER, tmdbType TEXT, " +
                        "backdropsJson TEXT NOT NULL DEFAULT '[]', " +
                        "fetchedAt INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        /**
         * v9 -> v10: catalog search history (anime/movies). Composite PK (query, kind): the same
         * text on different tabs doesn't overwrite itself.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS search_history (" +
                        "query TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "atMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`, `kind`))",
                )
            }
        }

        /** v10 -> v11: local cache of TMDB stills per chapter (not synced). */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS episode_still (" +
                        "episodeId TEXT NOT NULL, " +
                        "stillUrl TEXT, " +
                        "fetchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(`episodeId`))",
                )
            }
        }

        /**
         * v11 -> v12: local cache of arkiv-offline's library (which episodes are already
         * downloaded on the NUC) + per-series playback preference (NUC vs LIVE).
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS nuc_library_items (" +
                        "itemId INTEGER NOT NULL PRIMARY KEY, seriesId TEXT NOT NULL, " +
                        "season INTEGER NOT NULL, episode INTEGER NOT NULL, status TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, syncedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS series_playback_prefs (" +
                        "seriesId TEXT NOT NULL PRIMARY KEY, preference TEXT NOT NULL, " +
                        "asked INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v12 -> v13: local record of which arkiv-offline `job_id` this device triggered
         * (Task 9, Descargas screen) -- `local_active_jobs` table, dropped in
         * [MIGRATION_28_29] after the NUC pruning (Task 8).
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_active_jobs (" +
                        "jobId INTEGER NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v13 -> v14: `seriesId` on local jobs. Without this column, when a job finishes there's
         * no way to know which series it was for (arkiv-offline doesn't return it in
         * `GET /jobs/<id>`) and the "Terminados" section could only be filled by visiting the
         * series' detail.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_active_jobs ADD COLUMN seriesId TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v14 -> v15: `sourceRef` (the origin pageUrl) in the NUC library cache. Without this
         * column "already downloaded" could only be compared by (season, chapter), and since the
         * NUC stores a single file per episode, the green checkmark showed up on all three sites'
         * packs at once even though the copy came from just one.
         *
         * NULL (no DEFAULT) on purpose: rows already in the cache don't know where they came from,
         * and marking them with a made-up value would make them match the wrong site. With NULL
         * they simply don't match, and the next library refresh fills them in with the real
         * `source_ref` `GET /library` returns.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nuc_library_items ADD COLUMN sourceRef TEXT")
            }
        }

        /**
         * v15 -> v16: item's `tmdbId` and each chapter's (`season`, `episode`), to show the
         * episode's real title instead of the file name ("s01e03").
         *
         * Neither archive.org nor the mirror store the episode's name, only its number: the name
         * has to be asked from TMDB, and that needs both things — which series the item belongs
         * to and which number each file is.
         *
         * All three go NULL with no DEFAULT on purpose: rows that already existed don't know their
         * number or their series, and filling them with a made-up value would make the UI show
         * ANOTHER chapter's title. With NULL they simply fall back to the file name, as before, and
         * fill themselves in the next time the item refreshes.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN tmdbId INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN season INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN episode INTEGER")
                db.execSQL("ALTER TABLE episode_still ADD COLUMN title TEXT")
                // The still cache was filled by distributing chapters by count (see
                // ensureEpisodeStills), a distribution that gets misaligned if there are OVAs or
                // recaps. Now that there's an exact season/chapter it's worth redoing: it's
                // deleted instead of dragging along possibly wrong assignments -- it's derivable
                // cache, it repopulates on its own.
                db.execSQL("DELETE FROM episode_still")
            }
        }

        /**
         * v16 -> v17: the `downloads` table stops being exclusive to archive.org and starts
         * serving all three sources (archive, torrent, web).
         *
         * `source` gets DEFAULT 'archive' on purpose: every row that already exists comes from the
         * only path there was, so that default classifies them correctly with no data touched.
         *
         * `filePath` goes NULL with no DEFAULT: old rows saved the path as a `file://` in
         * `localUri` (what the system's DownloadManager used to return). Making up a filePath for
         * them would break them; with NULL, `LocalLibrary` falls back to `localUri` and what's
         * already downloaded keeps playing.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN source TEXT NOT NULL DEFAULT 'archive'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN filePath TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN bytesDone INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN stagingItemId INTEGER")
                db.execSQL("ALTER TABLE downloads ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN sizeConfirmed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recent_titles (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "kind TEXT NOT NULL, " +
                        "tmdbId INTEGER, " +
                        "anilistId INTEGER, " +
                        "title TEXT NOT NULL, " +
                        "posterUrl TEXT NOT NULL, " +
                        "year TEXT NOT NULL, " +
                        "atMs INTEGER NOT NULL)",
                )
            }
        }

        /**
         * "New chapters" badge: how many episodes the series had the last time its detail was opened.
         *
         * Nullable on purpose, and with no DEFAULT: rows that already exist are left NULL, which
         * means "never looked at" and does NOT paint a badge. With a default of 0, the day this
         * ships every library series would show up marked with all its chapters as if they were
         * new. See [com.arkiv.player.data.newcontent.NewEpisodeCounter].
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN episodiosVistosEnLista INTEGER")
            }
        }

        /**
         * v19 -> v20: the chapter's synopsis, which arrives together with the still and the title
         * from the gateway. `episode_still` is derivable local cache and is NOT among the tables
         * `SyncTriggers` syncs, so this column doesn't touch sync.
         *
         * And the table gets emptied, for exactly the same reason as [MIGRATION_15_16] when it
         * added `title`: the new column comes in as NULL on every old row, and
         * `ensureEpisodeStills` cuts short as soon as each chapter already has a row —it doesn't
         * care that it's half-filled—, so without this DELETE **no series that already had its
         * stills resolved would ever see a synopsis**: the row exists, so nobody asks again.
         * Deleting it loses none of the user's data: it's derivable cache, it repopulates on its
         * own the next time the series is opened.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episode_still ADD COLUMN overview TEXT")
                db.execSQL("DELETE FROM episode_still")
            }
        }

        /**
         * v20 -> v21: favorites and recents of live TV channels (Task 10). Favorites sync LWW
         * with a tombstone -- same schema as `skip_markers` -- and recents LWW with no tombstone
         * (pruned by age, not deleted by hand). The catalog cache (`live_channels_cache`) is local
         * and does NOT sync (see [LiveChannelCacheEntity]): it carries no `updatedAt`/`deleted`
         * because it never goes through [SyncTriggers] or the merge.
         *
         * `live_channels_cache` carries a COMPOSITE PK `(code, categoria)`, not just `code`: the
         * same channel can be in several of the portal's categories, and a simple PK meant caching
         * one category would overwrite (REPLACE) the row of a channel shared with another,
         * leaving it a ghost on going back to that other category from cache with no gateway
         * (finding F5 of the final review). Fixed HERE, in the still-unpublished migration, and
         * not with a v22: see the final wave's report for why the opportunity made sense.
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_favorites (" +
                        "code TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, numero INTEGER NOT NULL, " +
                        "logo TEXT, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_recents (" +
                        "code TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, vistoAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_channels_cache (" +
                        "code TEXT NOT NULL, categoria INTEGER NOT NULL, nombre TEXT NOT NULL, " +
                        "numero INTEGER NOT NULL, logo TEXT, guardadoAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(code, categoria))",
                )
            }
        }

        /** v21 -> v22: captured-frame thumbnails. The JPEG goes to disk; this table is the index. */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS episode_frame (" +
                        "episodeId TEXT NOT NULL PRIMARY KEY, " +
                        "positionMs INTEGER NOT NULL, " +
                        "capturedAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "deleted INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        /**
         * v22 -> v23: where to download the JPEG of a frame that came from another device.
         *
         * Nullable and with no DEFAULT on purpose: rows that already exist are left NULL, which
         * means "it's local, there's nothing to download" — which is exactly the truth for
         * everything captured in phase 1.
         *
         * WATCH the number: on phase 2's branch this migration was 21->22, but on merging it
         * collided with `main`'s 21->22 (the one that CREATES `episode_frame`, renumbered there
         * when phase 1 was integrated). It got moved to 22->23 when resolving the conflict. No
         * device had run the old numbering: phase 2 was never installed on any.
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episode_frame ADD COLUMN remoteUrl TEXT")
            }
        }

        /**
         * v23 -> v24: where the frame's row CAME FROM, so whoever receives it doesn't re-upload it.
         *
         * `DEFAULT 0` = "born on this device", which is the truth for everything that already
         * exists: up to this version the only writer of local frames was the capture. See
         * [EpisodeFrameEntity.origenRemoto].
         */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episode_frame ADD COLUMN origenRemoto INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v24 -> v25: recommendations for the home's "Para ti" row. The table is born empty, so
         * unlike MIGRATION_6_7/7_8 there's no need to seal existing rows with the current time.
         *
         * At the time this table came from a gateway sync and the app never wrote to it directly;
         * since sub-project 4 the app generates these on-device and writes here itself (see
         * [RecommendationEntity]) -- the schema this migration adds hasn't changed either way.
         */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recomendaciones (" +
                        "id TEXT NOT NULL PRIMARY KEY, tmdbId INTEGER NOT NULL, tipo TEXT NOT NULL, " +
                        "titulo TEXT NOT NULL, posterUrl TEXT NOT NULL, porque TEXT NOT NULL, " +
                        "ref TEXT NOT NULL, orden INTEGER NOT NULL, generadoAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }

        /**
         * v25 -> v26: item's `tipo` ("movie"|"tv"), so the library can tell with certainty whether
         * it already has something instead of comparing by title, which is fuzzy. See [ItemEntity.tipo].
         *
         * NULL with no DEFAULT on purpose, same as [MIGRATION_15_16] with `tmdbId`: items that
         * already exist don't know their type for certain, and guessing it (e.g. from
         * `categoryOverride`, which uses "series" and not "tv") would leave data with the SAME
         * shape as one confirmed by the source, without being one. With NULL it simply doesn't
         * take part in that exact comparison yet.
         */
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN tipo TEXT")
            }
        }

        /**
         * v26 -> v27: the name TMDB knows the work by, alongside the one the source said.
         *
         * Comes in NULL on every old row and fills itself in: on saving a magis season, and when
         * `ReparacionDeMagis` resolves the identity of an item that came in without it. While it's
         * null the library keeps showing `title`, same as now — there's no weird in-between state,
         * which is why nothing needs to be emptied out (unlike [MIGRATION_19_20]).
         *
         * `items` carries the `updatedAt`/`deleted` columns from this branch's now-removed cloud
         * sync; they stay until the Phase 3 column audit. No sync is planned to come back --
         * this branch's rule (see CLAUDE.md) forbids a server of its own.
         */
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN tituloCanonico TEXT")
            }
        }

        /**
         * v27 -> v28: markers become per-chapter. The table gets RECREATED instead of migrated: in
         * production it has 0 rows (verified against PocketBase on 2026-08-19), so there's nothing
         * to preserve — and recreating it avoids making up an `id` for old rows.
         */
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS skip_markers")
                db.execSQL(
                    "CREATE TABLE skip_markers (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "itemId TEXT NOT NULL, " +
                        "episodeId TEXT NOT NULL DEFAULT '', " +
                        "openingStartMs INTEGER, openingEndMs INTEGER, endingStartMs INTEGER, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "deleted INTEGER NOT NULL DEFAULT 0, " +
                        "origen TEXT NOT NULL DEFAULT 'manual')",
                )
            }
        }

        /**
         * v28 -> v29: drops the three orphaned tables from the NUC pruning (Task 8, "cero
         * servidor propio"): `nuc_library_items`, `series_playback_prefs` and `local_active_jobs`.
         * Their entities/DAOs (`NucLibraryItemEntity`/`SeriesPlaybackPrefEntity`/`LocalActiveJobEntity`)
         * and whoever read or wrote them (`NucDownloads`, `PlaybackPreferenceStore`,
         * `NucDownloadsScreen`/`ViewModel`) were already deleted in that same task; this migration
         * finishes removing the schema that was left pending.
         *
         * Direct DROP, nothing recreated: it's data exclusive to arkiv-offline (the NUC), which no
         * longer exists for this branch -- there's nothing to preserve or migrate to another table.
         */
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS nuc_library_items")
                db.execSQL("DROP TABLE IF EXISTS series_playback_prefs")
                db.execSQL("DROP TABLE IF EXISTS local_active_jobs")
            }
        }

        /**
         * Puts the `updatedAt` triggers back in place on EVERY open, and seals whatever was left
         * with no clock.
         *
         * Goes here and not in a migration because whoever installs the app from scratch **runs
         * no migration at all**: Room creates its tables from its generated schema, and the
         * triggers aren't part of that schema. That's how the Fire TV ended up with none, with its
         * whole library at `updatedAt = 0` and therefore invisible to the cloud push
         * (`updatedAt > cursor`). See [SyncTriggers].
         */
        private val SEAL_UPDATED_AT = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Triggers first: sealing afterward doesn't fire them (the row changes from 0 to
                // `now`, i.e. NEW.updatedAt != OLD.updatedAt, which is the trigger's guard).
                SyncTriggers.ddl().forEach { db.execSQL(it) }
                SyncTriggers.sealRowsWithNoClock().forEach { db.execSQL(it) }
            }
        }

        fun get(context: Context): ArkivDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArkivDatabase::class.java,
                    "arkiv.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29)
                    .addCallback(SEAL_UPDATED_AT)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
