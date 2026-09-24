package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The `kinobot_messages` table (Kinobot chat history): its DDL is valid, autoincrement ids order the
 * rows oldest-first (what [KinobotDao.flowAll] reads), and `clear()` empties it.
 *
 * Run against real SQLite -- same as [RecommendationQueryTest]: this module has no Room/Robolectric
 * in its JVM unit tests. The `CREATE TABLE` here MUST match [ArkivDatabase]'s `MIGRATION_31_32` (and
 * therefore [KinobotMessageEntity]'s Room schema); a mismatch is caught for real when the app opens a
 * migrated DB on the device. Kept in sync by hand.
 */
class KinobotMessagesTableTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS kinobot_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "role TEXT NOT NULL, content TEXT NOT NULL, " +
                    "suggestionsJson TEXT NOT NULL, createdAt INTEGER NOT NULL)",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(role: String, content: String, suggestions: String = "[]", createdAt: Long = 0) {
        db.prepareStatement(
            "INSERT INTO kinobot_messages (role, content, suggestionsJson, createdAt) VALUES (?,?,?,?)",
        ).use {
            it.setString(1, role); it.setString(2, content); it.setString(3, suggestions); it.setLong(4, createdAt)
            it.executeUpdate()
        }
    }

    private fun rows(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT role, content, suggestionsJson FROM kinobot_messages ORDER BY id ASC").use { rs ->
                while (rs.next()) out += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            }
        }
        return out
    }

    @Test fun `rows come back oldest first with fields intact`() {
        insert("user", "hola")
        insert("assistant", "te recomiendo Akira", """["Akira"]""")
        assertEquals(
            listOf(
                Triple("user", "hola", "[]"),
                Triple("assistant", "te recomiendo Akira", """["Akira"]"""),
            ),
            rows(),
        )
    }

    @Test fun `clear empties the table`() {
        insert("user", "hola")
        db.createStatement().use { it.executeUpdate("DELETE FROM kinobot_messages") }
        assertEquals(emptyList<Triple<String, String, String>>(), rows())
    }
}
