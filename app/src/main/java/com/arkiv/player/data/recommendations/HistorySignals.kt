package com.arkiv.player.data.recommendations

import com.arkiv.player.data.db.HistoryRow
import com.arkiv.player.data.model.WorkKind

/** What the model needs to know about something watched. [kind] is `"tv"` or `"movie"`. */
internal data class Watched(val title: String, val kind: String, val status: String)

/**
 * The local history → the signals the model cares about. Ported from
 * `arkiv-api/src/arkiv_api/recomendaciones/historial.py`, over this app's base.
 *
 * - **terminado** (finished): marked as watched.
 * - **abandonado** (dropped): less than [ABANDON_THRESHOLD] watched.
 * - Halfway through says nothing (it's being watched right now): skipped, and an older row of the
 *   same item decides instead, if there is one.
 *
 * ***Repeats* are lost**: the app only saves each episode's last playback, not a playback history.
 * Adult content doesn't show up by construction: `saveProgress` in `PlayerViewModel` never writes
 * its progress to `playback` (see `shouldLogHistory` and `AdultContent.shouldLog`), so
 * there's no row this query could read.
 */
internal object HistorySignals {
    const val ABANDON_THRESHOLD = 0.10
    const val CAP = 30

    fun of(rows: List<HistoryRow>): List<Watched> {
        val decided = mutableSetOf<String>()
        val out = mutableListOf<Watched>()
        for (f in rows.sortedByDescending { it.lastPlayedAt }) {
            if (f.itemId in decided) continue
            val status = when {
                f.watched -> "terminado"
                f.durationMs > 0 && f.positionMs.toDouble() / f.durationMs < ABANDON_THRESHOLD -> "abandonado"
                else -> continue
            }
            decided += f.itemId
            val title = f.tituloCanonico?.takeIf { it.isNotBlank() } ?: f.titulo
            out += Watched(title, WorkKind.of(f.tipo, f.categoryOverride, f.episodio), status)
            if (out.size >= CAP) break
        }
        return out
    }

    /** The line sent to the model by the gateway (`recomendaciones/modelo.py`). */
    fun lines(watched: List<Watched>): String =
        watched.joinToString("\n") { "- ${it.title} (${it.kind}): ${it.status}" }
}
