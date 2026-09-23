package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The "rows changed since a cursor" queries the sync engine's push side (Task 2 of the
 * companion-sync sub-project) pages with: ascending by `updatedAt`, so a page boundary never
 * skips a row that arrives between two pushes.
 *
 * Run against real SQLite -- same criterion as [RecommendationQueryTest] and [SyncTriggersTest]
 * -- and, like [RecommendationQueryTest], reads the SQL from [QUERY_PLAYBACK_SINCE], the same
 * constant [PlaybackDao.getPlaybackSince]'s `@Query` uses, instead of a hand-copied string: the
 * other five since-queries (`QUERY_ITEMS_SINCE`, `QUERY_EPISODES_SINCE`, `QUERY_MARKERS_SINCE`,
 * `QUERY_LIVE_FAVORITES_SINCE`, `QUERY_LIVE_RECENTS_SINCE`) share the exact same shape, just a
 * different table name, so this one case stands in for all six.
 *
 * Room's `@Query` strings use a named `:cursor` placeholder, which plain JDBC doesn't understand;
 * [since] swaps it for a positional `?` right before preparing the statement, which is a
 * mechanical substitution and not a copy of the query -- any change to the table, the `WHERE` or
 * the `ORDER BY` in [QUERY_PLAYBACK_SINCE] still flows straight into this test.
 */
class ChangedSinceQueryTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE playback (episodeId TEXT PRIMARY KEY, positionMs INTEGER NOT NULL DEFAULT 0, " +
                    "durationMs INTEGER NOT NULL DEFAULT 0, watched INTEGER NOT NULL DEFAULT 0, " +
                    "lastPlayedAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                    "deleted INTEGER NOT NULL DEFAULT 0)",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(episodeId: String, updatedAt: Long) {
        db.createStatement().use {
            it.executeUpdate(
                "INSERT INTO playback (episodeId, updatedAt) VALUES ('$episodeId', $updatedAt)",
            )
        }
    }

    /** THE query from [PlaybackDao.getPlaybackSince] -- not a copy, the same constant. */
    private fun since(cursor: Long): List<Long> =
        db.prepareStatement(QUERY_PLAYBACK_SINCE.replace(":cursor", "?")).use { ps ->
            ps.setLong(1, cursor)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Long>()
                while (rs.next()) out.add(rs.getLong("updatedAt"))
                out
            }
        }

    @Test fun `returns only the rows past the cursor, ascending by updatedAt`() {
        insert("e5", updatedAt = 5)
        insert("e10", updatedAt = 10)
        insert("e15", updatedAt = 15)
        assertEquals(listOf(10L, 15L), since(cursor = 8))
    }
}
