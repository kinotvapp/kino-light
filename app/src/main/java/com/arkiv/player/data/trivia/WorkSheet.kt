package com.arkiv.player.data.trivia

import org.json.JSONArray
import org.json.JSONObject

/**
 * The facts of a specific chapter, when the work is a series and TMDB found it.
 *
 * `season` and `episode` come from the chapter's own JSON (`season_number`/`episode_number`),
 * not from outside: that way [WorkSheet.lines] is built only with what TMDB confirmed.
 */
internal data class ChapterSheet(
    val season: Int? = null,
    val episode: Int? = null,
    val name: String? = null,
    /** `air_date`, exactly as TMDB sends it (`yyyy-MM-dd`). */
    val date: String? = null,
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val guestStars: List<String> = emptyList(),
)

/**
 * Verified TMDB facts to anchor the trivia fact to THIS exact work: never the synopsis or the
 * `overview`, because they carry plot and the trivia fact can't have spoilers.
 *
 * Built with [movieSheet] or [seriesSheet] (plus [chapterSheet] if it applies) — never by
 * hand except the minimal fallback sheet with no `tmdbId` (see `ArkivRepository.workSheetFor`),
 * which carries only [name] and nothing else.
 */
internal data class WorkSheet(
    /** "movie" or "tv", like in [TriviaSubject.kind]: decides the label and which facts make sense. */
    val kind: String,
    /** The movie's title or the series' name. Never empty if this sheet came from TMDB. */
    val name: String,
    /** `release_date` (movie) or `first_air_date` (series), exactly as TMDB sends them. */
    val releaseDate: String? = null,
    /** Movies only: `credits.crew` with `job == "Director"`. */
    val directors: List<String> = emptyList(),
    /** Movies only: `credits.crew` with `department == "Writing"`. */
    val writers: List<String> = emptyList(),
    /** The first 5 of the main cast, by `order` (`credits.cast` or `aggregate_credits.cast`). */
    val cast: List<String> = emptyList(),
    /** Movies only: `production_companies[].name`. */
    val productionCompanies: List<String> = emptyList(),
    /** Movies only: `runtime`, in minutes. */
    val runtimeMinutes: Int? = null,
    /** Series only: `created_by[].name`. */
    val creators: List<String> = emptyList(),
    /** Series only: `networks[].name`. */
    val networks: List<String> = emptyList(),
    /** Only if the work is a series chapter and TMDB found it. */
    val chapter: ChapterSheet? = null,
    /**
     * The series itself could be fetched, but a specific chapter was requested and that call
     * failed (see `ArkivRepository.workSheetFor`): the sheet is still built, without [chapter],
     * because asking with the series' facts is better than not asking. [TriviaFacts] asks with
     * this, but doesn't save the answer -saving it would leave THAT chapter with generic facts for
     * a month over a failure the next opening might not repeat.
     */
    val degraded: Boolean = false,
) {
    /**
     * The block of facts that goes in the prompt, to present them as verified TMDB data: one line
     * for the movie or series, and another for the chapter if there is one. Empty if the sheet
     * carries no fact beyond the name (the minimal fallback sheet).
     */
    fun lines(): String {
        val lines = mutableListOf<String>()
        val mainFacts = mutableListOf<String>()
        releaseDate?.let { mainFacts += if (kind == "movie") "estrenada $it" else "primera emisión $it" }
        if (kind == "movie") {
            if (directors.isNotEmpty()) mainFacts += "dirigida por ${directors.joinToString(", ")}"
            if (writers.isNotEmpty()) mainFacts += "escrita por ${writers.joinToString(", ")}"
        } else {
            if (creators.isNotEmpty()) mainFacts += "creada por ${creators.joinToString(", ")}"
            if (networks.isNotEmpty()) mainFacts += "canal ${networks.joinToString(", ")}"
        }
        if (cast.isNotEmpty()) mainFacts += "reparto: ${cast.joinToString(", ")}"
        if (kind == "movie") {
            if (productionCompanies.isNotEmpty()) mainFacts += "productoras: ${productionCompanies.joinToString(", ")}"
            runtimeMinutes?.let { mainFacts += "$it min" }
        }
        if (mainFacts.isNotEmpty()) {
            val label = if (kind == "movie") "Película" else "Serie"
            lines += "$label: $name (${mainFacts.joinToString("; ")})"
        }
        chapter?.let { c ->
            val chapterFacts = mutableListOf<String>()
            c.date?.let { chapterFacts += "emitido $it" }
            if (c.directors.isNotEmpty()) chapterFacts += "dirigido por ${c.directors.joinToString(", ")}"
            if (c.writers.isNotEmpty()) chapterFacts += "escrito por ${c.writers.joinToString(", ")}"
            if (c.guestStars.isNotEmpty()) chapterFacts += "invitados: ${c.guestStars.joinToString(", ")}"
            val numbers = when {
                c.season != null && c.episode != null -> "temporada ${c.season}, episodio ${c.episode}"
                c.episode != null -> "episodio ${c.episode}"
                c.season != null -> "temporada ${c.season}"
                else -> null
            }
            val header = listOfNotNull(numbers, c.name?.let { "«$it»" }).joinToString(", ")
            if (header.isNotBlank() || chapterFacts.isNotEmpty()) {
                val suffix = if (chapterFacts.isNotEmpty()) " (${chapterFacts.joinToString("; ")})" else ""
                lines += "Capítulo: $header$suffix"
            }
        }
        return lines.joinToString("\n")
    }
}

