package com.arkiv.player.data.subtitles

import com.arkiv.player.playback.TrackLang

/** The online subtitle catalogs Kino searches. [label] is what the person reads. */
enum class SubtitleProviderId(val label: String) {
    OPENSUBTITLES("OpenSubtitles"),
    SUBDL("SubDL"),
}

/**
 * What a search asks for: one title, by the strongest id known. [imdbId] is `tt…` and, like
 * [tmdbId], names the MOVIE or, for an episode ([season]/[episode] set), the SERIES. [title] (and
 * [year]) is the last resort when no id was found. [languages] are ISO 639-1 codes in order of
 * preference ("es", "en").
 */
data class SubtitleQuery(
    val isEpisode: Boolean,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val title: String = "",
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val languages: List<String> = listOf("es", "en"),
    /** The playing file's OpenSubtitles hash, when it could be computed (OpenSubtitles only). */
    val movieHash: String? = null,
) {
    /** Nothing to search by: no id and no title. */
    val isEmpty: Boolean get() = imdbId.isNullOrBlank() && (tmdbId ?: 0) <= 0 && title.isBlank()

    /** The numeric part of [imdbId] without leading zeros (`tt0133093` → `133093`), or null. */
    val imdbNumber: Long? get() = imdbId?.trim()?.removePrefix("tt")?.toLongOrNull()?.takeIf { it > 0 }
}

/**
 * One subtitle a provider found, before downloading it. [ref] is what [SubtitleProvider.download]
 * needs (an OpenSubtitles `file_id`, a SubDL download path). [language] is ISO 639-1 lowercase.
 */
data class OnlineSubtitle(
    val provider: SubtitleProviderId,
    val ref: String,
    val language: String,
    val release: String,
    val downloads: Int = 0,
    val hearingImpaired: Boolean = false,
    /** The provider says this subtitle was made for the exact file being played (OpenSubtitles `moviehash_match`). */
    val hashMatch: Boolean = false,
)

/** Why a provider call failed, each with the sentence the person reads. */
enum class SubtitleFailure(val message: String) {
    BAD_KEY("La llave no es válida o no tiene permiso"),
    QUOTA("Límite diario de descargas alcanzado"),
    RATE_LIMITED("Demasiadas búsquedas seguidas, espera un momento"),
    TIMEOUT("El servicio tardó demasiado en responder"),
    NETWORK("Sin conexión con el servicio"),
    NO_FILE("El archivo descargado no trae un subtítulo que se pueda usar"),
    UNAVAILABLE("El servicio no está disponible ahora"),
}

/** A provider call's outcome: [Ok] or [Failed] with a [SubtitleFailure]. */
sealed interface SubtitleResult<out T> {
    data class Ok<T>(val value: T) : SubtitleResult<T>
    data class Failed(val failure: SubtitleFailure) : SubtitleResult<Nothing>
}

/** A provider's credentials for one call: [apiKey], plus an optional account (OpenSubtitles only). */
data class ProviderAuth(val apiKey: String, val username: String = "", val password: String = "")

/** One online subtitle catalog. Every call runs off the main thread and never throws. */
interface SubtitleProvider {
    val id: SubtitleProviderId

    suspend fun search(query: SubtitleQuery, auth: ProviderAuth): SubtitleResult<List<OnlineSubtitle>>

    /** The subtitle's text file (SRT or WebVTT), raw bytes in whatever encoding it came. */
    suspend fun download(subtitle: OnlineSubtitle, auth: ProviderAuth, query: SubtitleQuery): SubtitleResult<ByteArray>

    /** Does [auth] work? A cheap authenticated call ("Probar llave"). */
    suspend fun test(auth: ProviderAuth): SubtitleResult<Unit>
}

/** Pure helpers shared by the providers, the service and the screens. */
object OnlineSubtitleRules {

    /** At most this many results per provider reach the menu. */
    const val MAX_RESULTS = 15

