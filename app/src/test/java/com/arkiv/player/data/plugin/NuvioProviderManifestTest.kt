package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioProviderManifestTest {
    private val sample = """
        { "name": "nuvio-providers", "scrapers": [
          { "id": "vixsrc", "name": "VixSrc", "filename": "providers/vixsrc.js", "enabled": true,
            "contentLanguage": ["en"], "supportedTypes": ["movie","tv"], "logo": "https://x/vixsrc.png",
            "disabledPlatforms": [] },
          { "id": "videasy", "name": "Videasy", "filename": "providers/videasy.js", "enabled": false,
            "contentLanguage": ["es"], "supportedTypes": ["movie"], "disabledPlatforms": [] },
          { "id": "iosonly", "name": "iOS Only", "filename": "providers/iosonly.js", "enabled": true,
            "supportedTypes": ["movie"], "disabledPlatforms": ["android"] }
        ] }
    """.trimIndent()

    @Test fun `parses a Nuvio manifest`() {
        val m = NuvioManifestParser.parse(sample)!!
        assertEquals("nuvio-providers", m.name)
        assertEquals(3, m.scrapers.size)
        val vixsrc = m.scrapers.first { it.id == "vixsrc" }
        assertEquals("VixSrc", vixsrc.name)
        assertEquals("providers/vixsrc.js", vixsrc.filename)
        assertEquals(listOf("en"), vixsrc.contentLanguage)
        assertTrue(vixsrc.enabled)
    }

    @Test fun `installable drops disabled and android-disabled scrapers`() {
        val m = NuvioManifestParser.parse(sample)!!
        assertEquals(listOf("vixsrc"), NuvioManifestParser.installable(m).map { it.id })
    }

    @Test fun `a kino-plugin json (no scrapers array) is not a Nuvio manifest`() {
        assertNull(NuvioManifestParser.parse("""{"id":"x","name":"X","hosts":["x.com"]}"""))
    }

    @Test fun `garbage text is not a Nuvio manifest`() {
        assertNull(NuvioManifestParser.parse("not json at all"))
    }
}
