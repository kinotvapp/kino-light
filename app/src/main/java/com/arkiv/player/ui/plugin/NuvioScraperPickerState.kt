package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.NuvioManifestParser
import com.arkiv.player.data.plugin.NuvioScraperEntry
import com.arkiv.player.data.plugin.PluginAddress
import java.text.Normalizer

/*
 * The pure decisions behind the full-screen Nuvio scraper picker (replaces the old dialog): the type and
 * language filters, the search box, which scrapers already have an installed plugin, and the header/badge
 * text. Nothing here touches Compose, so the phone and the TV share it and it is tested on the JVM.
 */

/** The type filter chips: "Todas", "Películas", "Series", "Anime" (from each scraper's own `supportedTypes`). */
internal enum class NuvioTypeFilter { ALL, MOVIES, SERIES, ANIME }

internal fun nuvioTypeFilterLabel(filter: NuvioTypeFilter): String = when (filter) {
    NuvioTypeFilter.ALL -> "Todas"
    NuvioTypeFilter.MOVIES -> "Películas"
    NuvioTypeFilter.SERIES -> "Series"
    NuvioTypeFilter.ANIME -> "Anime"
}

private val MOVIE_TYPES = setOf("movie", "movies", "film", "films")
private val SERIES_TYPES = setOf("series", "tv", "show", "shows")
private val ANIME_TYPES = setOf("anime")

/** Whether [scraper] declares a type this app's own catalogs use for "Película" ([MOVIE_TYPES], any spelling). */
internal fun nuvioScraperIsMovie(scraper: NuvioScraperEntry): Boolean = scraper.supportedTypes.any { it.trim().lowercase() in MOVIE_TYPES }

/** Whether [scraper] declares a "Serie" type ([SERIES_TYPES]): Nuvio's own manifests say `tv`, not `series`. */
internal fun nuvioScraperIsSeries(scraper: NuvioScraperEntry): Boolean = scraper.supportedTypes.any { it.trim().lowercase() in SERIES_TYPES }

/** Whether [scraper] declares `anime` among its `supportedTypes`, literally (spec: "anime = has `anime`"). */
internal fun nuvioScraperIsAnime(scraper: NuvioScraperEntry): Boolean = scraper.supportedTypes.any { it.trim().lowercase() in ANIME_TYPES }

/** Whether [scraper] matches [filter]: everything for [NuvioTypeFilter.ALL], its own declared type otherwise. */
internal fun nuvioScraperMatchesType(scraper: NuvioScraperEntry, filter: NuvioTypeFilter): Boolean = when (filter) {
    NuvioTypeFilter.ALL -> true
    NuvioTypeFilter.MOVIES -> nuvioScraperIsMovie(scraper)
    NuvioTypeFilter.SERIES -> nuvioScraperIsSeries(scraper)
    NuvioTypeFilter.ANIME -> nuvioScraperIsAnime(scraper)
}

/** The type chips a card shows under its name: "Película"/"Serie"/"Anime", in that order, only the ones it declares. */
internal fun nuvioScraperTypeChips(scraper: NuvioScraperEntry): List<String> = buildList {
    if (nuvioScraperIsMovie(scraper)) add("Película")
    if (nuvioScraperIsSeries(scraper)) add("Serie")
    if (nuvioScraperIsAnime(scraper)) add("Anime")
}

/** Language codes ([NuvioScraperEntry.contentLanguage]) this app groups into one "Español" bucket. */
private val SPANISH_CODES = setOf("es", "lat", "latam", "latino")

/** Whether [code] is one of the Spanish-flavoured spellings a Nuvio scraper declares (case-insensitive; `es-419`, `es-mx`, ...). */
internal fun nuvioIsSpanishLanguageCode(code: String): Boolean {
    val c = code.trim().lowercase()
    return c in SPANISH_CODES || c.startsWith("es-")
}

/** The language bucket [code] falls into: "es" for any Spanish spelling ([nuvioIsSpanishLanguageCode]), else the code itself, lower-cased. */
internal fun nuvioLanguageBucket(code: String): String = if (nuvioIsSpanishLanguageCode(code)) "es" else code.trim().lowercase()

