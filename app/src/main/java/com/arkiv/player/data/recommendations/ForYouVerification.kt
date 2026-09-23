package com.arkiv.player.data.recommendations

import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.ai.ModelJson
import com.arkiv.player.data.ai.UnreadableJson
import com.arkiv.player.data.ai.AiResponse
import java.text.Normalizer

internal data class Candidate(val title: String, val year: String, val kind: String, val why: String)

internal data class Verified(
    val candidate: Candidate,
    val tmdbId: Int,
    val kind: String,
    val title: String,
    val posterUrl: String,
    val ref: String,
)

/** TMDB's FIRST result for that kind, or null. */
internal fun interface TmdbSearcher { suspend fun search(kind: String, title: String): TmdbItem? }

internal fun interface SourceSearcher {
    suspend fun search(title: String, kind: String, year: String, tmdbId: Int): List<GatewayResult>
}

/** Which results ARE that work. Null = didn't answer (which is different from "none"). */
internal fun interface Referee {
    suspend fun which(title: String, year: String, kind: String, results: List<GatewayResult>): List<Int>?
}

/**
 * To compare titles regardless of accents, case or punctuation. Ported from `normalizar_titulo`
 * (`recomendaciones/verificacion.py`): so "El Señor de los Anillos!" and "el senor  de los anillos"
 * end up equal. Letters from any alphabet count.
 */
internal object NormalizeTitle {
    private val MARKS = Regex("\\p{Mn}+")
    fun of(text: String): String {
        val noAccents = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "")
        val clean = noAccents.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.joinToString("")
        return clean.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * The matching referee: decides whether a source result IS the searched work. Ported from
 * `arkiv-api/src/arkiv_api/arbitro.py`, which exists because of a measured bug: "The Mandalorian"
 * ended up pointing at a podcast. Comparing the exact title doesn't work either (catalog titles
 * carry the season, "Futurama T14", or a translated name); a model comparing is the only thing
 * that covers both cases.
 */
internal class AiReferee(private val ia: suspend (String) -> AiResponse) : Referee {

    override suspend fun which(title: String, year: String, kind: String, results: List<GatewayResult>): List<Int>? {
        val list = results.take(RESULTS_CAP)
        val rows = list.mapIndexed { i, r -> row(i, r) }.joinToString("\n")
        val r = ia("${prompt(title, year, kind)}\n\n$rows")
        if (r !is AiResponse.Text) return null
        return try {
            val arr = ModelJson.array(r.text)
            // A model that answers made-up indices can't drop anyone from the list: only real
            // integers (a `true` is not an index) within range survive.
            (0 until arr.length()).mapNotNull { arr.opt(it) as? Int }.filter { it in list.indices }
        } catch (e: UnreadableJson) {
            null
        }
    }

    /** The gateway's `_fila`: `"<i>. [<source>] <title>"` and, if present, `" (<year>, <kind>, <quality>)"`. */
    private fun row(i: Int, r: GatewayResult): String {
        val details = listOf(r.year, r.kind, r.quality).filter { it.isNotBlank() }
        val base = "$i. [${r.source}] ${r.title}"
        return if (details.isEmpty()) base else "$base (${details.joinToString(", ")})"
    }

    /**
     * The gateway's prompt, except for the description of the list: it used to talk about torrent
     * release names and archive items, sources this app deleted -- today every result is a Xuper
     * or Caracol catalog entry, whose titles carry the season at the end ("Futurama T14").
     */
    private fun prompt(title: String, year: String, kind: String): String {
        val which = if (kind == "tv" || kind == "anime") "serie" else "película"
        val withYear = if (year.isNotBlank()) " ($year)" else ""
        return "Busco: $title$withYear ($which). Abajo hay una lista numerada de resultados de " +
            "los catálogos de Xuper y de Caracol. Dime cuáles corresponden a ESA obra exacta. " +
            "Una temporada o un capítulo de la serie buscada sí corresponde; un título con la " +
            "temporada al final (p. ej. 'Futurama T14') sí corresponde. Un podcast, reseña, " +
            "documental sobre la obra, otra obra del mismo universo o una de nombre parecido " +
            "NO corresponde. Si busco una película, una serie del mismo nombre NO corresponde, " +
            "y al revés tampoco. Responde SOLO un arreglo JSON con los números que sí, " +
            "p. ej. [0,2]. Si ninguno corresponde, responde []."
    }

