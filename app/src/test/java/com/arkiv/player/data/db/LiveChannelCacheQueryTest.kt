package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The channel cache's deletes that keep the En vivo search honest, run against real SQLite from
 * the same constants their `@Query`s use: a plugin uninstalled leaves no rows, and a playlist
 * regrouped drops only that provider's playlist rows (`pl:` categories), never its own categories'
 * or another provider's.
 */
class LiveChannelCacheQueryTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec(
            "CREATE TABLE live_channels_cache (code TEXT NOT NULL, categoria TEXT NOT NULL, nombre TEXT NOT NULL, " +
                "numero INTEGER NOT NULL, logo TEXT, guardadoAt INTEGER NOT NULL, provider TEXT NOT NULL, ref TEXT, " +
                "PRIMARY KEY(provider, code, categoria))",
            "INSERT INTO live_channels_cache VALUES ('c1', '76182', 'RCN', 5, NULL, 1, 'xuper', NULL)",
            "INSERT INTO live_channels_cache VALUES ('n1', 'news', 'Uno', 1, NULL, 1, 'plugin:demo', 'r')",
            "INSERT INTO live_channels_cache VALUES ('~k.a', 'pl:k:0123456789', 'A', 0, NULL, 1, 'plugin:demo', NULL)",
            "INSERT INTO live_channels_cache VALUES ('~k.b', 'pl:k:otros', 'B', 0, NULL, 1, 'plugin:demo', NULL)",
            "INSERT INTO live_channels_cache VALUES ('~k.a', 'pl:k:0123456789', 'A', 0, NULL, 1, 'plugin:other', NULL)",
            "INSERT INTO live_channels_cache VALUES ('x', 'plx', 'X', 0, NULL, 1, 'plugin:demo', NULL)",
        )
    }

    @After fun tearDown() = db.close()

    private fun exec(vararg sql: String) = db.createStatement().use { st -> sql.forEach { st.executeUpdate(it) } }

    private fun delete(sql: String, provider: String) =
        db.prepareStatement(sql.replace(":provider", "?")).use { it.setString(1, provider); it.executeUpdate() }

    private fun left(): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT provider || '/' || categoria || '/' || code FROM live_channels_cache ORDER BY 1").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    @Test fun `an uninstalled plugin's rows all go, nobody else's`() {
        delete(QUERY_CLEAR_LIVE_PROVIDER, "plugin:demo")
        assertEquals(listOf("plugin:other/pl:k:0123456789/~k.a", "xuper/76182/c1"), left())
    }

    @Test fun `a regrouped playlist drops only that provider's playlist rows`() {
        delete(QUERY_CLEAR_LIVE_PLAYLIST_ROWS, "plugin:demo")
        assertEquals(
            listOf("plugin:demo/news/n1", "plugin:demo/plx/x", "plugin:other/pl:k:0123456789/~k.a", "xuper/76182/c1"),
            left(),
        )
    }
}
