package com.arkiv.player.data.plugin.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeedCatalogTest {
    private val seed = File("src/main/assets/plugin-catalog-seed.json").readText()

    @Test fun `the seed parses and always offers Internet Archive and own-server`() {
        val c = PluginCatalogParser.parse(seed, capabilities = emptySet())
        assertNotNull(c)
        val ids = c!!.entries.map { it.id }
        assertTrue("internet-archive" in ids && "own-server" in ids)
        assertTrue(c.entries.first { it.id == "own-server" }.needsSetup)
    }

    @Test fun `Xuper is in the seed but only visible to a build that has its bridge`() {
        assertTrue(PluginCatalogParser.parse(seed, emptySet())!!.entries.none { it.id == "xuper" })
        val withBridge = PluginCatalogParser.parse(seed, setOf("xuper-bridge"))!!.entries
        assertEquals("xuper", withBridge.first().id)
        assertTrue(withBridge.first().legacyDefault && withBridge.first().bundled)
    }
}
