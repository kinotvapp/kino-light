package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v32 -> v33 against real SQLite, starting from the EXACT v32 DDL (`MIGRATION_20_21`'s, unchanged
 * since) with rows in it: every existing favourite, recent and cache row becomes Xuper's, and
 * the new primary keys let the same code exist under two providers. Room's own schema check
 * runs only on a device (no Robolectric here): Task 16 opens a migrated DB on the phone.
 */
class LiveProviderMigrationTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec(
            "CREATE TABLE live_favorites (code TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, numero INTEGER NOT NULL, " +
                "logo TEXT, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE live_recents (code TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, vistoAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE live_channels_cache (code TEXT NOT NULL, categoria INTEGER NOT NULL, nombre TEXT NOT NULL, " +
                "numero INTEGER NOT NULL, logo TEXT, guardadoAt INTEGER NOT NULL, PRIMARY KEY(code, categoria))",
            "INSERT INTO live_favorites VALUES ('c1', 'RCN', 5, 'https://l/1.png', 111, 0)",
            "INSERT INTO live_favorites VALUES ('c2', 'Caracol', 6, NULL, 222, 1)",
            "INSERT INTO live_recents VALUES ('c1', 'RCN', 999, 333)",
            "INSERT INTO live_channels_cache VALUES ('c1', 76182, 'RCN', 5, 'https://l/1.png', 444)",
            "INSERT INTO live_channels_cache VALUES ('c1', 12, 'RCN', 5, 'https://l/1.png', 445)",
        )
    }

    @After fun tearDown() = db.close()

    private fun exec(vararg sql: String) = db.createStatement().use { st -> sql.forEach { st.executeUpdate(it) } }

    private fun rows(sql: String): List<List<Any?>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }
    }

    /** The live tables' part of [SyncTriggers.ddl] (this test DB has only the three live tables). */
    private fun liveTriggers(): Array<String> = SyncTriggers.ddl().filter { "live_" in it }.toTypedArray()

    /** column name -> pk position (0 = not in the key), from PRAGMA table_info. */
    private fun pk(table: String): Map<String, Int> =
        rows("PRAGMA table_info($table)").associate { it[1] as String to (it[5] as Number).toInt() }

    @Test fun `existing rows become xuper's with every value kept`() {
        exec(*LiveProviderMigration.STATEMENTS.toTypedArray())
        assertEquals(
            listOf(listOf("xuper", "c1", "RCN", 5, "https://l/1.png", 111, 0), listOf("xuper", "c2", "Caracol", 6, null, 222, 1)),
            rows("SELECT provider, code, nombre, numero, logo, updatedAt, deleted FROM live_favorites ORDER BY code"),
        )
        assertEquals(listOf(listOf("xuper", "c1", "RCN", 999, 333)), rows("SELECT provider, code, nombre, vistoAt, updatedAt FROM live_recents"))
        assertEquals(
            listOf(listOf("xuper", "c1", "12", null), listOf("xuper", "c1", "76182", null)),
            rows("SELECT provider, code, categoria, ref FROM live_channels_cache ORDER BY categoria"),
        )
    }

    @Test fun `the new keys are provider first and let one code live under two providers`() {
        exec(*LiveProviderMigration.STATEMENTS.toTypedArray())
        assertEquals(1, pk("live_favorites")["provider"]); assertEquals(2, pk("live_favorites")["code"])
        assertEquals(1, pk("live_recents")["provider"]); assertEquals(2, pk("live_recents")["code"])
        assertEquals(mapOf("provider" to 1, "code" to 2, "categoria" to 3), pk("live_channels_cache").filterValues { it > 0 })
        exec(
            "INSERT INTO live_favorites (code, nombre, numero, logo, provider) VALUES ('c1', 'Canal Uno', 1, NULL, 'plugin:own-server')",
            "INSERT INTO live_recents (code, nombre, vistoAt, provider) VALUES ('c1', 'Canal Uno', 1000, 'plugin:own-server')",
            "INSERT INTO live_channels_cache (code, categoria, nombre, numero, logo, guardadoAt, provider, ref) " +
                "VALUES ('c1', 'news', 'Canal Uno', 1, NULL, 1, 'plugin:own-server', 'plg1:own-server:x')",
        )
        assertEquals(3, rows("SELECT * FROM live_favorites").size)
        assertEquals(2, rows("SELECT * FROM live_recents").size)
    }

    @Test fun `the sync triggers work on the migrated tables and seal per provider`() {
        exec(*LiveProviderMigration.STATEMENTS.toTypedArray())
        exec(*liveTriggers())
        exec("INSERT INTO live_favorites (code, nombre, numero, logo, provider) VALUES ('c1', 'Canal Uno', 1, NULL, 'plugin:own-server')")
        assertEquals(111L, (rows("SELECT updatedAt FROM live_favorites WHERE provider = 'xuper' AND code = 'c1'")[0][0] as Number).toLong())
        assertTrue((rows("SELECT updatedAt FROM live_favorites WHERE provider = 'plugin:own-server'")[0][0] as Number).toLong() > 0)
    }

    /**
     * Most devices were installed fresh on v21+, so their live tables came from Room's generated
     * schema (no DEFAULTs, backquoted, `PRIMARY KEY(`code`)`), not from `MIGRATION_20_21`, and they
     * carry the v32 `updatedAt` triggers that `SEAL_UPDATED_AT` put on every open. Same result.
     */
    @Test fun `a fresh-install v32 schema with its v32 triggers migrates the same way`() {
        exec(
            "DROP TABLE live_favorites", "DROP TABLE live_recents", "DROP TABLE live_channels_cache",
            // Verbatim from the v32 build's generated ArkivDatabase_Impl.createAllTables.
            "CREATE TABLE IF NOT EXISTS `live_favorites` (`code` TEXT NOT NULL, `nombre` TEXT NOT NULL, `numero` INTEGER NOT NULL, `logo` TEXT, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`code`))",
            "CREATE TABLE IF NOT EXISTS `live_recents` (`code` TEXT NOT NULL, `nombre` TEXT NOT NULL, `vistoAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`code`))",
            "CREATE TABLE IF NOT EXISTS `live_channels_cache` (`code` TEXT NOT NULL, `categoria` INTEGER NOT NULL, `nombre` TEXT NOT NULL, `numero` INTEGER NOT NULL, `logo` TEXT, `guardadoAt` INTEGER NOT NULL, PRIMARY KEY(`code`, `categoria`))",
            // The v32 SyncTriggers text for the two synced live tables (single-column PK).
            "CREATE TRIGGER trg_live_favorites_ins AFTER INSERT ON live_favorites WHEN NEW.updatedAt = 0 " +
                "BEGIN UPDATE live_favorites SET updatedAt = CAST(strftime('%s','now') AS INTEGER)*1000 WHERE code = NEW.code; END",
            "CREATE TRIGGER trg_live_favorites_upd AFTER UPDATE ON live_favorites WHEN NEW.updatedAt = OLD.updatedAt " +
                "BEGIN UPDATE live_favorites SET updatedAt = MAX(CAST(strftime('%s','now') AS INTEGER)*1000, OLD.updatedAt + 1) WHERE code = NEW.code; END",
            "CREATE TRIGGER trg_live_recents_ins AFTER INSERT ON live_recents WHEN NEW.updatedAt = 0 " +
                "BEGIN UPDATE live_recents SET updatedAt = CAST(strftime('%s','now') AS INTEGER)*1000 WHERE code = NEW.code; END",
            "INSERT INTO live_favorites VALUES ('c1', 'RCN', 5, 'https://l/1.png', 111, 0)",
            "INSERT INTO live_favorites VALUES ('c2', 'Caracol', 6, NULL, 222, 1)",
            "INSERT INTO live_recents VALUES ('c1', 'RCN', 999, 333)",
            "INSERT INTO live_channels_cache VALUES ('c1', 76182, 'RCN', 5, 'https://l/1.png', 444)",
        )
        exec(*LiveProviderMigration.STATEMENTS.toTypedArray())
        // The copy is not re-sealed: every clock survives exactly.
        assertEquals(
            listOf(listOf("xuper", "c1", "RCN", 5, "https://l/1.png", 111, 0), listOf("xuper", "c2", "Caracol", 6, null, 222, 1)),
            rows("SELECT provider, code, nombre, numero, logo, updatedAt, deleted FROM live_favorites ORDER BY code"),
        )
        assertEquals(listOf(listOf("xuper", "c1", "RCN", 999, 333)), rows("SELECT provider, code, nombre, vistoAt, updatedAt FROM live_recents"))
        assertEquals(listOf(listOf("xuper", "c1", "76182", null)), rows("SELECT provider, code, categoria, ref FROM live_channels_cache"))
        // No temporary table is left behind, and the old triggers went with the old tables.
        assertEquals(emptyList<List<Any?>>(), rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%\\_new' ESCAPE '\\'"))
        assertEquals(emptyList<List<Any?>>(), rows("SELECT name FROM sqlite_master WHERE type = 'trigger'"))
        // What ArkivDatabase's onOpen then does: new triggers, then sealing; the migrated clocks stay.
        exec(*liveTriggers(), *SyncTriggers.sealRowsWithNoClock().filter { "live_" in it }.toTypedArray())
        exec("UPDATE live_favorites SET deleted = 1 WHERE provider = 'xuper' AND code = 'c1'")
        assertEquals(222L, (rows("SELECT updatedAt FROM live_favorites WHERE code = 'c2'")[0][0] as Number).toLong())
        assertTrue((rows("SELECT updatedAt FROM live_favorites WHERE code = 'c1'")[0][0] as Number).toLong() > 111)
    }
}