private val LANGUAGE_LABELS = mapOf(
    "es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés", "de" to "Alemán",
    "it" to "Italiano", "ja" to "Japonés", "ko" to "Coreano", "zh" to "Chino", "hi" to "Hindi",
    "ru" to "Ruso", "ar" to "Árabe",
)

/** How a language bucket ([nuvioLanguageBucket]) reads in the filter: a Spanish name for a known code, the code itself (upper case) otherwise. */
internal fun nuvioLanguageLabel(bucket: String): String = LANGUAGE_LABELS[bucket] ?: bucket.uppercase()

/** The language buckets present across [scrapers], as filter chips: "Español" first when it is one of them, then the rest alphabetically by label. */
internal fun nuvioLanguageBuckets(scrapers: List<NuvioScraperEntry>): List<String> {
    val buckets = scrapers.flatMap { it.contentLanguage }.map(::nuvioLanguageBucket).filter { it.isNotEmpty() }.distinct()
    val (spanish, rest) = buckets.partition { it == "es" }
    return spanish + rest.sortedBy { nuvioLanguageLabel(it) }
}

/** Whether [scraper] belongs to language [bucket] (see [nuvioLanguageBucket]); a scraper with no declared language matches nothing but "Todas" (null). */
internal fun nuvioScraperMatchesLanguage(scraper: NuvioScraperEntry, bucket: String?): Boolean =
    bucket == null || scraper.contentLanguage.any { nuvioLanguageBucket(it) == bucket }

/**
 * The language filter the picker opens on: "es" (Español) when any scraper of the repo declares a Spanish
 * spelling, else null ("Todas") -- the default the spec asks for, since this repo format overwhelmingly
 * serves a Latino audience but not exclusively.
 */
internal fun defaultNuvioLanguageFilter(scrapers: List<NuvioScraperEntry>): String? =
    "es".takeIf { scrapers.any { s -> s.contentLanguage.any(::nuvioIsSpanishLanguageCode) } }

/** Whether [scraper]'s name matches [query]: a case- and accent-insensitive substring ("pelicula" finds "Películas"); blank matches everything. */
internal fun nuvioScraperMatchesQuery(scraper: NuvioScraperEntry, query: String): Boolean =
    query.isBlank() || withoutMarks(scraper.name).contains(withoutMarks(query.trim()), ignoreCase = true)

private val MARKS = Regex("\\p{Mn}+")

