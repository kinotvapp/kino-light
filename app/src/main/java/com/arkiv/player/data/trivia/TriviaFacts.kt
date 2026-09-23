package com.arkiv.player.data.trivia

import android.util.Log
import com.arkiv.player.data.ai.ModelJson
import com.arkiv.player.data.ai.UnreadableJson
import com.arkiv.player.data.ai.AiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The work trivia facts are requested for: its identity, without its sheet. TMDB's sheet of
 * verified facts is looked up separately and only when needed (can cost up to 2 calls): see
 * [TriviaFacts.of].
 */
internal data class TriviaSubject(
    val kind: String,
    val tmdbId: Int?,
    val canonicalTitle: String?,
    val season: Int?,
    val episode: Int?,
) {
    /**
     * kind + tmdbId (or the canonical title) + season + chapter: the cache key.
     *
     * The `v3:` prefix is the version of the prompt, of the TMDB-anchored sheet and of the
     * no-Latin-letters name filter (spec addendum from 2026-09-10): without bumping it, data
     * already saved under the old key —including what brought back kanji names— would keep
     * showing up as-is.
     */
    val key: String
        get() {
            val who = tmdbId?.takeIf { it > 0 }?.toString() ?: canonicalTitle.orEmpty().trim().lowercase()
            return "v3:$kind:$who:${season ?: 0}:${episode ?: 0}"
        }

    internal companion object {
        /**
         * Null if there's no way to name it well —neither `tmdbId` nor `canonicalTitle`—: asking
         * the model blind is the fastest way for it to make something up.
         *
         * [season] and [episode] are dropped if [kind] isn't `"tv"`: `ArkivRepository.triviaSubjectFor`
         * deduces them from `displayName` when the row doesn't carry them ("Se7en" gives episode
         * 7), and a movie can't end up asking about "episode 7" or saving its answer under that key.
         */
        fun of(kind: String, tmdbId: Int?, canonicalTitle: String?, season: Int?, episode: Int?): TriviaSubject? {
            val id = tmdbId?.takeIf { it > 0 }
            val title = canonicalTitle?.trim()?.takeIf { it.isNotEmpty() }
            if (id == null && title == null) return null
            val isSeries = kind == "tv"
            return TriviaSubject(kind, id, title, season.takeIf { isSeries }, episode.takeIf { isSeries })
        }
    }
}

/**
 * The prompt is no longer the gateway's "as-is" (spec addendum from 2026-09-10): now it anchors
 * the question in a TMDB [WorkSheet], so the model has real facts to lean on instead of making
 * things up about little-known or very new works.
 */
internal object TriviaPrompt {
    const val HOW_MANY = 8
    /** Lives in the code and not just in the prompt: "short" is something a model respects sometimes. */
    const val MAX_LENGTH = 220

    /**
     * If it's a chapter, THAT chapter is asked about (season, episode and its name if the sheet
     * carries it): the gateway measured that this way chapter facts come out (director, writer,
     * premiere) and not generic series ones. The sheet goes underneath, presented as verified TMDB
     * data, and the model is asked to lean on it and prefer returning little —or nothing— over
     * making things up.
     */
    fun prompt(sheet: WorkSheet, season: Int?, episode: Int?): String {
        val work = buildString {
            append("«${sheet.name}»")
            if (episode != null) {
                append(if (season != null) ", temporada $season, episodio $episode" else ", episodio $episode")
            }
            sheet.chapter?.name?.let { append(", «$it»") }
        }
        val lines = sheet.lines()
        val sheetBlock = if (lines.isBlank()) "" else "\n\nDatos verificados de TMDB:\n$lines"
        return "Dame hasta $HOW_MANY datos curiosos y verificables sobre $work.$sheetBlock\n\n" +
            "Cada uno UNA sola frase corta, en español de Colombia, sin voseo, de menos de " +
            "$MAX_LENGTH caracteres. SIN SPOILERS: nada de lo que pasa en la trama, ni finales, " +
            "ni giros. Habla de producción, doblaje, música, reparto, rodaje, recepción o contexto " +
            "histórico. Los nombres de personas van solo en caracteres latinos: si de alguien solo " +
            "conoces el nombre en otro alfabeto, no lo menciones. Apóyate en los datos verificados " +
            "de arriba cuando los haya: nunca contradigas sus fechas ni sus nombres, y no incluyas " +
            "un dato si no estás seguro de que es cierto para ESTA obra exacta. Es mejor devolver " +
            "pocos datos, o un arreglo vacío ([]), que inventar. Responde SOLO un arreglo JSON de " +
            "cadenas, sin texto alrededor."
    }

