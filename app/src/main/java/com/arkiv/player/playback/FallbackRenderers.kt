package com.arkiv.player.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * ExoPlayer's renderers with decoder fallback ON: when the first decoder for a stream fails to
 * initialize, try the next one (typically a software decoder) instead of failing the playback.
 *
 * The default is OFF, and three of the app's four players (Magis VOD, live, Caracol) were built with the
 * default. It shows up in the field as `Decoder init failed: OMX.Exynos.hevc.dec` on a 4K HEVC file
 * (a hardware decoder that can't take that profile/size) -> ERROR_CODE_DECODER_INIT_FAILED and a dead
 * screen, when a software decoder on the same device would have played it. `LocalExoPlayer` already did this.
 *
 * It only changes what happens AFTER an initialization failure, so a device whose decoder works behaves
 * exactly as before. It does NOT help a decoder that dies mid-playback (`CodecException` from
 * `dequeueInputBuffer`): that's a different failure.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun fallbackRenderers(context: Context): DefaultRenderersFactory =
    DefaultRenderersFactory(context).setEnableDecoderFallback(true)
