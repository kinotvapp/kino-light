package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioReposMigrationTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { st ->
            NuvioReposMigration.STATEMENTS.forEach(st::execute)
            SyncTriggers.ddl().filter { "nuvio_repos" in it }.forEach(st::execute)
        }
    }

    private fun Connection.clockOf(address: String): Long =
        createStatement().use { it.executeQuery("SELECT updatedAt FROM nuvio_repos WHERE address = '$address'").use { r -> r.next(); r.getLong(1) } }

    @Test fun `the statements are idempotent`() {
        db().use { c -> c.createStatement().use { st -> NuvioReposMigration.STATEMENTS.forEach(st::execute) } }
    }

    @Test fun `a local insert is sealed, an explicit clock is respected`() {
        db().use { c ->
            c.createStatement().use {
                it.execute("INSERT INTO nuvio_repos (address, updatedAt, deleted) VALUES ('a/b', 0, 0)")
                it.execute("INSERT INTO nuvio_repos (address, updatedAt, deleted) VALUES ('c/d', 42, 0)")
            }
            assertTrue(c.clockOf("a/b") > 0)
            assertEquals(42L, c.clockOf("c/d"))
        }
    }
}