    fun clean(array: JSONArray): List<String> =
        (0 until array.length())
            .mapNotNull { array.opt(it) as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_LENGTH }
            .take(HOW_MANY)
}

internal interface TriviaCache {
    fun read(key: String): List<String>?
    fun save(key: String, data: List<String>)
}

/**
 * The on-device file cache, 30 days per work. It's only a cache: losing it costs asking again, so
 * it doesn't deserve a Room table or its migration.
 */
internal class DiskTriviaCache(private val dir: File, private val nowMs: () -> Long) : TriviaCache {

    override fun read(key: String): List<String>? = runCatching {
        val f = file(key).takeIf { it.exists() } ?: return null
        val json = JSONObject(f.readText())
        if (nowMs() - json.getLong("t") >= TTL_MS) return null
        val arr = json.getJSONArray("d")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrNull()

    override fun save(key: String, data: List<String>) {
        runCatching {
            dir.mkdirs()
            file(key).writeText(JSONObject().put("t", nowMs()).put("d", JSONArray(data)).toString())
        }
    }

    /** The key may carry titles with any character: the file name is its hash. */
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    internal companion object {
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * The trivia facts of a work, or empty. Never throws: with no facts there's no button, which is
 * the right failure for something accessory.
 *
 * **A failure is not saved**: sealing it would leave the work with no trivia for a month over a
 * thirty-second outage. Neither is an unreadable answer, nor an array that carried data and ended
 * up empty after cleaning (that's the model stumbling). A `[]` the model returned as-is **is**
 * saved: since the 2026-09-10 spec addendum it's a legitimate answer ("I have nothing sure about
 * this work"), and not saving it would re-ask Kilo (~20 s) every time someone reopens the work.
 *
 * A [WorkSheet.degraded] sheet isn't saved either, whatever the answer: it's asked with what
 * there is anyway, but saving it would leave the requested chapter with generic series facts for
 * a month, over a failure the next opening might not repeat.
 */
internal class TriviaFacts(
    private val ia: suspend (String) -> AiResponse,
    private val cache: TriviaCache,
) {
    suspend fun of(subject: TriviaSubject, sheet: suspend () -> WorkSheet?): List<String> {
        // The cache is on disk (`DiskTriviaCache`) and this runs from `viewModelScope` (Main):
        // reading and writing it goes on Dispatchers.IO, never on the main thread.
        withContext(Dispatchers.IO) { cache.read(subject.key) }?.let { return it }
        val resolvedSheet = sheet() ?: return emptyList()
        val r = ia(TriviaPrompt.prompt(resolvedSheet, subject.season, subject.episode))
        if (r !is AiResponse.Text) return emptyList()
        val raw = try {
            ModelJson.array(r.text)
        } catch (e: UnreadableJson) {
            Log.w(TAG, "unreadable response from ${r.model}: ${e.message}")
            return emptyList()
        }
        if (raw.length() == 0) {
            if (!resolvedSheet.degraded) withContext(Dispatchers.IO) { cache.save(subject.key, emptyList()) }
            return emptyList()
        }
        val data = TriviaPrompt.clean(raw)
        if (data.isNotEmpty() && !resolvedSheet.degraded) withContext(Dispatchers.IO) { cache.save(subject.key, data) }
        return data
    }

    private companion object { const val TAG = "ArkivTrivia" }
}
