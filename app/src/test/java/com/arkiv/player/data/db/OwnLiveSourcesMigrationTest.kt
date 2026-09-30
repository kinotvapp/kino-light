package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnLiveSourcesMigrationTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { st ->
            OwnLiveSourcesMigration.STATEMENTS.forEach(st::execute)
            SyncTriggers.ddl().filter { "own_live_sources" in it }.forEach(st::execute)
        }
    }

    private fun Connection.clockOf(id: String): Long =
        createStatement().use { it.executeQuery("SELECT updatedAt FROM own_live_sources WHERE id = '$id'").use { r -> r.next(); r.getLong(1) } }

    @Test fun `the statements are idempotent`() {
        db().use { c -> c.createStatement().use { st -> OwnLiveSourcesMigration.STATEMENTS.forEach(st::execute) } }
    }

    @Test fun `a local insert with no clock is sealed by the trigger`() {
        db().use { c ->
            c.createStatement().use { it.execute("INSERT INTO own_live_sources (id, kind, name, url) VALUES ('s1','CHANNEL','Uno','http://a.example.com/x.m3u8')") }
            assertTrue(c.clockOf("s1") > 0)
        }
    }

    @Test fun `an explicit clock written by the merge is respected`() {
        db().use { c ->
            c.createStatement().use { it.execute("INSERT INTO own_live_sources (id, kind, name, url, updatedAt) VALUES ('s1','CHANNEL','Uno','http://a.example.com/x.m3u8', 42)") }
            assertEquals(42L, c.clockOf("s1"))
        }
    }

    @Test fun `a local update moves the clock forward`() {
        db().use { c ->
            c.createStatement().use {
                it.execute("INSERT INTO own_live_sources (id, kind, name, url, updatedAt) VALUES ('s1','CHANNEL','Uno','http://a.example.com/x.m3u8', 5)")
                it.execute("UPDATE own_live_sources SET name = 'Dos' WHERE id = 's1'")
            }
            assertTrue(c.clockOf("s1") > 5)
        }
    }
}
