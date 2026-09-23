package com.arkiv.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/**
 * The ExoPlayer that [PlaybackService] hosts: it plays files already downloaded to the device
 * (`file://…`, `SourceKind.LOCAL`) behind the service's MediaSession, so they keep playing in the
 * background with the media notification.
 *
 * Differences from the in-screen players (Magis, live, Caracol), all on purpose:
 * - [DefaultDataSource] instead of `DefaultHttpDataSource`: the latter cannot open `file://`.
 * - Audio focus, "becoming noisy" (headphones unplugged) and a local wake lock, because this one
 *   plays with the screen off.
 * - Decoder fallback, plus a software-first decoder order when the item asks for it
 *   (`PlayerSourceTag.preferSoftware`). That flag already crosses the controller→session IPC in the
 *   item extras, so the screen's decoder watchdog ([DecoderWatchdog]) can reload in software through
 *   its `MediaController` alone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object LocalExoPlayer {

    private const val TAG = "LocalExo"

    fun build(context: Context): ExoPlayer {
        val preference = SoftwarePreference()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(preference.codecSelector)
        val sources = preference.watch(DefaultMediaSourceFactory(DefaultDataSource.Factory(context)))
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(audio, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    /**
     * [decoders] with every software decoder first, each group in the platform's order (stable).
     * Generic so it can be tested without `MediaCodecInfo`, which only exists on a device.
     */
    fun <T> softwareFirst(decoders: List<T>, isHardware: (T) -> Boolean): List<T> =
        decoders.sortedBy { isHardware(it) }

    /** Whether the item's tag asks for a software decoder. */
    fun prefersSoftware(tag: Any?): Boolean = (tag as? PlayerSourceTag)?.preferSoftware == true

    /**
     * Carries the current item's decoder preference from where the item enters the player to where
     * its codec is chosen.
     *
     * The flag is written in `createMediaSource`, which ExoPlayer calls on the application thread
     * inside `setMediaItems`, BEFORE the sources reach the playback thread; the codec selector reads
     * it later on the playback thread, hence `@Volatile`. Local playlists carry a single item, so
     * "the last item created" is "the item playing".
     */
    private class SoftwarePreference {
        @Volatile
        private var preferSoftware = false

        val codecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            if (!preferSoftware) return@MediaCodecSelector decoders
            softwareFirst(decoders) { it.hardwareAccelerated }.also { ordered ->
                Log.w(TAG, "software-first decoders for $mimeType: ${ordered.joinToString { it.name }}")
            }
        }

        fun watch(delegate: MediaSource.Factory): MediaSource.Factory =
            object : MediaSource.Factory by delegate {
                override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                    preferSoftware = prefersSoftware(mediaItem.localConfiguration?.tag)
                    // Logged so the device check can tell the decoder rescue apart from the failure
                    // it is rescuing: this line says the item asked for software, and the selector's
                    // own line below says a software decoder was actually put first.
                    if (preferSoftware) Log.w(TAG, "item asks for a software decoder: ${mediaItem.mediaId}")
                    return delegate.createMediaSource(mediaItem)
                }
            }
    }
}
