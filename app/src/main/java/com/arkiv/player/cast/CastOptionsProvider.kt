package com.arkiv.player.cast

import android.content.Context
import com.arkiv.player.BuildConfig
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Which receiver runs on the TV.
 *
 * Kino's own when `CAST_RECEIVER_ID` is set, and Google's Default Media Receiver otherwise. The
 * default one cannot play what Magis serves: measured on a KALLEY R3 (2026-09-12) it answered a
 * bare MPEG-TS with `FFmpegDemuxer: open context failed`, and fed the same bytes as HLS segments it
 * juddered through hundreds of `Failed to get frame timestamps` a minute while its decoder was
 * perfectly healthy. Its HLS support is the old Media Player Library.
 *
 * Kino's receiver is the same page with `useShakaForHls` turned on, so transport-stream segments
 * are transmuxed to fMP4 on the TV -- where the bytes already are -- instead of on the phone at a
 * cost of minutes, gigabytes of cache and battery. See `receiver/index.html`.
 *
 * The fallback is deliberate rather than a hard requirement: a checkout without an id still builds
 * and still casts everything the default receiver can handle (mp4), instead of failing to compile
 * or silently casting nothing.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val ownReceiverId = BuildConfig.CAST_RECEIVER_ID.trim()
        val id = ownReceiverId.ifEmpty { CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID }
        android.util.Log.w(
            "ArkivCast",
            "receiver app id = $id" + if (ownReceiverId.isEmpty()) " (Google's default: MPEG-TS will judder)" else " (Kino's own)",
        )
        return CastOptions.Builder()
            .setReceiverApplicationId(id)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
