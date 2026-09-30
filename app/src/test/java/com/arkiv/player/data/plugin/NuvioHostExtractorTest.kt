package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(listOf("vixsrc.to", "api.themoviedb.org", "sub.wyzie.ru"), NuvioHostExtractor.extractHosts(source))
    }

    /**
     * The on-device garbage from Task 7 (`phisher98/phisher-nuvio-providers`): a User-Agent's version
     * numbers, an IP-shaped `120.0.0.0`, `module.exports`/`cheerio.load` identifiers. EXACT list: a
     * `containsAll` is how this shipped undetected.
     */
    @Test fun `only string literals count, never version numbers, IPs or identifiers`() {
        val source = """
            var cheerio = require("cheerio-without-node-native");
            const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
            const ip = "192.168.1.10";
            const ${'$'} = cheerio.load(html);
            ${'$'}("div.movie-item a.title").each(function () {});
            ${'$'}("span.quality");
            const mirrors = ['4khdhub.one', "uhdmovies.my"];
            module.exports = { getStreams };
        """.trimIndent()
        assertEquals(listOf("4khdhub.one", "uhdmovies.my"), NuvioHostExtractor.extractHosts(source))
    }

    @Test fun `comments and regex literals never confuse what counts as inside a string`() {
        val source = """
            // don't fetch "https://commented.example" here
            /* nor "https://blockcomment.example" */
            var cleaned = title.replace(/['"`]/g, "");
            var ratio = a / b / c;
            var real = "https://real.example/x";
        """.trimIndent()
        assertEquals(listOf("real.example"), NuvioHostExtractor.extractHosts(source))
    }

    @Test fun `template literals yield their static hosts and their expressions' own literals`() {
        val source = """
            var a = `https://tpl.example/${'$'}{id}?x=${'$'}{flag ? "https://nested.example" : "none"}`;
            var b = `https://${'$'}{sub}.dynamic.example/path`;
        """.trimIndent()
        // `https://${'$'}{sub}.dynamic.example` has no static host: it's built at runtime.
        assertEquals(listOf("nested.example", "tpl.example"), NuvioHostExtractor.extractHosts(source))
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

    @Test fun `the domains json url stops at its own closing quote in minified code`() {
        val minified = """var a="https://raw.githubusercontent.com/x/y/main/domains.json",b="https://raw.githubusercontent.com/x/y/main/other/domains.json";"""
        assertEquals("https://raw.githubusercontent.com/x/y/main/domains.json", NuvioHostExtractor.findDomainsJsonUrl(minified))
        // No `domains.json` inside the first literal: never spans across the quote into the next one.
        val spanning = """var a="https://raw.githubusercontent.com/x/y/main/list.txt",b="see domains.json";"""
        assertNull(NuvioHostExtractor.findDomainsJsonUrl(spanning))
    }

    @Test fun `no domains json url when the scraper hardcodes its host`() {
        assertNull(NuvioHostExtractor.findDomainsJsonUrl("""fetch("https://vixsrc.to/x")"""))
    }

    /**
     * The REAL shape of the file the spec cites (`phisher98/TVVVV/main/domains.json`, fetched
     * 2026-09-28, trimmed): an object of name -> full URL, shared by the whole repo's scrapers.
     */
    private val realDomainsJson = """
        {
          "moviesdrive": "https://new4.moviesdrive.christmas",
          "HDHUB4u": "https://new6.hdhub4u.cl",
          "4khdhub": "https://4khdhub.one",
          "MultiMovies": "https://multimovies.casa",
          "moviesmod": "https://moviesmod.ai.in/",
          "broken": "not a url",
          "number": 42
        }
    """.trimIndent()

    @Test fun `an object domains json yields hosts, not URLs, keeping only the keys the scraper names`() {
        val source = """
            if (data && data.moviesdrive) { MAIN_URL = data.moviesdrive; }
            var other = domains["4khdhub"];
        """.trimIndent()
        // Compared as sets per group (still exact: nothing missing, nothing extra): the JVM test
        // classpath's org.json keeps object keys in a HashMap, Android's in insertion order.
        val parsed = NuvioHostExtractor.parseDomainsJson(realDomainsJson, source)
        assertEquals(setOf("new4.moviesdrive.christmas", "4khdhub.one"), parsed.preferred.toSet())
        assertEquals(2, parsed.preferred.size)
        // Other scrapers' domains are never declared for this one.
        assertEquals(emptyList<String>(), parsed.others)
    }

    @Test fun `a key the scraper reads by destructuring counts as named, and the others stay out`() {
        val shorthand = """
            const res = await fetch(DOMAINS_URL);
            const { moviesdrive } = await res.json();
            MAIN_URL = moviesdrive;
        """.trimIndent()
        val parsed = NuvioHostExtractor.parseDomainsJson(realDomainsJson, shorthand)
        assertEquals(listOf("new4.moviesdrive.christmas"), parsed.preferred)
        assertEquals(emptyList<String>(), parsed.others)

        // Renamed, with a default, among other names, and minified: still this scraper's key.
        val renamed = """let {  other , MultiMovies: base = "x" } = data; var{moviesmod:m}=d;"""
        val both = NuvioHostExtractor.parseDomainsJson(realDomainsJson, renamed)
        assertEquals(setOf("multimovies.casa", "moviesmod.ai.in"), both.preferred.toSet())
        assertEquals(2, both.preferred.size)
        assertEquals(emptyList<String>(), both.others)
    }

    @Test fun `a destructured local NAME that only aliases another key is not a reference to that name`() {
        // `moviesdrive` here is the local variable the `other` key lands in, not a key read from the file.
        val parsed = NuvioHostExtractor.parseDomainsJson(realDomainsJson, "const { other: moviesdrive } = data;")
        assertEquals(emptyList<String>(), parsed.preferred)
        assertEquals(5, parsed.others.size)
    }

    @Test fun `an object domains json whose keys the scraper never names keeps them all as others`() {
        val parsed = NuvioHostExtractor.parseDomainsJson(realDomainsJson, "var x = 1;")
        assertEquals(emptyList<String>(), parsed.preferred)
        assertEquals(
            setOf("new4.moviesdrive.christmas", "new6.hdhub4u.cl", "4khdhub.one", "multimovies.casa", "moviesmod.ai.in"),
            parsed.others.toSet(),
        )
        assertEquals(5, parsed.others.size)
    }

    @Test fun `an array domains json of hosts or URLs is all preferred, garbage dropped`() {
        assertEquals(
            NuvioRemoteHosts(preferred = listOf("a.example", "b.example"), others = emptyList()),
            NuvioHostExtractor.parseDomainsJson("""["a.example", "https://b.example/path", "5.0", "localhost"]""", ""),
        )
        assertEquals(NuvioRemoteHosts.NONE, NuvioHostExtractor.parseDomainsJson("not json", ""))
    }

    @Test fun `primary hosts are address-named constants and URLs with a runtime query, not embed paths`() {
        val source = """
            const BASE_URL = "https://areshd.com";
            var cfg = { apiUrl: 'https://api.site.example/v1', label: "https://not-an-address.example" };
            const DOMAIN = "pelis182.net";
            fetch(`https://vidhide.example/e/${'$'}{id}`);
            fetch(`https://cuevana.unbuendato.com/?id=${'$'}{rawId}`);
            fetch("https://plain.example/list");
        """.trimIndent()
        assertEquals(
            listOf("areshd.com", "api.site.example", "pelis182.net", "cuevana.unbuendato.com"),
            NuvioHostExtractor.primaryHosts(source),
        )
    }
}
