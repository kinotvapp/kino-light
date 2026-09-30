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

    @Test fun `isInstallable agrees with installable, entry by entry`() {
        val m = NuvioManifestParser.parse(sample)!!
        assertEquals(m.scrapers.filter(NuvioManifestParser::isInstallable), NuvioManifestParser.installable(m))
    }

    @Test fun `description, version and author parse when the manifest declares them`() {
        val withMeta = """
            { "name": "n", "scrapers": [
              { "id": "x", "name": "X", "filename": "x.js", "enabled": true,
                "description": "Busca en varios servidores", "version": "2.1.0", "author": "someone" }
            ] }
        """.trimIndent()
        val scraper = NuvioManifestParser.parse(withMeta)!!.scrapers.single()
        assertEquals("Busca en varios servidores", scraper.description)
        assertEquals("2.1.0", scraper.version)
        assertEquals("someone", scraper.author)
    }

    @Test fun `description, version and author are null when the manifest omits them, like the sample fixtures`() {
        val scraper = NuvioManifestParser.parse(sample)!!.scrapers.first { it.id == "vixsrc" }
        assertNull(scraper.description)
        assertNull(scraper.version)
        assertNull(scraper.author)
    }

    @Test fun `blank description, version and author are treated as absent`() {
        val blankMeta = """
            { "name": "n", "scrapers": [
              { "id": "x", "name": "X", "filename": "x.js", "enabled": true,
                "description": "", "version": "", "author": "" }
            ] }
        """.trimIndent()
        val scraper = NuvioManifestParser.parse(blankMeta)!!.scrapers.single()
        assertNull(scraper.description)
        assertNull(scraper.version)
        assertNull(scraper.author)
    }

    @Test fun `a kino-plugin json (no scrapers array) is not a Nuvio manifest`() {
        assertNull(NuvioManifestParser.parse("""{"id":"x","name":"X","hosts":["x.com"]}"""))
    }

    @Test fun `garbage text is not a Nuvio manifest`() {
        assertNull(NuvioManifestParser.parse("not json at all"))
    }
}
