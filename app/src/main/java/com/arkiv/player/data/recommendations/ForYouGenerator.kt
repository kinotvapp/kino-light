package com.arkiv.player.data.recommendations

import android.util.Log
import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.ai.ModelJson
import com.arkiv.player.data.ai.UnreadableJson
import com.arkiv.player.data.ai.AiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** When it's time to generate again. See [ForYouGenerator]'s KDoc. */
internal object ForYouGate {
    const val WINDOW_MS = 24 * 60 * 60 * 1000L
    /** A down model doesn't spend the whole window, but isn't hammered either: fifteen minutes.
     *  Also used when nothing got verified and the row is empty (see [ForYouGenerator]). */
    const val WINDOW_AFTER_FAILURE_MS = 15 * 60 * 1000L

    fun isDue(lastAttemptMs: Long, lastWasModelFailure: Boolean, nowMs: Long): Boolean {
        if (lastAttemptMs <= 0L) return true
        val window = if (lastWasModelFailure) WINDOW_AFTER_FAILURE_MS else WINDOW_MS
        return nowMs - lastAttemptMs >= window
    }
}

/** The gateway's prompt (`recomendaciones/modelo.py`), as-is, and the reading of its answer. */
internal object ForYouPrompt {
    const val HOW_MANY_TO_ASK = 20
    private val DIGITS = Regex("[0-9]+")

    /**
     * Keeps the word *repetido* even though the app doesn't send it: carried over as-is to avoid
     * touching a prompt the gateway tuned by measuring.
     */
    fun prompt(lines: String): String =
        "Eres un recomendador de películas y series para una persona de Colombia. " +
            "Te doy lo que vio: 'terminado' le gustó, 'abandonado' lo dejó (NO propongas nada " +
            "parecido), 'repetido' le gustó mucho. Propón $HOW_MANY_TO_ASK títulos que NO estén en la " +
            "lista. Responde SOLO un arreglo JSON, sin texto alrededor, con objetos " +
            "{\"titulo\",\"anio\",\"tipo\",\"porque\"}. \"tipo\" es \"movie\" o \"tv\". " +
            "\"porque\" es UNA frase corta en español de Colombia, sin voseo, que explique la " +
            "relación con lo que vio. Nada de contenido para adultos." +
            "\n\n$lines"

    /** The gateway's `_parsear`. Throws [UnreadableJson] if no array came back. */
    fun candidates(text: String): List<Candidate> {
        val arr = ModelJson.array(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("titulo").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Losing ONE candidate's year can't take down the other nineteen.
            val year = when (val a = o.opt("anio")) {
                is Int -> a.toString()
                is String -> a.trim().takeIf { DIGITS.matches(it) }.orEmpty()
                else -> ""
            }
            Candidate(
                title = title,
                year = year,
                kind = if (o.optString("tipo") == "tv") "tv" else "movie",
                why = o.optString("porque").trim(),
            )
        }
    }
}

/**
 * Generates the "For you" row on the device, with Kilo. Ported from the gateway's orchestrator
 * (`recomendaciones/generador.py`).
 *
 * Three rules rule:
 * 1. **Time-based deduplication** ([ForYouGate]): the trigger is "you finished something", which
 *    happens twenty times in an afternoon during a marathon. The window turns that into a single
 *    computation.
 * 2. **A failure never makes what was already there worse**: with no answer from the model, or
 *    none verified, the previous recommendations stay. If there are none to keep, "none
 *    verified" is retried in 15 minutes like a model failure, not in 24 hours.
 * 3. **Never throws**: runs on `applicationScope`, which has no exception handler. An unexpected
 *    exception is logged as a failure (retried in 15 min) and swallowed.
 */
internal class ForYouGenerator(
    private val ia: suspend (String) -> AiResponse,
    private val history: suspend () -> List<Watched>,
    private val alreadySeen: suspend () -> Set<String>,
    private val verify: suspend (List<Candidate>, Set<String>) -> List<Verified>,
    private val save: suspend (List<RecommendationEntity>) -> Unit,
    /** Whether the "Para ti" row has anything to show right now. */
    private val hasActive: suspend () -> Boolean,
    private val readMarks: () -> Pair<Long, Boolean>,
    private val writeMarks: (Long, Boolean) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** A single generation at a time: two triggers in a row can't both pass the gate. */
    private val inProgress = Mutex()

    suspend fun generateIfDue() {
        if (!inProgress.tryLock()) return
        val now = nowMs()
        try {
            val (last, modelFailure) = readMarks()
            if (!ForYouGate.isDue(last, modelFailure, now)) return
            val watched = history()
            if (watched.isEmpty()) return
            writeMarks(now, false)

            val r = ia(ForYouPrompt.prompt(HistorySignals.lines(watched)))
            val candidates = (r as? AiResponse.Text)?.let {
                try { ForYouPrompt.candidates(it.text) } catch (e: UnreadableJson) { null }
            }
            if (candidates.isNullOrEmpty()) {
                Log.w(TAG, "the model gave no candidates: keeping the previous recommendations")
                writeMarks(now, true)
                return
            }

            val verified = verify(candidates, alreadySeen())
            val rows = verified.mapNotNull { v ->
                val target = RecommendationSaving.targetForRef(v.ref) ?: return@mapNotNull null
                v to RecommendationSaving.itemIdFor(target)
            }.distinctBy { it.second }.mapIndexed { i, (v, id) ->
                RecommendationEntity(
                    id = id, tmdbId = v.tmdbId, tipo = v.kind, titulo = v.title,
                    posterUrl = v.posterUrl, porque = v.candidate.why, ref = v.ref,
                    orden = i, generadoAt = now, updatedAt = now,
                )
            }
            if (rows.isEmpty()) {
                // Keeping the previous ones only makes waiting a whole day acceptable if there ARE
                // previous ones. With the row empty (fresh install, wiped data) a day-long wait
                // hides it for no reason -- measured on the TV 2026-09-19 -- so it gets the same
                // short window as a model failure.
                val showing = hasActive()
                if (!showing) writeMarks(now, true)
                Log.w(
                    TAG,
                    "none verified out of ${candidates.size}: " +
                        if (showing) "keeping the previous ones" else "nothing showing, retrying in 15 min",
                )
                return
            }
            save(rows)
            Log.w(TAG, "${rows.size} new recommendations out of ${candidates.size} candidates")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "generation failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeMarks(now, true) }
        } finally {
            inProgress.unlock()
        }
    }

    private companion object { const val TAG = "ArkivForYou" }
}
