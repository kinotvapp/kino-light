package com.arkiv.player.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.arkiv.player.crash.AvSyncSuspect
import com.arkiv.player.crash.Crash
import com.arkiv.player.data.net.DohDns
import java.util.Locale

/**
 * Watches a FILM's playback (not live TV) and, when something that comes with out-of-sync audio shows up, sends ONE
 * [AvSyncSuspect] report with the context needed to tell a Bluetooth/HDMI output from a bad external audio track from
 * a decoder problem. Nobody can measure a viewer's "the audio is late", so this cannot say it happened; it says what
 * was going on when it likely did.
 *
 * It never floods: nothing is sent unless [VodSyncStats.suspectReason] finds something, and a device sends at most one
 * report a day ([mayReport]). A normal session costs only a one-line log summary. Counting is in [VodSyncStats]; this
 * class only listens and formats. Call [finish] BEFORE the player is released.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VodSyncMonitor(
    private val player: ExoPlayer,
    private val context: Context,
    /** `magis` or `plugin`: whose stream this is. */
    private val sourceTag: String,
    /** How many external audio tracks are merged in right now: it shrinks when a failing one is dropped. */
    private val externalAudioTracks: () -> Int,
    private val mayReport: () -> Boolean,
    private val onReported: () -> Unit,
) : AnalyticsListener {

    private val stats = VodSyncStats(SystemClock::elapsedRealtime)
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var videoFormat = ""
    private var audioFormat = ""
    private var reported = false

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        stats.quality.onState(state)
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        stats.quality.onDroppedFrames(droppedFrames)
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        stats.quality.onAudioUnderrun()
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        stats.onAudioSinkError()
        Log.w(TAG, "audio sink error: ${audioSinkError.javaClass.simpleName}: ${audioSinkError.message}")
    }

    override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
        stats.onFrameProcessingOffset(totalProcessingOffsetUs, frameCount)
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_INTERNAL) stats.onUnexpectedDiscontinuity()
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        val selected = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return
        val format = (0 until selected.length).firstOrNull { selected.isTrackSelected(it) }?.let { selected.getTrackFormat(it) } ?: return
        stats.onAudioSelected("${format.id}|${format.language}|${format.sampleMimeType}")
        audioFormat = describe(format)
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        videoFormat = describe(format)
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        audioDecoder = decoderName
    }

    /** The film was left or the player is about to be released: a one-line summary, and the report if it earned one. */
    fun finish() {
        stats.externalAudioTracks = externalAudioTracks()
        stats.quality.renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: stats.quality.renderedFrames
        val reason = stats.suspectReason()
        Log.i(
            TAG,
            "session summary: watched=${stats.quality.watchedMs() / 1000}s stalls=${stats.quality.stalls} " +
                "underruns=${stats.quality.audioUnderruns} sinkErrors=${stats.audioSinkErrors} audioSwitches=${stats.audioSwitches} " +
                "jumps=${stats.unexpectedDiscontinuities} dropped=${stats.quality.droppedFrames} suspect=${reason ?: "no"}",
        )
        if (reason == null || reported) return
        if (!mayReport()) {
            Log.i(TAG, "sync suspect ($reason) but this device reported within the last day: not reporting again")
            return
        }
        reported = true
        onReported()
        Log.w(TAG, "sync suspect ($reason): reporting")
        val outputs = outputTypes()
        Crash.report(
            AvSyncSuspect("av sync suspect · ${reason.substringBefore(':')}"),
            "av-sync-suspect",
            extras = mapOf(
                "reason" to reason,
                "source" to sourceTag,
                "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "dns_mode" to DohDns.mode.key,
                "outputs_available" to AudioRoute.describe(outputs),
                "bluetooth_connected" to AudioRoute.hasBluetooth(outputs).toString(),
                "audio_format" to audioFormat,
                "audio_decoder" to audioDecoder,
                "video_format" to videoFormat,
                "video_decoder" to videoDecoder,
                "external_audio_tracks" to stats.externalAudioTracks.toString(),
                "audio_switches" to stats.audioSwitches.toString(),
                "audio_underruns" to stats.quality.audioUnderruns.toString(),
                "audio_sink_errors" to stats.audioSinkErrors.toString(),
                "position_jumps" to stats.unexpectedDiscontinuities.toString(),
                "dropped_frames" to stats.quality.droppedFrames.toString(),
                "dropped_pct" to String.format(Locale.ROOT, "%.1f", stats.quality.droppedPercent()),
                "frame_offset_ms" to (stats.avgFrameOffsetMs()?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""),
                "stalls" to stats.quality.stalls.toString(),
                "stall_ms" to stats.quality.stallMs.toString(),
                "watched_s" to (stats.quality.watchedMs() / 1000).toString(),
                "position_s" to (player.currentPosition / 1000).toString(),
            ),
        )
    }

    private fun outputTypes(): List<Int> = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }.getOrDefault(emptyList())

    private fun describe(f: Format): String {
        val kind = when {
            MimeTypes.isVideo(f.sampleMimeType) -> "${f.width}x${f.height}"
            MimeTypes.isAudio(f.sampleMimeType) -> "${f.channelCount}ch ${f.sampleRate}Hz ${f.language ?: ""}"
            else -> ""
        }
        return "${f.sampleMimeType} ${f.codecs ?: ""} $kind".replace(Regex("\\s+"), " ").trim()
    }

    private companion object { const val TAG = "ArkivSync" }
}
