package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAddressTest {
    @Test fun `owner slash repo defaults to HEAD`() {
        val a = PluginAddress.parse(" kinotvapp/kino-plugin-archive ")!!
        assertEquals(PluginAddress("kinotvapp", "kino-plugin-archive"), a)
        assertEquals("kinotvapp/kino-plugin-archive", a.canonical)
        assertEquals(
            "https://raw.githubusercontent.com/kinotvapp/kino-plugin-archive/HEAD/kino-plugin.json",
            a.rawUrl("kino-plugin.json"),
        )
    }

    @Test fun `subdirectory and ref`() {
        val a = PluginAddress.parse("lordmacu/kino-light/plugins/archive-org@v1.2.0")!!
        assertEquals(PluginAddress("lordmacu", "kino-light", "plugins/archive-org", "v1.2.0"), a)
        assertEquals("lordmacu/kino-light/plugins/archive-org@v1.2.0", a.canonical)
        assertEquals(
            "https://raw.githubusercontent.com/lordmacu/kino-light/v1.2.0/plugins/archive-org/plugin.js",
            a.rawUrl("plugin.js"),
        )
    }

    @Test fun `github URLs normalize to the same address`() {
        assertEquals(PluginAddress("o", "r"), PluginAddress.parse("https://github.com/o/r"))
        assertEquals(PluginAddress("o", "r"), PluginAddress.parse("https://github.com/o/r.git/"))
        assertEquals(PluginAddress("o", "r"), PluginAddress.parse("https://www.github.com/o/r/"))
        assertEquals(PluginAddress("o", "r", "sub/dir", "main"), PluginAddress.parse("https://github.com/o/r/tree/main/sub/dir"))
        assertEquals(PluginAddress("o", "r", "", "v1"), PluginAddress.parse("https://github.com/o/r/tree/v1"))
        assertEquals(PluginAddress("o", "r"), PluginAddress.parse("o/r.git"))
    }

    @Test fun `a pasted raw githubusercontent file URL is its folder at its ref`() {
        val a = PluginAddress.parse("https://raw.githubusercontent.com/latinokodi/latinuvio-V2/main/manifest.json")!!
        assertEquals(PluginAddress("latinokodi", "latinuvio-V2", "", "main"), a)
        assertEquals("latinokodi/latinuvio-V2@main", a.canonical)
        assertEquals(PluginAddress("o", "r", "sub/dir", "v1"), PluginAddress.parse("https://raw.githubusercontent.com/o/r/v1/sub/dir/kino-plugin.json"))
        assertEquals(PluginAddress("o", "r", "", "main"), PluginAddress.parse("https://raw.githubusercontent.com/o/r/refs/heads/main/manifest.json"))
        assertEquals(PluginAddress("o", "r", "sub", "v2"), PluginAddress.parse("https://raw.githubusercontent.com/o/r/refs/tags/v2/sub/manifest.json"))
        assertEquals(PluginAddress("o", "r", "", "main"), PluginAddress.parse("  http://RAW.githubusercontent.com/o/r.git/main/manifest.json?token=x#L3/ "))
        assertEquals(PluginAddress("o", "r", "", "HEAD"), PluginAddress.parse("https://raw.githubusercontent.com/o/r/HEAD/other.JSON"))
    }

    @Test fun `pasted blob and raw page URLs are their folder at their ref`() {
        assertEquals(PluginAddress("o", "r", "", "main"), PluginAddress.parse("https://github.com/o/r/blob/main/manifest.json"))
        assertEquals(PluginAddress("o", "r", "a/b", "dev"), PluginAddress.parse("https://github.com/o/r/blob/dev/a/b/kino-plugin.json?plain=1#L5"))
        assertEquals(PluginAddress("o", "r", "", "main"), PluginAddress.parse("https://www.github.com/o/r/raw/main/manifest.json/"))
        assertEquals(PluginAddress("o", "r", "x", "main"), PluginAddress.parse("https://github.com/o/r/raw/refs/heads/main/x/manifest.json"))
        assertEquals(PluginAddress("o", "r", "", "main"), PluginAddress.parse("https://github.com/o/r/tree/main?tab=readme"))
    }

    @Test fun `only URLs copied from a GitHub page or file count as browsed refs`() {
        listOf(
            "https://github.com/o/r/tree/main/sub",
            "https://github.com/o/r/blob/main/manifest.json",
            "https://github.com/o/r/raw/main/manifest.json",
            "https://raw.githubusercontent.com/o/r/main/manifest.json",
        ).forEach { assertTrue(it, PluginAddress.isBrowsedRefUrl(it)) }
        listOf("o/r@main", "o/r/sub@main", "https://github.com/o/r", "https://gitlab.com/o/r/tree/main")
            .forEach { assertFalse(it, PluginAddress.isBrowsedRefUrl(it)) }
    }

    @Test fun `nonsense is refused`() {
        listOf(
            "", "o", "/o/r", "o//r", "o/r/../x", "o/r@", "o/r@a..b", "https://github.com/o",
            "https://github.com/o/r/blob/main/x.js", "https://gitlab.com/o/r", "o r/x", "-o/r",
            // file URLs: not a .json file, too few segments, traversal, other hosts
            "https://github.com/o/r/blob/main", "https://github.com/o/r/blob/main/sub/plugin.js",
            "https://github.com/o/r/raw/main/icon.png", "https://github.com/o/r/blob/main/../x/manifest.json",
            "https://raw.githubusercontent.com/o/r/main", "https://raw.githubusercontent.com/o/r",
            "https://raw.githubusercontent.com/o/r/main/plugin.js", "https://raw.githubusercontent.com/o/r/main/../manifest.json",
            "https://raw.githubusercontent.com/o/r/a..b/manifest.json", "https://raw.githubusercontent.com/o/r/refs/heads/main",
            "https://raw.githubusercontent.com/o/r/main/sub//manifest.json", "https://raw.githubusercontent.com/o/r/main/.json/..",
            "https://gitlab.com/o/r/-/raw/main/manifest.json", "https://raw.githubusercontent.com.evil.io/o/r/main/manifest.json",
            "https://evil.io/raw.githubusercontent.com/o/r/main/manifest.json", "https://gist.githubusercontent.com/o/r/raw/manifest.json",
        ).forEach { assertNull(it, PluginAddress.parse(it)) }
    }
}
