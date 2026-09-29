package com.arkiv.player.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.arkiv.player.ui.player.STREAM_TS_SEARCH_BYTES

/**
 * Debug-only: plays one URL full screen with the plugin player's own ExoPlayer setup (decoder
 * fallback, the same extractor settings, a TextureView) and logs what the video decoder is actually
 * given: the track's Format (mime, codecs, size, initializationData sizes), the decoder chosen, the
 * first frame, and every 2 s the rendered/dropped frame counters. Made to chase Castle's "audio but
 * no picture" on the KALLEY R3 without the plugin in the way.
 *
 * ```
 * adb shell am start -n com.arkiv.player.light/com.arkiv.player.debug.StreamProbeActivity \
 *     --es url castle.m3u8 [--ez soft true] [--es mime application/x-mpegURL]
 * adb logcat -s KinoStreamProbe
 * ```
 * A `url` without a scheme is a file in the app's files dir, put there with
 * `adb push x /data/local/tmp/ && adb shell run-as com.arkiv.player.light cp /data/local/tmp/x files/`.
 * `soft` puts software decoders first. `--es rescue prepare|rehook [--ei rescueEvery 15000]` replays
 * StreamExoPlayer's frozen-video rescues on a playback that paints, every `rescueEvery` ms. Lives in `src/debug`: never in a release APK.
 */
@OptIn(UnstableApi::class)
class StreamProbeActivity : Activity() {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A bare name is a file in the app's files dir (pushed there with run-as).
        val url = intent.getStringExtra("url")?.let { if ("://" in it) it else "file://$filesDir/$it" } ?: return finish()
        val soft = intent.getBooleanExtra("soft", false)
        val mime = intent.getStringExtra("mime")
        val texture = TextureView(this)
        setContentView(texture)

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector { m, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(m, secure, tunneling)
            val ordered = if (soft) all.sortedBy { it.hardwareAccelerated } else all
            Log.i(TAG, "decoders for $m: ${ordered.joinToString { "${it.name}(hw=${it.hardwareAccelerated})" }}")
            ordered
        }
        val sources = DefaultMediaSourceFactory(
            // file:// too: a playlist pushed into the app's files dir with run-as.
            DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setUserAgent("okhttp/4.12.0")),
            DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(STREAM_TS_SEARCH_BYTES),
        )
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sources).build()
        player = p
        p.setVideoTextureView(texture)
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.forEach { g ->
                    for (i in 0 until g.length) Log.i(TAG, "track type=${g.type} selected=${g.isTrackSelected(i)} ${describe(g.getTrackFormat(i))}")
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) = Log.i(TAG, "video size ${videoSize.width}x${videoSize.height}").let { }
            override fun onRenderedFirstFrame() = Log.i(TAG, "FIRST FRAME").let { }
            override fun onPlaybackStateChanged(state: Int) = Log.i(TAG, "state=$state").let { }
            override fun onPlayerError(error: PlaybackException) = Log.w(TAG, "error ${error.errorCodeName}", error).let { }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
                Log.i(TAG, "video decoder $name").let { }
            override fun onVideoInputFormatChanged(e: AnalyticsListener.EventTime, format: Format, eval: DecoderReuseEvaluation?) =
                Log.i(TAG, "video input format ${describe(format)}").let { }
            override fun onAudioDecoderInitialized(e: AnalyticsListener.EventTime, name: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
                Log.i(TAG, "audio decoder $name").let { }
        })
        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply { mime?.let { setMimeType(it) } }.build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        // Replays StreamExoPlayer's frozen-video rescues on a playback that IS painting, to see
        // whether a rescue itself can stop the picture: "prepare" (rescue 1: seek + prepare) or
        // "rehook" (rescue 2+: re-set the TextureView, then seek + prepare), every `rescueEvery` ms.
        val rescue = intent.getStringExtra("rescue")
        val rescueEvery = intent.getIntExtra("rescueEvery", 15_000).toLong()
        if (rescue != null) handler.postDelayed(object : Runnable {
            override fun run() {
                val pl = player ?: return
                val pos = pl.currentPosition
                Log.w(TAG, "RESCUE $rescue at $pos · rendered=${pl.videoDecoderCounters?.renderedOutputBufferCount}")
                if (rescue == "rehook") {
                    pl.clearVideoTextureView(texture)
                    pl.setVideoTextureView(texture)
                }
                pl.seekTo(pos)
                pl.prepare()
                pl.playWhenReady = true
                handler.postDelayed(this, rescueEvery)
            }
        }, rescueEvery)
        handler.post(object : Runnable {
            override fun run() {
                val pl = player ?: return
                val c = pl.videoDecoderCounters
                Log.i(TAG, "pos=${pl.currentPosition} rendered=${c?.renderedOutputBufferCount} dropped=${c?.droppedBufferCount} skipped=${c?.skippedOutputBufferCount} queued=${c?.queuedInputBufferCount}")
                handler.postDelayed(this, 2_000)
            }
        })
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun describe(f: Format) =
        "id=${f.id} mime=${f.sampleMimeType} container=${f.containerMimeType} codecs=${f.codecs} ${f.width}x${f.height} " +
            "lang=${f.language} init=${f.initializationData.map { it.size }} colorInfo=${f.colorInfo} rot=${f.rotationDegrees}" +
            (if (f.frameRate != Format.NO_VALUE.toFloat()) " fps=${f.frameRate}" else "") +
            (if (f.sampleRate != Format.NO_VALUE) " rate=${f.sampleRate} ch=${f.channelCount}" else "") +
            (if (f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) " default" else "")

    private companion object {
        const val TAG = "KinoStreamProbe"
    }
}
