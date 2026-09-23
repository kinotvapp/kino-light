package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The query that exposes the account's active recommendations (the home's "Para ti" row):
 * ordered by `orden` -- what `ForYouGenerator` decided, on the device -- and without what's
 * already marked as a tombstone (`deleted`). See [RecommendationDao.observeActive].
 *
 * Run against real SQLite -- same criterion as [SyncTriggersTest] -- because it's pure SQL and
 * this module has no Room infrastructure (nor Robolectric) in its JVM unit tests. The
 * `CREATE TABLE` here has to stay in sync with [ArkivDatabase]'s `MIGRATION_24_25` -- there's no
 * way to check that automatically -- but the SQL itself is NOT copied by hand: it uses
 * [QUERY_ACTIVE_RECOMMENDATIONS], the same constant the real `@Query` in
 * [RecommendationDao.observeActive] uses. This test used to have its own copy of the string, and
 * that was exactly the hole the review found: removing the `WHERE deleted = 0` from the real
 * query made nothing fail here, because two different SQL statements ran that just so happened to
 * say the same thing.
 */
class RecommendationQueryTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE recomendaciones (" +
                    "id TEXT NOT NULL PRIMARY KEY, tmdbId INTEGER NOT NULL, tipo TEXT NOT NULL, " +
                    "titulo TEXT NOT NULL, posterUrl TEXT NOT NULL, porque TEXT NOT NULL, " +
                    "ref TEXT NOT NULL, orden INTEGER NOT NULL, generadoAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(id: String, order: Int, deleted: Int = 0) {
        db.createStatement().use {
            it.executeUpdate(
                "INSERT INTO recomendaciones " +
                    "(id, tmdbId, tipo, titulo, posterUrl, porque, ref, orden, generadoAt, updatedAt, deleted) " +
                    "VALUES ('$id', 1, 'movie', 'T', '', '', 'ref', $order, 0, 0, $deleted)",
            )
        }
    }

    /** THE query from [RecommendationDao.observeActive] -- not a copy, the same constant. */
    private fun active(): List<String> =
        db.createStatement().use { st ->
            st.executeQuery(QUERY_ACTIVE_RECOMMENDATIONS).use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out.add(rs.getString("id"))
                out
            }
        }

    @Test fun `returns ordered by orden`() {
        insert("c", order = 2)
        insert("a", order = 0)
        insert("b", order = 1)
        assertEquals(listOf("a", "b", "c"), active())
    }

    @Test fun `excludes deleted ones`() {
        insert("alive", order = 0)
        insert("buried", order = 1, deleted = 1)
        assertEquals("the tombstone can't reappear in the 'Para ti' row", listOf("alive"), active())
    }

    @Test fun `a newer delete that won the LWW stops showing up`() {
        // Simulates what RecommendationDao.retireActive does on a fresh generation: the row was
        // already live locally, and the update leaves it with deleted=1 and a newer updatedAt
        // because the new generation buried it.
        insert("rec1", order = 0)
        db.createStatement().use {
            it.executeUpdate("UPDATE recomendaciones SET deleted = 1, updatedAt = 999 WHERE id = 'rec1'")
        }
        assertEquals(emptyList<String>(), active())
    }
}
