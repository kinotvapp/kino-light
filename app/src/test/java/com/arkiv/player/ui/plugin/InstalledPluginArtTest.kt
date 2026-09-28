package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.catalog.CatalogArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Which icon an installed plugin's row draws: its own (from its installed files) and, when it has none,
 * the art its catalog repo ships; and how that art is found for an installed plugin, whose address is the
 * canonical `owner/repo` while the art map is keyed by the catalog entry's own spelling.
 */
class InstalledPluginArtTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String): File = tmp.newFile(name)

    @Test fun `a plugin's own icon wins over the catalog art`() {
        val own = file("own.png")
        val art = CatalogArt("#112233", file("art.png"))
        assertEquals(own, installedIconFile(own, art))
    }

    @Test fun `a plugin with no icon of its own falls back to the catalog art`() {
        val artIcon = file("art.png")
        assertEquals(artIcon, installedIconFile(null, CatalogArt("#112233", artIcon)))
    }

    @Test fun `an own icon that is no longer on disk falls back to the catalog art`() {
        val gone = File(tmp.root, "gone.png")
        val artIcon = file("art.png")
        assertEquals(artIcon, installedIconFile(gone, CatalogArt(null, artIcon)))
    }

    @Test fun `an own icon that is a folder is not an icon`() {
        val folder = tmp.newFolder("icon.png")
        val artIcon = file("art.png")
        assertEquals(artIcon, installedIconFile(folder, CatalogArt(null, artIcon)))
    }

    @Test fun `a plugin with no icon and art that has none shows none`() {
        assertNull(installedIconFile(null, CatalogArt("#112233", null)))
    }

    @Test fun `a plugin with no icon and no art shows none`() {
        assertNull(installedIconFile(null, null))
        assertNull(installedIconFile(File(tmp.root, "gone.png"), null))
    }

    @Test fun `the own icon is used even when there is no art at all`() {
        val own = file("own.png")
        assertEquals(own, installedIconFile(own, null))
    }

    private val xuperArt = CatalogArt("#AA0000", File("xuper.png"))
    private val otherArt = CatalogArt("#00AA00", File("other.png"))

    @Test fun `the art of an installed plugin is found by its address`() {
        val art = mapOf("lordmacu/kino-plugin-xuper" to xuperArt, "o/other" to otherArt)
        assertEquals(xuperArt, artForInstalled(art, "lordmacu/kino-plugin-xuper"))
        assertEquals(otherArt, artForInstalled(art, "o/other"))
    }

    @Test fun `a Xuper installed from its legacy address matches the catalog's new Xuper address, and only that`() {
        assertTrue(sameAddress("kinotvapp/kino-plugin-xuper", "xuper-plugin/kino-plugin-xuper"))
        assertTrue(sameAddress("https://github.com/xuper-plugin/kino-plugin-xuper", "kinotvapp/kino-plugin-xuper"))
        assertFalse(sameAddress("kinotvapp/kino-plugin-xuper@dev", "xuper-plugin/kino-plugin-xuper"))
        assertFalse(sameAddress("someone/kino-plugin-xuper", "xuper-plugin/kino-plugin-xuper"))
        assertEquals(xuperArt, artForInstalled(mapOf("xuper-plugin/kino-plugin-xuper" to xuperArt), "kinotvapp/kino-plugin-xuper"))
    }

    @Test fun `the art is found whatever the spelling of the catalog's repo`() {
        // installed.json stores the canonical address; the catalog may spell the same plugin another way.
        assertEquals(xuperArt, artForInstalled(mapOf("https://github.com/o/r" to xuperArt), "o/r"))
        assertEquals(xuperArt, artForInstalled(mapOf("o/r.git" to xuperArt), "o/r"))
        assertEquals(xuperArt, artForInstalled(mapOf("o/r@HEAD" to xuperArt), "o/r"))
    }

    @Test fun `a plugin of another repo or another ref does not take a neighbour's art`() {
        val art = mapOf("o/r" to xuperArt)
        assertNull(artForInstalled(art, "o/other"))
        assertNull(artForInstalled(art, "o/r@v2"))
        assertNull(artForInstalled(art, "p/r"))
    }

    @Test fun `no art at all finds nothing`() {
        assertNull(artForInstalled(emptyMap(), "o/r"))
    }

    @Test fun `an address that does not parse only matches the very same string`() {
        val art = mapOf("not an address" to xuperArt)
        assertEquals(xuperArt, artForInstalled(art, "not an address"))
        assertNull(artForInstalled(art, "o/r"))
    }
}
