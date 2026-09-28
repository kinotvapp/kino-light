package com.arkiv.player.data.plugin.discovery

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DedupeDiscoveredTest {
    private fun installed(id: String, address: String) = InstalledPlugin(
        PluginManifest(id, id, "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord(address, "1.0.0", "x", listOf("example.com"), 0L),
        iconFile = null,
    )

    private fun entry(id: String, repo: String) = CatalogEntry(id = id, repo = repo, name = id, description = "")

    private val realXuperOwner = XuperPrivilege.SOURCE_REPO.substringBefore('/')
    private val realXuperRepo = XuperPrivilege.SOURCE_REPO.substringAfter('/')
    private val realXuper = DiscoveredPlugin(realXuperOwner, realXuperRepo, XuperPrivilege.MANIFEST_ID, "Xuper", "", 3)

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
            DiscoveredPlugin("k", "extra", "extra", "Extra", "", 6),
            DiscoveredPlugin("K", "Extra", "extra", "Extra again", "", 5),
            DiscoveredPlugin("k2", "extra", "extra", "Extra 2", "", 1),
        )
        val kept = dedupeDiscovered(found, listOf(entry("archive-org", "kinotvapp/kino-plugin-archive")), listOf(installed("demo", "o/r@v1")))
        assertEquals(listOf("o/r", "k/extra"), kept.map { it.address })
    }

    @Test fun `an impostor of the reserved Xuper id with more stars, listed first, is dropped and the real one kept`() {
        val impostor = DiscoveredPlugin("evil", "kino-xuper", XuperPrivilege.MANIFEST_ID, "Xuper", "", 500)
        val kept = dedupeDiscovered(listOf(impostor, realXuper), emptyList(), emptyList())
        assertEquals(listOf(XuperPrivilege.SOURCE_REPO), kept.map { it.address })
    }

    @Test fun `an impostor of the reserved Xuper id is dropped even when the real repo is not in the results`() {
        val impostor = DiscoveredPlugin("evil", "kino-xuper", XuperPrivilege.MANIFEST_ID, "Xuper", "", 500)
        assertEquals(emptyList<DiscoveredPlugin>(), dedupeDiscovered(listOf(impostor), emptyList(), emptyList()))
    }

    @Test fun `a case-only variant of the real Xuper address still counts as the real one`() {
        val shouted = realXuper.copy(owner = realXuperOwner.uppercase(), repo = realXuperRepo.replaceFirstChar { it.uppercase() })
        assertEquals(listOf(shouted), dedupeDiscovered(listOf(shouted), emptyList(), emptyList()))
    }

    @Test fun `a plugin claiming a recommended plugin's id from another repo is dropped`() {
        val catalog = listOf(entry("archive-org", "kinotvapp/kino-plugin-archive"))
        val impostor = DiscoveredPlugin("evil", "archive", "archive-org", "Internet Archive", "", 999)
        val other = DiscoveredPlugin("someone", "cuevana", "cuevana", "Cuevana", "", 1)
        assertEquals(listOf(other), dedupeDiscovered(listOf(impostor, other), catalog, emptyList()))
    }

    @Test fun `unrelated plugins are untouched by the reserved and catalog ids`() {
        val catalog = listOf(entry("archive-org", "kinotvapp/kino-plugin-archive"))
        val found = listOf(
            DiscoveredPlugin("a", "one", "one", "One", "", 9),
            DiscoveredPlugin("b", "xuper-extras", "xuper-extras", "Xuper extras", "", 5),
            DiscoveredPlugin("c", "archive-org-mirror", "archive-org-mirror", "Mirror", "", 2),
        )
        assertEquals(found, dedupeDiscovered(found, catalog, emptyList()))
    }

    @Test fun `the Xuper repo from its legacy owner is not an impostor of the reserved id`() {
        val legacy = DiscoveredPlugin("kinotvapp", "kino-plugin-xuper", XuperPrivilege.MANIFEST_ID, "Xuper", "", 3)
        assertEquals(listOf(legacy), dedupeDiscovered(listOf(legacy), emptyList(), emptyList()))
    }

    @Test fun `a catalog listing either Xuper address hides both from the community list`() {
        val legacy = DiscoveredPlugin("kinotvapp", "kino-plugin-xuper", XuperPrivilege.MANIFEST_ID, "Xuper", "", 3)
        val oldCatalog = listOf(entry(XuperPrivilege.MANIFEST_ID, "kinotvapp/kino-plugin-xuper"))
        assertEquals(emptyList<DiscoveredPlugin>(), dedupeDiscovered(listOf(realXuper), oldCatalog, emptyList()))
        val newCatalog = listOf(entry(XuperPrivilege.MANIFEST_ID, XuperPrivilege.SOURCE_REPO))
        assertEquals(emptyList<DiscoveredPlugin>(), dedupeDiscovered(listOf(legacy), newCatalog, emptyList()))
    }

    @Test fun `someone with Xuper from the legacy address is not offered the new one as a second Xuper`() {
        val kept = dedupeDiscovered(listOf(realXuper), emptyList(), listOf(installed(XuperPrivilege.MANIFEST_ID, "kinotvapp/kino-plugin-xuper")))
        assertEquals(emptyList<DiscoveredPlugin>(), kept)
    }
}
