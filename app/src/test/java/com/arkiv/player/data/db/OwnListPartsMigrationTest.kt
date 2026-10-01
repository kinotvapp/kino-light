package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnListPartsMigrationTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { st ->
            OwnLiveSourcesMigration.STATEMENTS.forEach(st::execute)
            st.execute(OwnListPartsMigration.ADD_DIGEST)
            OwnListPartsMigration.STATEMENTS.forEach(st::execute)
            SyncTriggers.ddl().filter { "own_live_list_parts" in it }.forEach(st::execute)
        }
    }

    private fun Connection.long(sql: String): Long = createStatement().use { it.executeQuery(sql).use { r -> r.next(); r.getLong(1) } }

    @Test fun `the table statements are idempotent and the digest column exists`() {
        db().use { c ->
            c.createStatement().use { st -> OwnListPartsMigration.STATEMENTS.forEach(st::execute) }
            c.createStatement().use { it.execute("INSERT INTO own_live_sources (id, kind, name, url, contentDigest) VALUES ('s1','PLAYLIST','L','kino-list:s1','ab')") }
            assertEquals(1L, c.long("SELECT COUNT(*) FROM own_live_sources WHERE contentDigest = 'ab'"))
        }
    }

    @Test fun `a part written with no clock is sealed alone, an explicit clock is respected`() {
        db().use { c ->
            c.createStatement().use {
                it.execute("INSERT INTO own_live_list_parts (sourceId, part, parts, digest, data, updatedAt) VALUES ('s1', 0, 2, 'd', 'x', 42)")
                it.execute("INSERT INTO own_live_list_parts (sourceId, part, parts, digest, data) VALUES ('s1', 1, 2, 'd', 'y')")
            }
            assertEquals(42L, c.long("SELECT updatedAt FROM own_live_list_parts WHERE sourceId = 's1' AND part = 0"))
            assertTrue(c.long("SELECT updatedAt FROM own_live_list_parts WHERE sourceId = 's1' AND part = 1") > 42)
        }
    }
}