/** Like `optString`, but without the literal `"null"` text Android returns when the field is a
 *  real null (same pattern as `TmdbApi.text`; TMDB sends real nulls often). */
private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

/** Whether [name] has any Latin letter. TMDB stores many Japanese (and other) people's `name` in
 *  their original alphabet: with no Latin letter at all that name comes out unreadable to someone
 *  viewing from Colombia, so the person parsers drop it (never the work's or the chapter's titles,
 *  which are shown as-is). A mixed name with at least one Latin letter stays. */
private val LATIN_LETTER = Regex("\\p{IsLatin}")

private fun hasLatinLetters(name: String): Boolean = LATIN_LETTER.containsMatchIn(name)

/** The non-empty `name`s (nor the literal `"null"` text) of an array of `{"name": ...}` objects. */
private fun JSONArray?.names(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i)?.text("name")?.takeIf(String::isNotBlank) }

/** The `name`s from `crew` whose `job` is exactly "Director". */
private fun JSONArray?.directorsFrom(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i) }
        .filter { it.text("job") == "Director" }
        .mapNotNull { it.text("name").takeIf(String::isNotBlank) }
        .filter(::hasLatinLetters)
        .distinct()

/** The `name`s from `crew` whose `department` is "Writing" (a person may repeat under different `job`s). */
private fun JSONArray?.writersFrom(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i) }
        .filter { it.text("department") == "Writing" }
        .mapNotNull { it.text("name").takeIf(String::isNotBlank) }
        .filter(::hasLatinLetters)
        .distinct()

/** The first [n] `name`s of a cast array, sorted by `order` (those with no `order` go last). */
private fun JSONArray?.castFrom(n: Int): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { i -> this?.optJSONObject(i) }
        .sortedBy { it.optInt("order", Int.MAX_VALUE) }
        .mapNotNull { it.text("name").takeIf(String::isNotBlank) }
        .filter(::hasLatinLetters)
        .take(n)

private const val MAIN_CAST = 5
private const val MAIN_GUEST_STARS = 5

/** Parses `movie/{id}?append_to_response=credits`. Pure/testable (no network). Null if the JSON
 *  can't be read or doesn't even carry a title. */
internal fun movieSheet(json: String): WorkSheet? = runCatching {
    val o = JSONObject(json)
    val name = o.text("title").takeIf { it.isNotBlank() } ?: return@runCatching null
    val credits = o.optJSONObject("credits")
    WorkSheet(
        kind = "movie",
        name = name,
        releaseDate = o.text("release_date").takeIf { it.isNotBlank() },
        directors = credits?.optJSONArray("crew").directorsFrom(),
        writers = credits?.optJSONArray("crew").writersFrom(),
        cast = credits?.optJSONArray("cast").castFrom(MAIN_CAST),
        productionCompanies = o.optJSONArray("production_companies").names(),
        runtimeMinutes = o.optInt("runtime", 0).takeIf { it > 0 },
    )
}.getOrNull()

/** Parses `tv/{id}?append_to_response=aggregate_credits`. Pure/testable (no network). Null if the
 *  JSON can't be read or doesn't even carry a name. */
internal fun seriesSheet(json: String): WorkSheet? = runCatching {
    val o = JSONObject(json)
    val name = o.text("name").takeIf { it.isNotBlank() } ?: return@runCatching null
    val cast = o.optJSONObject("aggregate_credits")?.optJSONArray("cast")
    WorkSheet(
        kind = "tv",
        name = name,
        releaseDate = o.text("first_air_date").takeIf { it.isNotBlank() },
        cast = cast.castFrom(MAIN_CAST),
        creators = o.optJSONArray("created_by").names().filter(::hasLatinLetters),
        networks = o.optJSONArray("networks").names(),
    )
}.getOrNull()

/** Parses `tv/{id}/season/{s}/episode/{e}` (crew and guest_stars come at the root, with no
 *  `append_to_response` wrapper). Pure/testable (no network). Null if the JSON can't be read or
 *  doesn't even carry a season or episode number. */
internal fun chapterSheet(json: String): ChapterSheet? = runCatching {
    val o = JSONObject(json)
    val season = o.optInt("season_number", 0).takeIf { it > 0 }
    val episode = o.optInt("episode_number", 0).takeIf { it > 0 }
    if (season == null && episode == null) return@runCatching null
    val guestStars = (0 until (o.optJSONArray("guest_stars")?.length() ?: 0))
        .mapNotNull { i -> o.optJSONArray("guest_stars")?.optJSONObject(i) }
        .mapNotNull { it.text("name").takeIf(String::isNotBlank) }
        .filter(::hasLatinLetters)
        .take(MAIN_GUEST_STARS)
    ChapterSheet(
        season = season,
        episode = episode,
        name = o.text("name").takeIf { it.isNotBlank() },
        date = o.text("air_date").takeIf { it.isNotBlank() },
        directors = o.optJSONArray("crew").directorsFrom(),
        writers = o.optJSONArray("crew").writersFrom(),
        guestStars = guestStars,
    )
}.getOrNull()
