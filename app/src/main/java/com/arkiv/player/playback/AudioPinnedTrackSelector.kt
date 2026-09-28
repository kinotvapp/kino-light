package com.arkiv.player.playback

import android.content.Context
import android.util.Log
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import com.arkiv.player.cast.AudioTrackRef
import com.arkiv.player.cast.CastAudio
import com.arkiv.player.cast.CastAudioChoice

/**
 * The track selector a remux runs with when the cast must carry a SPECIFIC audio track.
 *
 * Transformer writes one audio track and lets its own `DefaultTrackSelector` choose it, which
 * knows nothing about the phone's audio menu -- so the TV got whichever it liked (see
 * [CastAudioChoice]). This one finds the phone's choice among the input's audio groups
 * ([CastAudio.indexIn]) and selects that; when it cannot find it, or the renderer cannot take it,
 * it falls back to the default choice rather than failing the remux.
 *
 * Its parameters are the ones Transformer's own default selector uses (highest bitrate, no channel
 * count constraint), so video selection is exactly what it was.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioPinnedTrackSelector(
    context: Context,
    private val choice: CastAudioChoice,
) : DefaultTrackSelector(
    context,
    DefaultTrackSelector.Parameters.Builder()
        .setForceHighestSupportedBitrate(true)
        .setConstrainAudioChannelCountToDeviceCapabilities(false)
        .build(),
) {
    override fun selectAudioTrack(
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            val refs = (0 until groups.length).map { g ->
                val f = groups[g].getFormat(0)
                AudioTrackRef(f.id, f.language, f.label)
            }
            val index = CastAudio.indexIn(refs, choice) ?: continue
            val support = RendererCapabilities.getFormatSupport(rendererFormatSupports[renderer][index][0])
            if (support != C.FORMAT_HANDLED) {
                Log.w(TAG, "audio ${refs[index]} is not handled by the remux (support=$support), default audio instead")
                continue
            }
            Log.w(TAG, "remux audio pinned to #$index ${refs[index]} (phone chose #${choice.ordinal} ${choice.id}/${choice.language})")
            return Pair(ExoTrackSelection.Definition(groups[index], 0), renderer)
        }
        Log.w(TAG, "phone's audio #${choice.ordinal} (${choice.id}/${choice.language}) not found, default audio instead")
        return super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
    }

    private companion object { const val TAG = "ArkivRemux" }
}
