package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The triggers that seal `updatedAt` on every local write.
 *
 * They were the clock BOTH syncs (cloud and LAN, both deleted from this branch) depended on to
 * decide who won, and the filter the push used to choose what to upload (`updatedAt > cursor`;
 * the DAO that read that cursor was deleted too). The trigger is still in force because the
 * `updatedAt`/`deleted` columns stay until the Phase 3 column audit.
 *
 * They used to live only inside `MIGRATION_6_7`, so a device installed fresh on a later version
 * never had them — Room generates the tables from its schema and the triggers aren't part of it.
 * Measured on 2026-08-10: the Fire TV had ZERO triggers and 77 of 116 items, 1054 of 1806
 * episodes and 7 progress rows at `updatedAt = 0`; the phone, which had migrated, had all 8
 * triggers and no unsealed row. Meaning: everything born on the TV was invisible to PocketBase.
 *
 * Run against real SQLite because they're pure SQL: it's the only way to know if they seal.
 */
class SyncTriggersTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE items (identifier TEXT PRIMARY KEY, title TEXT, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE episodes (id TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE playback (episodeId TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            // The same schema Room generates: the PK is `id` ("<itemId>|<episodeId>"), not `itemId`
            // -- a series has one row per chapter plus the whole-series one.
            it.executeUpdate(
                "CREATE TABLE skip_markers (id TEXT PRIMARY KEY, itemId TEXT NOT NULL, episodeId TEXT NOT NULL DEFAULT '', " +
                    "updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)",
            )
            // Task 10: live TV favorites and recents, the two tables added to
            // SyncTriggers.TABLES. `live_recents` carries no `deleted` on purpose -- see
            // LiveRecentEntity -- so neither does it here, so this test's schema stays faithful
            // to the real one.
            it.executeUpdate("CREATE TABLE live_favorites (code TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0)")
            it.executeUpdate("CREATE TABLE live_recents (code TEXT PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0)")
        }
    }

    @After fun tearDown() = db.close()

    private fun apply(statements: List<String>) =
        db.createStatement().use { st -> statements.forEach { st.executeUpdate(it) } }

    private fun clockOf(table: String, pk: String, value: String): Long =
        db.createStatement().use { st ->
            st.executeQuery("SELECT updatedAt FROM $table WHERE $pk = '$value'").use { it.getLong(1) }
        }

    private fun execute(sql: String) = db.createStatement().use { it.executeUpdate(sql) }

    @Test fun `a new row is sealed with the current time`() {
        // THE Fire TV bug: with no trigger the row is born at 0 and the push never picks it up.
        apply(SyncTriggers.ddl())
        execute("INSERT INTO items (identifier, title) VALUES ('daima', 'Daima')")
        assertTrue("born with no clock", clockOf("items", "identifier", "daima") > 0)
    }

    @Test fun `a row arriving from sync keeps its remote clock`() {
        // The merge writes the other device's `updatedAt` on purpose: sealing it here would break
        // last-write-wins (the row would look newer than it is and bounce back and forth forever).
        apply(SyncTriggers.ddl())
        execute("INSERT INTO items (identifier, title, updatedAt) VALUES ('remota', 'R', 12345)")
        assertEquals(12345L, clockOf("items", "identifier", "remota"))
    }

    @Test fun `editing a row moves its clock`() {
        apply(SyncTriggers.ddl())
        execute("INSERT INTO items (identifier, title, updatedAt) VALUES ('a', 'viejo', 100)")
        execute("UPDATE items SET title = 'nuevo' WHERE identifier = 'a'")
        assertTrue("a local edit has to mark itself dirty", clockOf("items", "identifier", "a") > 100)
    }

    @Test fun `a soft delete also moves the clock`() {
        // If the tombstone doesn't mark itself dirty, the deletion doesn't propagate and the item revives.
        apply(SyncTriggers.ddl())
        execute("INSERT INTO items (identifier, title, updatedAt) VALUES ('a', 'T', 100)")
        execute("UPDATE items SET deleted = 1 WHERE identifier = 'a'")
        assertTrue(clockOf("items", "identifier", "a") > 100)
    }

    @Test fun `the merge can overwrite the clock without the trigger rewriting it`() {
        apply(SyncTriggers.ddl())
        execute("INSERT INTO items (identifier, title, updatedAt) VALUES ('a', 'T', 100)")
        execute("UPDATE items SET title = 'del otro', updatedAt = 555 WHERE identifier = 'a'")
        assertEquals(555L, clockOf("items", "identifier", "a"))
    }

    @Test fun `covers the six tables that sync`() {
        apply(SyncTriggers.ddl())
        execute("INSERT INTO episodes (id) VALUES ('e1')")
        execute("INSERT INTO playback (episodeId) VALUES ('e1')")
        execute("INSERT INTO skip_markers (id, itemId, episodeId) VALUES ('i1|', 'i1', '')")
        execute("INSERT INTO live_favorites (code) VALUES ('c1')")
        execute("INSERT INTO live_recents (code) VALUES ('c1')")
        assertTrue(clockOf("episodes", "id", "e1") > 0)
        assertTrue(clockOf("playback", "episodeId", "e1") > 0)
        assertTrue(clockOf("skip_markers", "id", "i1|") > 0)
        assertTrue(clockOf("live_favorites", "code", "c1") > 0)
        assertTrue(clockOf("live_recents", "code", "c1") > 0)
    }

    @Test fun `sealing one marker doesn't move the clock of the series' other chapters`() {
        // The table map used to declare `skip_markers to "itemId"`, which was no longer the PK:
        // the trigger sealed with `WHERE itemId = NEW.itemId`, i.e. EVERY marker of the series at
        // once. A single new marker with no clock gave a fresh time to every other chapter's too
        // and sent them off to upload as if they'd just been edited (and in the LWW merge, to win
        // over whatever was on the other side).
        apply(SyncTriggers.ddl())
        execute("INSERT INTO skip_markers (id, itemId, episodeId, updatedAt) VALUES ('daima|e1', 'daima', 'e1', 42)")
        execute("INSERT INTO skip_markers (id, itemId, episodeId) VALUES ('daima|e2', 'daima', 'e2')")
        assertEquals("the other chapter's marker wasn't touched", 42L, clockOf("skip_markers", "id", "daima|e1"))
        assertTrue("the one born with no clock does get sealed", clockOf("skip_markers", "id", "daima|e2") > 0)
    }

    @Test fun `applying it twice doesn't fail`() {
        // Runs on every database open: if it weren't idempotent, the app would never open again.
        apply(SyncTriggers.ddl())
        apply(SyncTriggers.ddl())
    }

    @Test fun `two writes in the same second, the clock always advances`() {
        // The real crash (Fire TV, 2026-08-10): `ArkivRepository.addMagisSeason` did `upsertItem`
        // (the INSERT seals with NOW) and, in the same second, an UPDATE that doesn't move the
        // clock (`markEpisodesSeen`, the badge). `NOW` has SECOND resolution: the UPDATE inside
        // the trigger wrote the same number again, `NEW.updatedAt = OLD.updatedAt` evaluated true
        // again, and the trigger fired itself until SQLite cut it off with "too many levels of
        // trigger recursion" -- the app died after saving but before navigating to the player.
        //
        // The same second is forced by inserting with `updatedAt` = NOW (not a fixed past value,
        // which is what the rest of this file's tests do) and updating right away: microseconds
        // pass between the two statements, so the real second-clock hasn't advanced yet.
        //
        // The crash's literal `SQLITE_ERROR` isn't seen here: Android ships `recursive_triggers`
        // ON, but xerial/sqlite-jdbc (what runs this test, verified with `PRAGMA
        // recursive_triggers` = 0) ships it OFF by default, so the trigger re-invoking itself on
        // its own write is disabled here and there's no real recursion to count. What IS the same
        // in both environments is the underlying defect -- the UPDATE inside the trigger writes
        // the same value the row already had -- and that's what this test verifies: with the old
        // trigger, `clockOf(...)` after the UPDATE comes out EQUAL to `sealed` (the `assertTrue`
        // below fails); with the new one, it always advances.
        apply(SyncTriggers.ddl())
        execute(
            "INSERT INTO items (identifier, title, updatedAt) VALUES " +
                "('a', 'viejo', CAST(strftime('%s','now') AS INTEGER)*1000)",
        )
        val sealed = clockOf("items", "identifier", "a")
        execute("UPDATE items SET title = 'nuevo' WHERE identifier = 'a'")
        assertTrue(
            "the second write in the same second has to ADVANCE the clock, not repeat it",
            clockOf("items", "identifier", "a") > sealed,
        )
    }

    @Test fun `an old trigger already created gets replaced by the new one`() {
        // `CREATE TRIGGER IF NOT EXISTS` replaces nothing: without the `DROP TRIGGER IF EXISTS`
        // that now precedes every CREATE, a device that had already opened the database with the
        // recursive trigger (the one from before this fix) would have kept that definition
        // forever. This simulates that device: the OLD trigger is created by hand (the one that
        // seals with a bare NOW) and it's verified that applying `ddl()` again -- which happens on
        // every database open, see `ArkivDatabase.SEAL_UPDATED_AT` -- leaves it with the new one.
        execute(
            "CREATE TRIGGER trg_items_upd AFTER UPDATE ON items WHEN NEW.updatedAt = OLD.updatedAt " +
                "BEGIN UPDATE items SET updatedAt = CAST(strftime('%s','now') AS INTEGER)*1000 " +
                "WHERE identifier = NEW.identifier; END",
        )
        apply(SyncTriggers.ddl())
        execute(
            "INSERT INTO items (identifier, title, updatedAt) VALUES " +
                "('a', 'viejo', CAST(strftime('%s','now') AS INTEGER)*1000)",
        )
        val sealed = clockOf("items", "identifier", "a")
        execute("UPDATE items SET title = 'nuevo' WHERE identifier = 'a'")
        assertTrue(clockOf("items", "identifier", "a") > sealed)
    }

    @Test fun `seals rows that had already been left with no clock`() {
        // The 77+1054+7 rows the Fire TV already has at 0: without this they never upload, ever.
        execute("INSERT INTO items (identifier, title, updatedAt) VALUES ('vieja', 'T', 0)")
        execute("INSERT INTO items (identifier, title, updatedAt) VALUES ('ok', 'T', 42)")
        apply(SyncTriggers.ddl())
        apply(SyncTriggers.sealRowsWithNoClock())
        assertTrue(clockOf("items", "identifier", "vieja") > 0)
        assertEquals("an already-sealed row isn't touched", 42L, clockOf("items", "identifier", "ok"))
    }
}
