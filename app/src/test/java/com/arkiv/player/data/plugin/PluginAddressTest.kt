package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `nonsense is refused`() {
        listOf(
            "", "o", "/o/r", "o//r", "o/r/../x", "o/r@", "o/r@a..b", "https://github.com/o",
            "https://github.com/o/r/blob/main/x.js", "https://gitlab.com/o/r", "o r/x", "-o/r",
        ).forEach { assertNull(it, PluginAddress.parse(it)) }
    }
}
