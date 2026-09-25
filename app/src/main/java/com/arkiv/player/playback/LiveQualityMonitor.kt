package com.arkiv.player.playback

import android.os.Build
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.arkiv.player.crash.Crash
import com.arkiv.player.crash.LivePlaybackQuality
import com.arkiv.player.dlna.DlnaXml
import java.io.IOException

/**
 * Watches a live channel from the PLAYER's side and writes down what the viewer sees, under the `ArkivLive` tag:
 * every rebuffer (when it started, how long it froze), dropped frames, audio underruns, every failed load, which
 * decoder is doing the work (a software one is a common cause of choppy video on a weak box), format changes,
 * position discontinuities, and, every 10 s, a one-line health report with the buffer level and how far behind
 * the live edge playback is.
 *
 * If the session turns out degraded (see [LiveQualityStats.degradedReason]) ONE [LivePlaybackQuality] event goes to
 * GlitchTip with the details as extras, so a bad channel or device shows up without anyone reporting it. Counting is
 * in [LiveQualityStats]; this class only listens and formats.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LiveQualityMonitor(private val player: ExoPlayer) : AnalyticsListener {

    private val stats = LiveQualityStats(SystemClock::elapsedRealtime)
    var videoDecoder = ""
        private set
    private var audioDecoder = ""
    private var videoFormat = ""
    private var audioFormat = ""
    var softwareDecoder = false
        private set
    private var reported = false
    private var lastSpeed = 1f
    private var bandwidthKbps = 0L

    private var segmentLoads = 0
    private var segmentLoadMsSum = 0L
    private var segmentLoadMsMax = 0L

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val name = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?"
        }
        val endedStall = stats.onState(state)
        when {
            // BUFFERING straight after READY is a stall. Before the first READY it's the channel opening, and after
            // an IDLE (the app left and came back) it's a resume: neither is the buffer running dry.
            state == Player.STATE_BUFFERING && stats.isStalled ->
                LiveLog.w("STALL started: the buffer ran dry (state -> BUFFERING) after ${stats.watchedMs() / 1000}s of playback")
            endedStall > 0 -> {
                val message = "STALL ended: frozen ${endedStall}ms (#${stats.stalls}, worst ${stats.worstStallMs}ms, total ${stats.stallMs}ms)"
                if (endedStall >= LONG_STALL_MS) LiveLog.w(message) else LiveLog.i(message)
            }
            state == Player.STATE_READY && stats.startupMs >= 0 && stats.watchedMs() < 1_000 ->
                LiveLog.i("READY: playback started ${stats.startupMs}ms after the player was created")
            else -> LiveLog.i("state -> $name")
        }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        stats.onDroppedFrames(droppedFrames)
        val message = "dropped $droppedFrames video frame(s) in ${elapsedMs}ms (total ${stats.droppedFrames})"
        if (droppedFrames >= MANY_DROPPED) LiveLog.w(message) else LiveLog.i(message)
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        stats.onAudioUnderrun()
        LiveLog.w("AUDIO UNDERRUN: buffer ${bufferSizeMs}ms, ${elapsedSinceLastFeedMs}ms since the last feed (an audible glitch) #${stats.audioUnderruns}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        if (!wasCanceled) stats.onLoadError()
        val code = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        LiveLog.w(
            "load ERROR ${if (wasCanceled) "(canceled) " else ""}type=${dataType(mediaLoadData.dataType)} " +
                "${code?.let { "http=$it " } ?: ""}${error.javaClass.simpleName}: ${error.message} " +
                "after ${loadEventInfo.loadDurationMs}ms/${loadEventInfo.bytesLoaded}B · ${DlnaXml.safeUrl(loadEventInfo.uri.toString())}",
        )
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        // Media segments only: the playlist refreshes are logged by the proxy. Aggregated, not one line each.
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        segmentLoads++
        segmentLoadMsSum += loadEventInfo.loadDurationMs
        segmentLoadMsMax = maxOf(segmentLoadMsMax, loadEventInfo.loadDurationMs)
        if (loadEventInfo.loadDurationMs > SLOW_LOAD_MS) {
            LiveLog.w("slow segment load: ${loadEventInfo.loadDurationMs}ms for ${loadEventInfo.bytesLoaded / 1024}KB · ${DlnaXml.safeUrl(loadEventInfo.uri.toString())}")
        }
    }

    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        val f = mediaLoadData.trackFormat ?: return
        when (mediaLoadData.trackType) {
            C.TRACK_TYPE_VIDEO -> {
                videoFormat = describe(f)
                LiveLog.i("video format: $videoFormat")
            }
            C.TRACK_TYPE_AUDIO -> {
                audioFormat = describe(f)
                LiveLog.i("audio format: $audioFormat")
            }
        }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
        softwareDecoder = isSoftware(decoderName)
        val message = "video decoder: $decoderName (init ${initializationDurationMs}ms)"
        if (softwareDecoder) LiveLog.w("$message SOFTWARE: no hardware acceleration, likely choppy on a weak device") else LiveLog.i(message)
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoder = decoderName
        LiveLog.i("audio decoder: $decoderName (init ${initializationDurationMs}ms)")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        LiveLog.i("video decoder input: ${describe(format)} fps=${format.frameRate} sar=${format.pixelWidthHeightRatio} rotation=${format.rotationDegrees} codecs=${format.codecs} color=${format.colorInfo}")
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val jump = newPosition.positionMs - oldPosition.positionMs
        val message = "position discontinuity ${reasonName(reason)}: ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms (${if (jump >= 0) "+" else ""}${jump}ms)"
        // A jump that isn't a seek nobody asked for is what a viewer perceives as the picture skipping.
        if (reason == Player.DISCONTINUITY_REASON_INTERNAL || (reason != Player.DISCONTINUITY_REASON_SEEK && kotlin.math.abs(jump) > BIG_JUMP_MS)) {
            LiveLog.w(message)
        } else {
            LiveLog.i(message)
        }
    }

    override fun onPlaybackParametersChanged(eventTime: AnalyticsListener.EventTime, playbackParameters: PlaybackParameters) {
        // Live catch-up speeds playback up or down slightly to stay near the live edge; a large change is audible.
        if (playbackParameters.speed != lastSpeed) {
            LiveLog.i("playback speed $lastSpeed -> ${playbackParameters.speed} (live catch-up)")
            lastSpeed = playbackParameters.speed
        }
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        bandwidthKbps = bitrateEstimate / 1000
    }

    /** The 10 s health line. Called from the screen's polling loop, on the player's own thread. */
    fun logSummary() {
        stats.renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: stats.renderedFrames
        val offset = player.currentLiveOffset.takeIf { it != C.TIME_UNSET }
        val target = player.currentMediaItem?.liveConfiguration?.targetOffsetMs?.takeIf { it != C.TIME_UNSET }
        val avgLoad = if (segmentLoads > 0) segmentLoadMsSum / segmentLoads else 0L
        LiveLog.i(
            "quality: pos=${player.currentPosition}ms buffered=${player.totalBufferedDuration}ms " +
                "liveOffset=${offset ?: "-"}ms(target=${target ?: "-"}) speed=${player.playbackParameters.speed} " +
                "watched=${stats.watchedMs() / 1000}s stalls=${stats.stalls}/${stats.stallMs}ms(${"%.1f".format(stats.stallPercent())}%) " +
                "dropped=${stats.droppedFrames}(${"%.1f".format(stats.droppedPercent())}%) underruns=${stats.audioUnderruns} " +
                "loadErr=${stats.loadErrors} segLoads=$segmentLoads(avg=${avgLoad}ms max=${segmentLoadMsMax}ms) bw=${bandwidthKbps}kbps",
        )
        stats.degradedReason()?.let { if (!reported) report(it) }
    }

    /** The channel was left or the player released: the session summary, and the report if it was a bad one. */
    fun finish() {
        stats.renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: stats.renderedFrames
        LiveLog.i(
            "session summary: watched=${stats.watchedMs() / 1000}s startup=${stats.startupMs}ms stalls=${stats.stalls} " +
                "(${stats.stallMs}ms, worst ${stats.worstStallMs}ms, ${"%.1f".format(stats.stallPercent())}%) " +
                "dropped=${stats.droppedFrames} underruns=${stats.audioUnderruns} loadErr=${stats.loadErrors} " +
                "vdec=$videoDecoder${if (softwareDecoder) "(SW)" else ""}",
        )
        stats.degradedReason()?.let { if (!reported) report(it) }
    }

    private fun report(reason: String) {
        reported = true
        LiveLog.w("session DEGRADED ($reason): reporting")
        Crash.report(
            LivePlaybackQuality("live playback degraded"),
            "live-quality",
            extras = mapOf(
                "reason" to reason,
                "channel" to LiveLog.channel,
                "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "watched_s" to (stats.watchedMs() / 1000).toString(),
                "startup_ms" to stats.startupMs.toString(),
                "stalls" to stats.stalls.toString(),
                "stall_ms" to stats.stallMs.toString(),
                "worst_stall_ms" to stats.worstStallMs.toString(),
                "stall_pct" to "%.1f".format(stats.stallPercent()),
                "dropped_frames" to stats.droppedFrames.toString(),
                "dropped_pct" to "%.1f".format(stats.droppedPercent()),
                "audio_underruns" to stats.audioUnderruns.toString(),
                "load_errors" to stats.loadErrors.toString(),
                "seg_loads" to segmentLoads.toString(),
                "seg_load_ms_max" to segmentLoadMsMax.toString(),
                "bw_kbps" to bandwidthKbps.toString(),
                "video_decoder" to videoDecoder,
                "software_decoder" to softwareDecoder.toString(),
                "video_format" to videoFormat,
                "audio_format" to audioFormat,
            ),
        )
    }

    private fun describe(f: Format): String {
        val kind = when {
            MimeTypes.isVideo(f.sampleMimeType) -> "${f.width}x${f.height}${if (f.frameRate > 0) " @${"%.0f".format(f.frameRate)}fps" else ""}"
            MimeTypes.isAudio(f.sampleMimeType) -> "${f.channelCount}ch ${f.sampleRate}Hz"
            else -> ""
        }
        return "${f.sampleMimeType} ${f.codecs ?: ""} $kind ${if (f.bitrate > 0) "${f.bitrate / 1000}kbps" else ""}".replace(Regex("\\s+"), " ").trim()
    }

    private fun dataType(type: Int): String = when (type) {
        C.DATA_TYPE_MEDIA -> "media"
        C.DATA_TYPE_MANIFEST -> "playlist"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
        else -> "type$type"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
        Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
        Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
        Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
        Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
        else -> "reason$reason"
    }

    private fun isSoftware(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.contains("ffmpeg") || n.endsWith(".sw")
    }

    private companion object {
        const val LONG_STALL_MS = 800L
        const val MANY_DROPPED = 5
        const val SLOW_LOAD_MS = 4_000L
        const val BIG_JUMP_MS = 2_000L
    }
}
