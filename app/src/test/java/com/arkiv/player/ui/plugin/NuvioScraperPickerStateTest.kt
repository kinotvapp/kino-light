package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.NuvioScraperEntry
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioScraperPickerStateTest {
    private fun scraper(
        id: String,
        name: String = id,
        types: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        enabled: Boolean = true,
        disabledPlatforms: List<String> = emptyList(),
        version: String? = null,
        author: String? = null,
    ) = NuvioScraperEntry(
        id = id, name = name, filename = "$id.js", enabled = enabled, contentLanguage = languages,
        supportedTypes = types, logo = null, disabledPlatforms = disabledPlatforms, version = version, author = author,
    )

    // ---- type filter ----

    @Test fun `movie, series and anime types match their own Todas-independent filter`() {
        val movie = scraper("m", types = listOf("movie"))
        val series = scraper("s", types = listOf("tv"))
        val anime = scraper("a", types = listOf("anime"))
        assertTrue(nuvioScraperMatchesType(movie, NuvioTypeFilter.MOVIES))
        assertFalse(nuvioScraperMatchesType(movie, NuvioTypeFilter.SERIES))
        assertTrue(nuvioScraperMatchesType(series, NuvioTypeFilter.SERIES))
        assertTrue(nuvioScraperMatchesType(anime, NuvioTypeFilter.ANIME))
        assertFalse(nuvioScraperMatchesType(anime, NuvioTypeFilter.MOVIES))
    }

    @Test fun `Todas matches every scraper whatever its declared types`() {
        assertTrue(nuvioScraperMatchesType(scraper("x"), NuvioTypeFilter.ALL))
        assertTrue(nuvioScraperMatchesType(scraper("x", types = listOf("movie", "tv", "anime")), NuvioTypeFilter.ALL))
    }

    @Test fun `a scraper declaring several types matches each of their filters`() {
        val both = scraper("b", types = listOf("movie", "tv"))
        assertTrue(nuvioScraperMatchesType(both, NuvioTypeFilter.MOVIES))
        assertTrue(nuvioScraperMatchesType(both, NuvioTypeFilter.SERIES))
        assertFalse(nuvioScraperMatchesType(both, NuvioTypeFilter.ANIME))
    }

    @Test fun `type chips list only the types a scraper declares, in Pelicula-Serie-Anime order`() {
        assertEquals(listOf("Película", "Serie", "Anime"), nuvioScraperTypeChips(scraper("x", types = listOf("anime", "tv", "movie"))))
        assertEquals(listOf("Serie"), nuvioScraperTypeChips(scraper("x", types = listOf("tv"))))
        assertEquals(emptyList<String>(), nuvioScraperTypeChips(scraper("x")))
    }

    // ---- language buckets ----

    @Test fun `es, es dash codes, lat, latam and latino all bucket as Spanish`() {
        listOf("es", "ES", "es-419", "es-MX", "lat", "latam", "LATINO").forEach {
            assertEquals("Spanish bucket for '$it'", "es", nuvioLanguageBucket(it))
        }
    }

    @Test fun `an unknown code buckets as itself, lower-cased`() {
        assertEquals("en", nuvioLanguageBucket("EN"))
        assertEquals("xx", nuvioLanguageBucket("xx"))
    }

    @Test fun `known buckets get a Spanish label, unknown ones fall back to the upper-cased code`() {
        assertEquals("Español", nuvioLanguageLabel("es"))
        assertEquals("Inglés", nuvioLanguageLabel("en"))
        assertEquals("XX", nuvioLanguageLabel("xx"))
    }

    @Test fun `language buckets list every code present, Espanol first when it is one of them`() {
        val scrapers = listOf(
            scraper("a", languages = listOf("en")),
            scraper("b", languages = listOf("lat")),
            scraper("c", languages = listOf("pt")),
        )
        assertEquals(listOf("es", "en", "pt"), nuvioLanguageBuckets(scrapers))
    }

    @Test fun `language buckets are empty with nothing declared`() {
        assertEquals(emptyList<String>(), nuvioLanguageBuckets(listOf(scraper("a"))))
    }

    @Test fun `a scraper matches a language filter by any of its declared codes`() {
        val s = scraper("s", languages = listOf("en", "latino"))
        assertTrue(nuvioScraperMatchesLanguage(s, "es"))
        assertTrue(nuvioScraperMatchesLanguage(s, "en"))
        assertFalse(nuvioScraperMatchesLanguage(s, "pt"))
        assertTrue(nuvioScraperMatchesLanguage(s, null)) // Todas
    }

    @Test fun `default language filter preselects Espanol when any scraper declares it`() {
        val scrapers = listOf(scraper("a", languages = listOf("en")), scraper("b", languages = listOf("es-419")))
        assertEquals("es", defaultNuvioLanguageFilter(scrapers))
    }

    @Test fun `default language filter is Todas (null) when nothing is Spanish`() {
        val scrapers = listOf(scraper("a", languages = listOf("en")), scraper("b", languages = listOf("pt")))
        assertNull(defaultNuvioLanguageFilter(scrapers))
    }

    @Test fun `default language filter is Todas with no scrapers at all`() {
        assertNull(defaultNuvioLanguageFilter(emptyList()))
    }

    // ---- search ----

    @Test fun `query ignores accents on either side`() {
        val s = scraper("x", name = "Películas Latino")
        assertTrue(nuvioScraperMatchesQuery(s, "pelicula"))
        assertTrue(nuvioScraperMatchesQuery(s, "PELÍCULAS"))
        assertTrue(nuvioScraperMatchesQuery(scraper("y", name = "Peliculas"), "películas"))
        assertFalse(nuvioScraperMatchesQuery(s, "serie"))
    }

    @Test fun `query matches the name case-insensitively, substring`() {
        val s = scraper("x", name = "CineCalidad")
        assertTrue(nuvioScraperMatchesQuery(s, "cine"))
        assertTrue(nuvioScraperMatchesQuery(s, "CALIDAD"))
        assertFalse(nuvioScraperMatchesQuery(s, "nope"))
        assertTrue(nuvioScraperMatchesQuery(s, "  ")) // blank: matches everything
    }

    // ---- combined filter ----

    @Test fun `filterNuvioScrapers applies type, language and query together, keeping manifest order`() {
        val a = scraper("a", name = "Alpha", types = listOf("movie"), languages = listOf("es"))
        val b = scraper("b", name = "Beta", types = listOf("tv"), languages = listOf("es"))
        val c = scraper("c", name = "Gamma", types = listOf("movie"), languages = listOf("en"))
        val all = listOf(a, b, c)
        assertEquals(listOf("a"), filterNuvioScrapers(all, NuvioTypeFilter.MOVIES, "es", "").map { it.id })
        assertEquals(listOf("a", "c"), filterNuvioScrapers(all, NuvioTypeFilter.MOVIES, null, "").map { it.id })
        assertEquals(listOf("a", "b"), filterNuvioScrapers(all, NuvioTypeFilter.ALL, "es", "").map { it.id })
        assertEquals(listOf("b"), filterNuvioScrapers(all, NuvioTypeFilter.ALL, null, "eta").map { it.id })
    }

    // ---- repo key / installed matching ----

    @Test fun `repo key ignores the ref suffix, case-insensitively`() {
        assertEquals(nuvioRepoKey("owner/repo"), nuvioRepoKey("owner/repo@main"))
        assertEquals(nuvioRepoKey("owner/repo"), nuvioRepoKey("Owner/Repo@master"))
    }

    @Test fun `an address that does not parse falls back to itself, lower-cased`() {
        assertEquals("not a valid address", nuvioRepoKey("Not A Valid Address"))
    }

    private val manifest = PluginManifest("id", "Name", "1.0.0", 1, "p.js", "", "lordmacu", "", listOf("h.com"), setOf("search"), null, null)
    private fun installedFrom(repo: String, scraperId: String) = InstalledPlugin(
        manifest, InstalledRecord("addr", "1.0.0", "x", listOf("h.com"), 0L, nuvioRepo = repo, nuvioScraperId = scraperId), null,
    )

    @Test fun `installed scraper ids match regardless of an at-ref suffix on either side`() {
        val installed = listOf(installedFrom("owner/repo@main", "fakesrc"))
        assertEquals(setOf("fakesrc"), nuvioInstalledScraperIds("owner/repo", installed))
        assertEquals(setOf("fakesrc"), nuvioInstalledScraperIds("owner/repo@main", installed))
    }

    @Test fun `installed scraper ids exclude a plugin installed from another repo, or not from Nuvio at all`() {
        val installed = listOf(
            installedFrom("owner/other-repo", "fakesrc"),
            InstalledPlugin(manifest, InstalledRecord("addr2", "1.0.0", "x", listOf("h.com"), 0L), null), // not Nuvio
        )
        assertEquals(emptySet<String>(), nuvioInstalledScraperIds("owner/repo", installed))
    }

    @Test fun `a scraper is addable when installable and not already installed`() {
        val installable = scraper("a", enabled = true)
        val disabled = scraper("b", enabled = false)
        val androidDisabled = scraper("c", disabledPlatforms = listOf("android"))
        assertTrue(nuvioScraperIsAddable(installable, emptySet()))
        assertFalse(nuvioScraperIsAddable(disabled, emptySet()))
        assertFalse(nuvioScraperIsAddable(androidDisabled, emptySet()))
        assertFalse(nuvioScraperIsAddable(installable, setOf("a")))
    }

    @Test fun `card action is INSTALLED, then ADD, then UNAVAILABLE, in that priority`() {
        assertEquals(NuvioCardAction.INSTALLED, nuvioCardActionOf(scraper("a"), setOf("a")))
        assertEquals(NuvioCardAction.ADD, nuvioCardActionOf(scraper("a"), emptySet()))
        assertEquals(NuvioCardAction.UNAVAILABLE, nuvioCardActionOf(scraper("a", enabled = false), emptySet()))
        // Installed wins even if the manifest would now say it's not installable (already on disk, upstream disabled it later).
        assertEquals(NuvioCardAction.INSTALLED, nuvioCardActionOf(scraper("a", enabled = false), setOf("a")))
    }

    @Test fun `card action labels are Spanish, one word each`() {
        assertEquals("Agregar", nuvioCardActionLabel(NuvioCardAction.ADD))
        assertEquals("Instalado", nuvioCardActionLabel(NuvioCardAction.INSTALLED))
        assertEquals("No disponible", nuvioCardActionLabel(NuvioCardAction.UNAVAILABLE))
    }

    // ---- header / meta text ----

    @Test fun `repo name is the address's own repo segment, not the whole typed text`() {
        assertEquals("nuvio-providers", nuvioPickerRepoName("owner/nuvio-providers@main"))
        assertEquals("garbage input", nuvioPickerRepoName("garbage input"))
    }

    @Test fun `header line agrees in number and names the GPL-3-0 license`() {
        assertTrue(nuvioPickerHeaderLine(1), nuvioPickerHeaderLine(1).startsWith("1 scraper convertido de Nuvio "))
        assertTrue(nuvioPickerHeaderLine(3), nuvioPickerHeaderLine(3).startsWith("3 scrapers convertidos de Nuvio "))
        assertTrue(nuvioPickerHeaderLine(0), nuvioPickerHeaderLine(0).startsWith("0 scrapers convertidos "))
        assertTrue(nuvioPickerHeaderLine(3).contains("GPL-3.0"))
    }

    @Test fun `spanishCount agrees the noun with the number`() {
        assertEquals("1 fuente", nuvioPickerSourceCount(1))
        assertEquals("12 fuentes", nuvioPickerSourceCount(12))
        assertEquals("0 fuentes", nuvioPickerSourceCount(0))
        assertEquals("2 países", spanishCount(2, "país", "países"))
    }

    @Test fun `meta line names each language once`() {
        assertEquals("Español · v1.2", nuvioScraperMetaLine(scraper("x", languages = listOf("es", "es-MX", "latino"), version = "1.2")))
        assertEquals("Español/Inglés", nuvioScraperMetaLine(scraper("x", languages = listOf("es", "en", "es-419"))))
    }

    @Test fun `meta line joins language, version and author, skipping whichever is absent`() {
        assertEquals("Español · v2.1.0 · someone", nuvioScraperMetaLine(scraper("x", languages = listOf("es"), version = "2.1.0", author = "someone")))
        assertEquals("v1.0.0", nuvioScraperMetaLine(scraper("x", version = "1.0.0")))
        assertNull(nuvioScraperMetaLine(scraper("x")))
    }

    @Test fun `type-and-language line joins type chips and languages, null with neither`() {
        assertEquals(
            "Película · Serie · Español",
            nuvioScraperTypeAndLanguageLine(scraper("x", types = listOf("movie", "tv"), languages = listOf("es"))),
        )
        assertEquals("Película", nuvioScraperTypeAndLanguageLine(scraper("x", types = listOf("movie"))))
        assertNull(nuvioScraperTypeAndLanguageLine(scraper("x")))
    }

    @Test fun `version-author line has only version and author, never language`() {
        assertEquals("v1.3 · KennethJYS", nuvioScraperVersionAuthorLine(scraper("x", languages = listOf("es"), version = "1.3", author = "KennethJYS")))
        assertEquals("v1.3", nuvioScraperVersionAuthorLine(scraper("x", version = "1.3")))
        assertEquals("KennethJYS", nuvioScraperVersionAuthorLine(scraper("x", author = "KennethJYS")))
        assertNull(nuvioScraperVersionAuthorLine(scraper("x", languages = listOf("es"))))
    }
}
