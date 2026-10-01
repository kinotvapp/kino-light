package com.arkiv.player.cast

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

/**
 * Tells the receiver how long the title actually is.
 *
 * media3's [DefaultMediaItemConverter] never sets a stream duration -- it builds the `MediaInfo`
 * with `STREAM_TYPE_BUFFERED` and leaves the length for the receiver to work out from the media.
 * For a normal file that is fine. For a fragmented MP4 that is still being WRITTEN it is not: the
 * receiver reads the fragments that exist and concludes the title is five seconds long. Measured
 * 2026-09-12 -- `dur=5166ms` while the position was already past 24 s -- and it costs twice over.
 * The progress bar lies and there is nothing to seek along, and every few seconds playback reaches
 * that imaginary end, stalls into buffering, gets handed more data and resumes. That was the
 * "loading" appearing every six seconds, with a remux running 2.5 MB/s ahead of playback and not a
 * byte missing.
 *
 * The duration travels in the MediaItem's request metadata because that is the one bag that
 * survives being turned into a `MediaQueueItem`: [CastRequest] carries it, `CastSessionManager`
 * puts it there, and this reads it back out.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DurationAwareMediaItemConverter : MediaItemConverter {

    private val base = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val item = base.toMediaQueueItem(mediaItem)
        val extras = mediaItem.requestMetadata.extras
        val shape = CastStreamShape.of(
            durationMs = extras?.getLong(KEY_DURATION_MS, C.TIME_UNSET) ?: C.TIME_UNSET,
            isLive = extras?.getBoolean(KEY_LIVE, false) ?: false,
            hlsFmp4 = extras?.getBoolean(KEY_HLS_FMP4, false) ?: false,
        )
        // Sidecar subtitles (see CastSubtitles): their MediaTracks, and the one to start with.
        val textTracks = CastTextMedia.tracks(extras)
        if (shape == null && textTracks == null) return item

        val info = item.media ?: return item
        val withDuration = MediaInfo.Builder(info.contentId)
            // LIVE for a live channel: it has no end, and calling it "buffered" makes the receiver
            // invent one and stall against it. The duration goes to -1, which is what the API asks
            // for on a live stream.
            .setStreamType(if (shape?.live == true) MediaInfo.STREAM_TYPE_LIVE else info.streamType)
            .setContentType(info.contentType)
            .setContentUrl(info.contentUrl ?: info.contentId)
            .setMetadata(info.metadata)
            .setStreamDuration(shape?.streamDurationMs ?: info.streamDuration)
            .setCustomData(info.customData)
            .apply {
                // fMP4 segments: without this the receiver's HLS player takes them for MPEG-TS.
                if (shape?.fmp4Segments == true) {
                    setHlsSegmentFormat(HlsSegmentFormat.FMP4)
                    setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
                }
                if (textTracks != null) {
                    setMediaTracks(textTracks)
                    setTextTrackStyle(CastTextMedia.style())
                }
            }
            .build()
        if (shape != null) {
            android.util.Log.i(
                TAG,
                if (shape.live) "announcing it as LIVE (no end to chase)" else
                    "telling the receiver the title runs ${shape.streamDurationMs}ms" + if (shape.fmp4Segments) " · HLS of fMP4 segments" else "",
            )
        }
        if (textTracks != null) android.util.Log.i(TAG, "with ${textTracks.size} sidecar subtitle track(s)")
        return MediaQueueItem.Builder(withDuration)
            // Chain straight into the next item. Without these the Default Media Receiver puts its
            // own interstitial between queue entries -- "Your video will play in N" -- which on a
            // title cut into thirty-second chunks means a countdown twice a minute.
            .setAutoplay(true)
            .setPreloadTime(PRELOAD_SECONDS)
            .apply { CastTextMedia.active(extras)?.let { setActiveTrackIds(it) } }
            .build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        base.toMediaItem(mediaQueueItem)

    companion object {
        /** Key under which the duration rides in `MediaItem.requestMetadata.extras`. */
        const val KEY_DURATION_MS = "arkiv.durationMs"

        /** Key under which "treat this as live" rides in `MediaItem.requestMetadata.extras`. */
        const val KEY_LIVE = "arkiv.comoEnVivo"

        /** Key under which "an HLS playlist of fMP4 segments" rides in the same extras. */
        const val KEY_HLS_FMP4 = "arkiv.hlsFmp4"

        /**
         * Seconds before an item ends that the receiver should start fetching the next one. It is
         * what lets one queue entry run into the next without the receiver stopping to announce
         * it.
         */
        private const val PRELOAD_SECONDS = 10.0

        private const val TAG = "ArkivCast"
    }
}

/**
 * What the `MediaInfo` says about the stream, decided apart from the Cast SDK's builders so it can
 * be tested on the JVM. Null = nothing to add, media3's default `MediaInfo` goes out unchanged.
 */
internal data class CastStreamShape(val live: Boolean, val streamDurationMs: Long, val fmp4Segments: Boolean) {
    companion object {
        fun of(durationMs: Long, isLive: Boolean, hlsFmp4: Boolean): CastStreamShape? {
            if (durationMs <= 0L && !isLive && !hlsFmp4) return null
            return CastStreamShape(
                live = isLive,
                streamDurationMs = if (isLive || durationMs <= 0L) -1L else durationMs,
                fmp4Segments = hlsFmp4 && !isLive,
            )
        }
    }
}
