package com.arkiv.player.ui

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * How much of a progress bar to paint red, for every bar that draws "where you are": the player's
 * seek bar, the "Continuar viendo" cards, the library and the chapter lists.
 *
 * The one rule that matters: an UNKNOWN duration (0, negative, `C.TIME_UNSET`) is an EMPTY bar,
 * never a full one. The player's seek bar used to divide the position by a stand-in duration of 1
 * when it had none, so any position past the first millisecond clamped to 100%: an MPEG-TS whose
 * duration the extractor could not work out (no PCR near the end, or PCRs that go backwards across
 * a splice) opened with the bar full and the thumb parked at the end.
 */
object ProgressFraction {

    /** Red fraction for [positionMs] of [durationMs], in [0, 1]; 0 when the duration is unknown. */
    fun of(positionMs: Long, durationMs: Long): Float = of(positionMs.toFloat(), durationMs)

    /** Same, for the slider, which works in float milliseconds. */
    fun of(positionMs: Float, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs.isNaN() || positionMs <= 0f) return 0f
        return (positionMs / durationMs).coerceIn(0f, 1f)
    }

    /**
     * The value to hand the player's Slider, whose range is `0..duration` (or `0..1` while the
     * duration is unknown). Without a duration the thumb stays at the start: a position in ms
     * against a range of 1 would otherwise be clamped to the END.
     */
    fun sliderValue(positionMs: Float, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else positionMs.coerceIn(0f, durationMs.toFloat())
}

/**
 * Light, private diagnostics for the progress bars (tag [TAG]): what the player reports when it
 * becomes ready, what gets saved and what each card ends up drawing. Never a URL: only its scheme
 * and extension. Every entry point is rate-limited by the caller's own cadence (ready, a save, a
 * list emission), never per frame.
 */
object ProgressDiagnostics {

    const val TAG = "KinoProgress"

    /** `file .ts`, `http(loopback)`, `https .m3u8`: what kind of thing plays, without saying where. */
    fun uriKind(uri: String?): String {
        if (uri.isNullOrBlank()) return "none"
        val scheme = uri.substringBefore("://", "").lowercase().ifBlank { "path" }
        val rest = uri.substringAfter("://", uri)
        val host = rest.substringBefore('/').substringBefore(':').lowercase()
        val loopback = host == "127.0.0.1" || host == "localhost"
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
        return buildString {
            append(scheme)
            if (loopback) append("(loopback)")
            if (ext != null) append(" .").append(ext)
        }
    }

    /** A duration as the log shows it: `TIME_UNSET` spelled out, since it is the case being chased. */
    fun durationLabel(ms: Long): String = when {
        ms == C.TIME_UNSET -> "UNSET"
        ms <= 0L -> "${ms}ms(unknown)"
        else -> "${ms}ms"
    }

    /** One line per READY (or duration change) of [player]: duration, window, seekability, position. */
    fun playerReady(source: String, uri: String?, player: Player) {
        val windowMs = runCatching {
            val t = player.currentTimeline
            if (t.isEmpty) null else t.getWindow(player.currentMediaItemIndex, Timeline.Window()).let { w ->
                "window=${durationLabel(w.durationMs)} seekable=${w.isSeekable} dynamic=${w.isDynamic} live=${w.isLive()}"
            }
        }.getOrNull() ?: "window=none"
        val dur = player.duration
        val pos = player.currentPosition
        Log.i(
            TAG,
            "ready · source=$source uri=${uriKind(uri)} duration=${durationLabel(dur)} $windowMs " +
                "pos=${pos}ms → bar=${"%.3f".format(ProgressFraction.of(pos, dur))}",
        )
    }

    /** A progress save (throttled by [ProgressSaveLog]). */
    fun saved(episodeKind: String, positionMs: Long, durationMs: Long, watched: Boolean) {
        Log.i(
            TAG,
            "save · $episodeKind pos=${positionMs}ms dur=${durationLabel(durationMs)} " +
                "fraction=${"%.3f".format(ProgressFraction.of(positionMs, durationMs))} watched=$watched",
        )
    }

    /** The player's bar lost (or got) its duration while a position exists. */
    fun barDuration(known: Boolean, positionMs: Long, durationMs: Long) {
        Log.w(
            TAG,
            if (known) "player bar: duration known again (${durationMs}ms) at pos=${positionMs}ms"
            else "player bar: NO duration at pos=${positionMs}ms → drawn empty, not full",
        )
    }

    /** What a list of cards draws: one line per emission, a few rows at most. */
    fun cards(where: String, rows: List<Triple<String, Long, Long>>) {
        if (rows.isEmpty()) return
        val shown = rows.take(MAX_CARDS).joinToString(" | ") { (kind, pos, dur) ->
            "$kind ${pos}ms/${durationLabel(dur)}=${"%.3f".format(ProgressFraction.of(pos, dur))}"
        }
        Log.i(TAG, "$where (${rows.size}) · $shown")
    }

    /** The source prefix of an episode id (`magis`, `plugin`, `local`…), never the id itself. */
    fun episodeKind(episodeId: String): String =
        episodeId.substringBefore(':').takeIf { it.isNotBlank() && it.length <= 16 } ?: "?"

    private const val MAX_CARDS = 6
}

/**
 * Throttles the "save" diagnostic: the player saves every few seconds, the log only needs the first
 * save of an episode, a duration that changed, and then one line a minute.
 */
class ProgressSaveLog(private val everyMs: Long = 60_000L) {
    private var lastEpisode: String? = null
    private var lastDuration = Long.MIN_VALUE
    private var lastAt = Long.MIN_VALUE

    fun shouldLog(episodeId: String, durationMs: Long, nowMs: Long): Boolean {
        val log = episodeId != lastEpisode || durationMs != lastDuration || nowMs - lastAt >= everyMs
        if (log) {
            lastEpisode = episodeId
            lastDuration = durationMs
            lastAt = nowMs
        }
        return log
    }
}
