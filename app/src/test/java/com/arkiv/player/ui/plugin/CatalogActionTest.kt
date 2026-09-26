package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogActionTest {
    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "lordmacu", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)
    private val entry = CatalogEntry(id = "demo", repo = "o/r", name = "Demo", description = "")

    private fun row(installed: InstalledPlugin?) = CatalogRow(entry, installed)
    private fun plugin(record: InstalledRecord = this.record, missingSettings: List<String> = emptyList()) =
        InstalledPlugin(manifest, record, iconFile = null, missingSettings = missingSettings)

    @Test fun `a plugin that is not installed offers to install it`() {
        assertEquals(CatalogAction.INSTALL, catalogActionOf(row(null)))
    }

    @Test fun `an active plugin shows as installed`() {
        assertEquals(CatalogAction.INSTALLED, catalogActionOf(row(plugin())))
    }

    @Test fun `a plugin with an update waiting for approval still shows as installed`() {
        val pending = record.copy(pendingVersion = "1.1.0")
        assertEquals(CatalogAction.INSTALLED, catalogActionOf(row(plugin(pending))))
    }

    @Test fun `a usable plugin that lacks a required setting offers to configure it`() {
        assertEquals(CatalogAction.CONFIGURE, catalogActionOf(row(plugin(missingSettings = listOf("x")))))
    }

    @Test fun `a plugin with an update waiting that also lacks a setting offers to configure it`() {
        val pending = record.copy(pendingVersion = "1.1.0")
        assertEquals(CatalogAction.CONFIGURE, catalogActionOf(row(plugin(pending, missingSettings = listOf("x")))))
    }

    @Test fun `a disabled plugin offers to enable it`() {
        assertEquals(CatalogAction.ENABLE, catalogActionOf(row(plugin(record.copy(enabled = false)))))
    }

    @Test fun `an unresponsive plugin offers to enable it again`() {
        assertEquals(CatalogAction.ENABLE, catalogActionOf(row(plugin(record.copy(unresponsive = true)))))
    }

    @Test fun `a disabled plugin that also lacks a setting is enabled first`() {
        assertEquals(CatalogAction.ENABLE, catalogActionOf(row(plugin(record.copy(enabled = false), missingSettings = listOf("x")))))
    }

    @Test fun `a damaged plugin offers to install it again`() {
        assertEquals(CatalogAction.INSTALL, catalogActionOf(row(plugin(record.copy(damaged = true)))))
    }

    @Test fun `damage wins over disabled, unresponsive and a missing setting`() {
        val broken = record.copy(damaged = true, enabled = false, unresponsive = true)
        assertEquals(CatalogAction.INSTALL, catalogActionOf(row(plugin(broken, missingSettings = listOf("x")))))
    }

    private fun rowOf(id: String, legacy: Boolean = false) = CatalogRow(CatalogEntry(id, "o/$id", id, "", legacyDefault = legacy), null)

    @Test fun `the row that was already in use moves to the top and the others keep their order`() {
        val rows = listOf(rowOf("a"), rowOf("b"), rowOf("c", legacy = true), rowOf("d"))
        assertEquals(listOf("c", "a", "b", "d"), legacyFirst(rows).map { it.entry.id })
    }

    @Test fun `without a legacy row the order does not change`() {
        val rows = listOf(rowOf("b"), rowOf("a"), rowOf("c"))
        assertEquals(listOf("b", "a", "c"), legacyFirst(rows).map { it.entry.id })
    }

    @Test fun `several legacy rows keep their own order at the top`() {
        val rows = listOf(rowOf("a"), rowOf("x", legacy = true), rowOf("b"), rowOf("y", legacy = true))
        assertEquals(listOf("x", "y", "a", "b"), legacyFirst(rows).map { it.entry.id })
    }
}
