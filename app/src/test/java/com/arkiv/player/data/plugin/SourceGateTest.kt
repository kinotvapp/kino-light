package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGateTest {
    private val manifest = PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "lordmacu", "", listOf("example.com"), setOf("search", "resolve"), null, null)
    private val record = InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L)

    private fun plugin(
        record: InstalledRecord = this.record,
        missingSettings: List<String> = emptyList(),
    ) = InstalledPlugin(manifest, record, null, missingSettings = missingSettings)

    private val active = plugin()
    private val disabled = plugin(record.copy(enabled = false))
    private val damaged = plugin(record.copy(damaged = true))
    private val unresponsive = plugin(record.copy(unresponsive = true))
    private val needsSetup = plugin(missingSettings = listOf("x"))
    private val updatePending = plugin(record.copy(pendingVersion = "2.0.0"))

    @Test
    fun `the fixtures are in the states the rule table names`() {
        assertEquals(PluginStatus.ACTIVE, active.status)
        assertEquals(PluginStatus.DISABLED, disabled.status)
        assertEquals(PluginStatus.DAMAGED, damaged.status)
        assertEquals(PluginStatus.UNRESPONSIVE, unresponsive.status)
        assertEquals(PluginStatus.NEEDS_SETUP, needsSetup.status)
        assertEquals(PluginStatus.UPDATE_PENDING, updatePending.status)
    }

    @Test
    fun `not activated never needs a source, whatever is installed`() {
        assertFalse(needsSource(activated = false, plugins = emptyList()))
        assertFalse(needsSource(activated = false, plugins = listOf(disabled)))
        assertFalse(needsSource(activated = false, plugins = listOf(active)))
    }

    @Test
    fun `activated with no plugins needs a source`() {
        assertTrue(needsSource(activated = true, plugins = emptyList()))
    }

    @Test
    fun `one active plugin is enough`() {
        assertFalse(needsSource(activated = true, plugins = listOf(active)))
    }

    @Test
    fun `only a disabled plugin needs a source`() {
        assertTrue(needsSource(activated = true, plugins = listOf(disabled)))
    }

    @Test
    fun `only a damaged plugin needs a source`() {
        assertTrue(needsSource(activated = true, plugins = listOf(damaged)))
    }

    @Test
    fun `only an unresponsive plugin needs a source`() {
        assertTrue(needsSource(activated = true, plugins = listOf(unresponsive)))
    }

    @Test
    fun `only a plugin that needs setup needs a source`() {
        assertTrue(needsSource(activated = true, plugins = listOf(needsSetup)))
    }

    @Test
    fun `a plugin that needs setup next to an active one is enough`() {
        assertFalse(needsSource(activated = true, plugins = listOf(needsSetup, active)))
    }

    @Test
    fun `a pending update is still usable, so it is enough`() {
        assertFalse(needsSource(activated = true, plugins = listOf(updatePending)))
    }

    @Test
    fun `several unusable plugins together still need a source`() {
        assertTrue(needsSource(activated = true, plugins = listOf(disabled, damaged, unresponsive, needsSetup)))
    }
}
