package com.arkiv.player.crash

import com.arkiv.player.playback.NoVideoFrame

/**
 * A telemetry report split the way GlitchTip needs it: a [message] that only names the problem
 * (plus low-cardinality parts such as a portal code), and the changing values in [extras].
 *
 * GlitchTip groups by exception type + message, so a channel code, a title, an episode id or a
 * resolution inside the message opened one issue per value (hundreds for `LiveResolveFailed`
 * alone). With the values in extras, one problem is one issue and every event still carries them.
 */
internal data class StableReport(val message: String, val extras: Map<String, String>)

internal object StableReports {
    /** The in-screen or local player plays audio but never painted a frame. */
    fun noVideoFrame(
        where: String,
        waitedMs: Long,
        codec: String?,
        width: Int?,
        height: Int?,
        audioTracks: Int,
        state: Int,
        more: Map<String, String> = emptyMap(),
    ) = StableReport(
        "$where: audio-only, no video frame",
        mapOf(
            "waited_ms" to waitedMs.toString(),
            "codec" to codec.orEmpty(),
            "video_size" to if (width != null && height != null) "${width}x$height" else "",
            "audio_tracks" to audioTracks.toString(),
            "playback_state" to state.toString(),
        ) + more,
    )

    /** Reports [noVideoFrame] under the usual tag. Kept here so the player screen only makes one call. */
    fun reportNoVideoFrame(report: StableReport) {
        Crash.report(NoVideoFrame(report.message), "video-no-frame", extras = report.extras)
    }

    /** A live channel could not be opened; [reason] is the portal code, `red` or `no-session`. */
    fun liveResolveFailed(channelCode: String, reason: String) = StableReport(
        "live channel did not resolve · reason=$reason",
        mapOf("channel" to channelCode, "reason" to reason),
    )

    /** A TMDB-identified title found no Magis source at all. */
    fun noSources(
        query: String,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
        poolEmpty: Boolean,
        queries: List<String>,
        fullTitleRetry: List<String>,
        forms: List<String>,
        /** The best of what the portal answered instead (its look-alikes), title only. */
        poolTop: List<String> = emptyList(),
    ) = StableReport(
        "0 sources",
        mapOf(
            "pool_top" to poolTop.toString(),
            "query" to query,
            "tmdb" to tmdbId.toString(),
            "type" to type,
            "season_episode" to "S${season}E$episode",
            "pool_empty" to poolEmpty.toString(),
            "queries" to queries.toString(),
            "full_title_retry" to fullTitleRetry.toString(),
            "forms" to forms.toString(),
        ),
    )

    /** A portal error survived every rescue and reached the user. [code] is low-cardinality; [msg] is not. */
    fun portalErrorReached(code: String, msg: String, accountLinked: Boolean, seedsExhausted: Boolean) = StableReport(
        "portal error reached the user · code=$code",
        mapOf(
            "code" to code,
            "msg" to msg,
            "account_linked" to accountLinked.toString(),
            "seeds_exhausted" to seedsExhausted.toString(),
        ),
    )

    /**
     * An offline download failed for good. The episode id used to lead the message, which made one
     * issue per episode; it goes to extras and the reason alone names the problem (with the id
     * scrubbed out of it, in case the reason quotes it).
     */
    fun offlineDownloadFailed(episodeId: String, reason: String) = StableReport(
        (if (episodeId.isNotEmpty()) reason.replace(episodeId, "[episode]") else reason).ifBlank { "download failed" },
        mapOf("episode" to episodeId, "reason" to reason),
    )
}