    /**
     * The ISO 639-1 codes to ask for, from the person's subtitle order: every Spanish variant is
     * "es" (neither catalog tells Latin American from Castilian reliably), English "en", Japanese
     * "ja"; Spanish always goes in, and English as the fallback most titles have.
     */
    fun languagesFor(order: List<TrackLang>): List<String> {
        val codes = order.mapNotNull {
            when (it) {
                TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL -> "es"
                TrackLang.ENGLISH -> "en"
                TrackLang.JAPANESE -> "ja"
                TrackLang.UNKNOWN -> null
            }
        }
        return (codes + "es" + "en").distinct()
    }

    /**
     * [results] in the menu's order: a hash match first, then the closest release name to [file]
     * (in tenths, so a tiny difference never outranks the person's language), then the preferred
     * language, then the most downloaded; capped at [cap], but a hash match is never cut.
     */
    fun sort(results: List<OnlineSubtitle>, languages: List<String>, cap: Int = MAX_RESULTS, file: FileHint? = null): List<OnlineSubtitle> {
        fun rank(lang: String) = languages.indexOf(lang).let { if (it < 0) languages.size else it }
        val hint = file?.takeUnless { it.isBlank }
        fun similarity(s: OnlineSubtitle) = if (hint == null) 0 else Math.round(ReleaseMatch.score(s.release, hint) * 10).toInt()
        val sorted = results.sortedWith(
            compareByDescending<OnlineSubtitle> { it.hashMatch }
                .thenByDescending { similarity(it) }
                .thenBy { rank(it.language) }
                .thenByDescending { it.downloads },
        )
        return sorted.take(maxOf(cap, sorted.count { it.hashMatch }))
    }

    /** The provider groups with a hash match first; the rest keep the person's order. */
    fun orderGroups(groups: List<ProviderResults>): List<ProviderResults> =
        groups.sortedByDescending { g -> g.results.any { it.hashMatch } }

    /** A language code as the menu names it, in Spanish. */
    fun languageName(code: String): String = when (code.lowercase()) {
        "es", "spa", "es-419", "ea" -> "Español"
        "en", "eng" -> "Inglés"
        "ja", "jpn" -> "Japonés"
        "pt", "pt-br", "pb", "por" -> "Portugués"
        "fr", "fre", "fra" -> "Francés"
        "it", "ita" -> "Italiano"
        "de", "ger", "deu" -> "Alemán"
        "" -> "Desconocido"
        else -> java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale("es"))
            .takeIf { it.isNotBlank() && !it.equals(code, true) }?.replaceFirstChar { it.uppercase() } ?: code.uppercase()
    }

    /**
     * The name the downloaded track gets in the audio and subtitles menu, unique enough to be
     * remembered per title ([TitleSubtitleMemory] matches by label): language, provider and the
     * start of the release.
     */
    fun trackLabel(subtitle: OnlineSubtitle): String {
        val release = subtitle.release.trim().take(28)
        return buildString {
            append(languageName(subtitle.language))
            append(" · ")
            append(subtitle.provider.label)
            if (release.isNotEmpty()) append(" · ").append(release)
        }.replace('\t', ' ').replace('\n', ' ')
    }

    /** [key] for a log line: never the key itself, only its length and last two characters. */
    fun mask(key: String): String = if (key.isBlank()) "<none>" else "…" + key.takeLast(2) + " (${key.length})"

    /** The first `tt` IMDb id in [text] (an item id like `tt0133093` or `web:series:tt…`), or null. */
    fun imdbIn(text: String?): String? = text?.let { Regex("tt\\d{7,9}").find(it)?.value }

    /** A year in parentheses at the end of [title] ("Matrix (1999)" → 1999), or null. */
    fun yearIn(title: String): Int? =
        Regex("\\((\\d{4})\\)\\s*$").find(title.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    /** [title] without a trailing "(year)". */
    fun bareTitle(title: String): String = title.trim().replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
}