    private companion object { const val RESULTS_CAP = 25 }
}

/**
 * The cascade that decides what reaches the row. Ported from
 * `arkiv-api/src/arkiv_api/recomendaciones/verificacion.py`.
 *
 * **The ORDER is the optimization**: each step is more expensive than the previous one, so the
 * one that discards the cheapest goes first. Querying the sources for a title TMDB doesn't even
 * know would be paying the expensive step for nothing.
 */
internal class ForYouVerification(
    private val tmdb: TmdbSearcher,
    private val sources: SourceSearcher,
    private val referee: Referee,
) {
    suspend fun verify(candidates: List<Candidate>, alreadySeen: Set<String>, cap: Int = 10): List<Verified> {
        val out = mutableListOf<Verified>()
        for (c in candidates) {
            if (out.size >= cap) break

            // 1. Does it exist? A title TMDB doesn't know was a hallucination. The search already
            //    goes with include_adult=false (`TmdbApi.search`), so adult content doesn't pass.
            val (inTmdb, kind) = realKind(c) ?: continue

            // 2. Already have it? By id and by BOTH titles. A title that normalizes to empty says
            //    nothing and can't rule anything out.
            val byTitle = listOf(NormalizeTitle.of(inTmdb.title), NormalizeTitle.of(c.title))
                .filter { it.isNotEmpty() }
            if ("tmdb:${inTmdb.id}" in alreadySeen || byTitle.any { it in alreadySeen }) continue

            // 3. Can it be played? Only here is the expensive step paid. The TMDB id travels with
            //    the search; the year does not (the adapter in AppGraph drops it, since the search
            //    query has no year field) and only reaches the arbiter in step 4.
            val year = inTmdb.year
            val results = sources.search(c.title, kind, year, inTmdb.id)
            if (results.isEmpty()) continue

            // 4. Is it that work? Total rejection = candidate discarded; referee down = the first
            //    OF THE KIND TMDB confirmed if there is one (not a hard filter: with the referee
            //    answering this doesn't weigh in), otherwise the first of all, as the gateway did.
            //    With several approved, the one that goes first IN `results` wins, not the first
            //    index the model happened to write (the JSON doesn't force ascending order) --
            //    that's how `router/search.py::buscar_para_recomendaciones` decides in the gateway.
            val indices = referee.which(c.title, year, kind, results)
            val chosen = when {
                indices == null -> results.firstOrNull { it.kind == kind } ?: results.first()
                indices.isEmpty() -> continue
                else -> {
                    val approved = indices.toSet()
                    results.withIndex().first { (i, _) -> i in approved }.value
                }
            }
            val title = inTmdb.title.ifBlank { c.title }
            out += Verified(c, inTmdb.id, kind, title, inTmdb.posterUrl, chosen.ref)
        }
        return out
    }

    /**
     * The gateway's `_buscar_tipo_real`: first the kind the model proposed (the common case,
     * cheaper), the other only if the first doesn't match EXACTLY. An exact match on either one
     * cuts it short; if neither matches, the first one that showed up wins.
     */
    private suspend fun realKind(c: Candidate): Pair<TmdbItem, String>? {
        val target = NormalizeTitle.of(c.title)
        val other = if (c.kind == "tv") "movie" else "tv"
        var best: Pair<TmdbItem, String>? = null
        for (kind in listOf(c.kind, other)) {
            val first = tmdb.search(kind, c.title) ?: continue
            if (best == null) best = first to kind
            if (target.isNotEmpty() && NormalizeTitle.of(first.title) == target) return first to kind
        }
        return best
    }
}
