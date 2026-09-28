package com.arkiv.player.data.plugin.discovery

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DedupeDiscoveredTest {
    private fun installed(id: String, address: String) = InstalledPlugin(
        PluginManifest(id, id, "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord(address, "1.0.0", "x", listOf("example.com"), 0L),
        iconFile = null,
    )

    @Test fun `owner repo keys ignore case, spelling, folder and ref`() {
        assertEquals("a/b", ownerRepoKey("A/B"))
        assertEquals("a/b", ownerRepoKey("https://github.com/A/b"))
        assertEquals("a/b", ownerRepoKey("a/b/sub@v1"))
        assertNull(ownerRepoKey("not an address"))
    }

    @Test fun `catalog repos, id clashes with another installed repo, and repeats are dropped`() {
        val found = listOf(
            DiscoveredPlugin("KinoTvApp", "Kino-Plugin-Archive", "archive-org", "IA", "", 9),
            DiscoveredPlugin("x", "clone", "demo", "Clone", "", 8),
            DiscoveredPlugin("o", "r", "demo", "Demo", "", 7),
            DiscoveredPlugin("k", "xuper", "xuper", "Xuper", "", 6),
            DiscoveredPlugin("K", "Xuper", "xuper", "Xuper again", "", 5),
            DiscoveredPlugin("k2", "xuper", "xuper", "Xuper 2", "", 1),
        )
        val kept = dedupeDiscovered(found, listOf("kinotvapp/kino-plugin-archive"), listOf(installed("demo", "o/r@v1")))
        assertEquals(listOf("o/r", "k/xuper"), kept.map { it.address })
    }
}