/** [text] with its accents and other combining marks dropped ("Películas" -> "Peliculas"). */
private fun withoutMarks(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")

/**
 * "1 fuente" / "3 fuentes": [count] with the noun that agrees with it in Spanish. [plural] defaults to
 * [singular] + "s", which covers every noun and adjective this picker counts.
 */
internal fun spanishCount(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"

/** The TV picker's header count: "1 fuente", "12 fuentes". */
internal fun nuvioPickerSourceCount(scraperCount: Int): String = spanishCount(scraperCount, "fuente")

/** The scrapers the picker's grid shows: every one of [scrapers] matching all three filters, in the manifest's own order. */
internal fun filterNuvioScrapers(scrapers: List<NuvioScraperEntry>, type: NuvioTypeFilter, language: String?, query: String): List<NuvioScraperEntry> =
    scrapers.filter {
        nuvioScraperMatchesType(it, type) && nuvioScraperMatchesLanguage(it, language) && nuvioScraperMatchesQuery(it, query)
    }

/**
 * The repo part of an address, ignoring its `@ref`: `"owner/repo"`, `"owner/repo@main"` and
 * `"https://github.com/owner/repo"` are all `"owner/repo"`. Used to match [com.arkiv.player.data.plugin.InstalledRecord.nuvioRepo]
 * (which may carry a `@main`/`@master` suffix from [com.arkiv.player.data.plugin.NuvioPluginInstaller]'s
 * branch fallback) against [NuvioPickerState.repoInput] (which may carry one too, or not, independently of
 * what is on disk) without a mismatched ref hiding an already-installed scraper. An address that does not
 * parse falls back to its lower-cased self, so it only equals the very same string.
 */
internal fun nuvioRepoKey(repo: String): String {
    val address = PluginAddress.parse(repo) ?: return repo.trim().lowercase()
    return "${address.owner}/${address.repo}".lowercase()
}

/**
 * The scraper ids of [repoInput] that already have an installed plugin, among [installed] ([sameAddress]'s
 * Nuvio counterpart: matches by repo, ignoring the ref, and by scraper id). A card whose id is in this set
 * shows "Instalado" instead of "Agregar".
 */
internal fun nuvioInstalledScraperIds(repoInput: String, installed: List<InstalledPlugin>): Set<String> {
    val key = nuvioRepoKey(repoInput)
    return installed.mapNotNullTo(HashSet()) { p ->
        val repo = p.record.nuvioRepo
        val scraperId = p.record.nuvioScraperId
        scraperId.takeIf { repo != null && scraperId != null && nuvioRepoKey(repo) == key }
    }
}

/** The repo name the picker's header shows: the address's own `repo` segment when it parses, the raw text otherwise. */
internal fun nuvioPickerRepoName(repoInput: String): String = PluginAddress.parse(repoInput)?.repo ?: repoInput

/**
 * The header line under the repo name: how many scrapers the manifest declares (installable or not -- the
 * count is about what the person can SEE, not what they can add) and that they are converted from Nuvio's
 * own GPL-3.0 code.
 */
internal fun nuvioPickerHeaderLine(scraperCount: Int): String {
    val converted = if (scraperCount == 1) "convertido" else "convertidos"
    return "${spanishCount(scraperCount, "scraper")} $converted de Nuvio (código original con licencia GPL-3.0)."
}

/** Whether [scraper] can be added right now: installable ([NuvioManifestParser.isInstallable]) and not already installed. */
internal fun nuvioScraperIsAddable(scraper: NuvioScraperEntry, installedIds: Set<String>): Boolean =
    NuvioManifestParser.isInstallable(scraper) && scraper.id !in installedIds

/** What a scraper's card button does: install it, say it is already installed, or say it can't be added (disabled by the manifest). */
internal enum class NuvioCardAction { ADD, INSTALLED, UNAVAILABLE }

/** [scraper]'s card action, given which scrapers of its repo are already installed ([nuvioInstalledScraperIds]). */
internal fun nuvioCardActionOf(scraper: NuvioScraperEntry, installedIds: Set<String>): NuvioCardAction = when {
    scraper.id in installedIds -> NuvioCardAction.INSTALLED
    NuvioManifestParser.isInstallable(scraper) -> NuvioCardAction.ADD
    else -> NuvioCardAction.UNAVAILABLE
}

/** The words on a card's button ([NuvioCardAction]). */
internal fun nuvioCardActionLabel(action: NuvioCardAction): String = when (action) {
    NuvioCardAction.ADD -> "Agregar"
    NuvioCardAction.INSTALLED -> "Instalado"
    NuvioCardAction.UNAVAILABLE -> "No disponible"
}

/** The line a card's meta row shows: language(s), version and author, whichever [scraper] declares, "·"-separated. */
internal fun nuvioScraperMetaLine(scraper: NuvioScraperEntry): String? {
    val parts = buildList {
        scraper.contentLanguage.takeIf { it.isNotEmpty() }?.let { codes -> add(codes.map { nuvioLanguageLabel(nuvioLanguageBucket(it)) }.distinct().joinToString("/")) }
        scraper.version?.let { add("v$it") }
        scraper.author?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The type chips plus the languages [scraper] declares, "·"-separated, for a detail panel's second line (TV list+detail layout). Null with neither. */
internal fun nuvioScraperTypeAndLanguageLine(scraper: NuvioScraperEntry): String? {
    val languages = scraper.contentLanguage.map { nuvioLanguageLabel(nuvioLanguageBucket(it)) }.distinct()
    val parts = nuvioScraperTypeChips(scraper) + languages
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "v1.3 · KennethJYS": version and author only, whichever [scraper] declares -- separate from [nuvioScraperMetaLine] so a detail panel can show type/language and version/author on their own lines. Null with neither. */
internal fun nuvioScraperVersionAuthorLine(scraper: NuvioScraperEntry): String? {
    val parts = listOfNotNull(scraper.version?.let { "v$it" }, scraper.author)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
