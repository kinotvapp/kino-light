package com.arkiv.player.cast

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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
        val durationMs = extras?.getLong(KEY_DURATION_MS, C.TIME_UNSET) ?: C.TIME_UNSET
        val isLive = extras?.getBoolean(KEY_LIVE, false) ?: false
        if (durationMs <= 0L && !isLive) return item

        val info = item.media ?: return item
        val withDuration = MediaInfo.Builder(info.contentId)
            // LIVE for a file still being written: it has no end yet, and calling it "buffered"
            // makes the receiver invent one and stall against it. The duration goes to -1, which
            // is what the API asks for on a live stream.
            .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else info.streamType)
            .setContentType(info.contentType)
            .setContentUrl(info.contentUrl ?: info.contentId)
            .setMetadata(info.metadata)
            .setStreamDuration(if (isLive) -1L else durationMs)
            .setCustomData(info.customData)
            .build()
        android.util.Log.i(
            TAG,
            if (isLive) "announcing it as LIVE (no end to chase)" else "telling the receiver the title runs ${durationMs}ms",
        )
        return MediaQueueItem.Builder(withDuration)
            // Chain straight into the next item. Without these the Default Media Receiver puts its
            // own interstitial between queue entries -- "Your video will play in N" -- which on a
            // title cut into thirty-second chunks means a countdown twice a minute.
            .setAutoplay(true)
            .setPreloadTime(PRELOAD_SECONDS)
            .build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        base.toMediaItem(mediaQueueItem)

    companion object {
        /** Key under which the duration rides in `MediaItem.requestMetadata.extras`. */
        const val KEY_DURATION_MS = "arkiv.durationMs"

        /** Key under which "treat this as live" rides in `MediaItem.requestMetadata.extras`. */
        const val KEY_LIVE = "arkiv.comoEnVivo"

        /**
         * Seconds before an item ends that the receiver should start fetching the next one. It is
         * what lets one queue entry run into the next without the receiver stopping to announce
         * it.
         */
        private const val PRELOAD_SECONDS = 10.0

        private const val TAG = "ArkivCast"
    }
}
