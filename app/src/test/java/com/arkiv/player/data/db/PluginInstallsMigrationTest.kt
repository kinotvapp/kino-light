package com.arkiv.player.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInstallsMigrationTest {
    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        c.createStatement().use { st ->
            PluginInstallsMigration.STATEMENTS.forEach(st::execute)
            SyncTriggers.ddl().filter { "plugin_installs" in it }.forEach(st::execute)
        }
    }

    private fun Connection.clockOf(id: String): Long =
        createStatement().use { it.executeQuery("SELECT updatedAt FROM plugin_installs WHERE id = '$id'").use { r -> r.next(); r.getLong(1) } }

    @Test fun `the statements are idempotent`() {
        db().use { c -> c.createStatement().use { st -> PluginInstallsMigration.STATEMENTS.forEach(st::execute) } }
    }

    @Test fun `a local insert with no clock is sealed by the trigger`() {
        db().use { c ->
            c.createStatement().use { it.execute("INSERT INTO plugin_installs (id, address, name, version, sha256, enabled, approvedJson, settingsJson, updatedAt, deleted) VALUES ('s1','a/b','Uno','1.0.0','',1,'{}','{}',0,0)") }
            assertTrue(c.clockOf("s1") > 0)
        }
    }

    @Test fun `an explicit clock written by the merge is respected`() {
        db().use { c ->
            c.createStatement().use { it.execute("INSERT INTO plugin_installs (id, address, name, version, sha256, enabled, approvedJson, settingsJson, updatedAt, deleted) VALUES ('s1','a/b','Uno','1.0.0','',1,'{}','{}',42,0)") }
            assertEquals(42L, c.clockOf("s1"))
        }
    }

    @Test fun `a local update moves the clock forward`() {
        db().use { c ->
            c.createStatement().use {
                it.execute("INSERT INTO plugin_installs (id, address, name, version, sha256, enabled, approvedJson, settingsJson, updatedAt, deleted) VALUES ('s1','a/b','Uno','1.0.0','',1,'{}','{}',5,0)")
                it.execute("UPDATE plugin_installs SET name = 'Dos' WHERE id = 's1'")
            }
            assertTrue(c.clockOf("s1") > 5)
        }
    }
}
