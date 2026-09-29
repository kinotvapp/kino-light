package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioHostExtractorTest {
    @Test fun `extracts domain-shaped literals and ignores file extensions`() {
        val source = """
            const BASE = "https://vixsrc.to";
            fetch(BASE + "/movie/" + id);
            fetch("https://api.themoviedb.org/3/movie/" + id + "?api_key=" + TMDB_API_KEY);
            fetch("https://sub.wyzie.ru/search?id=" + id);
            const script = require("./utils.js"); // not a host
            const img = "poster.png"; // not a host either
        """.trimIndent()
        val hosts = NuvioHostExtractor.extractHosts(source)
        assertTrue(hosts.containsAll(listOf("vixsrc.to", "api.themoviedb.org", "sub.wyzie.ru")))
        assertTrue(hosts.none { it.endsWith(".js") || it.endsWith(".png") })
    }

    @Test fun `finds a remote domains json url when the scraper loads one`() {
        val source = """
            const res = await fetch("https://raw.githubusercontent.com/phisher98/TVVVV/main/domains.json");
            const domains = await res.json();
        """.trimIndent()
        assertEquals(
            "https://raw.githubusercontent.com/phisher98/TVVVV/main/domains.json",
            NuvioHostExtractor.findDomainsJsonUrl(source),
        )
    }

    @Test fun `no domains json url when the scraper hardcodes its host`() {
        assertNull(NuvioHostExtractor.findDomainsJsonUrl("""fetch("https://vixsrc.to/x")"""))
    }
}
