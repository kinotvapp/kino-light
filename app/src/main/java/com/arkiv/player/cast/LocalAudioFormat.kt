package com.arkiv.player.cast

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/**
 * The `Format` of the audio the LOCAL player is using, for the cast audio gate
 * ([CastAudioSupport.receiverDecodes]).
 *
 * The local player is the ExoPlayer hosted by `PlaybackService`, so this reads the `Tracks` its
 * `MediaController` reports: the selected audio track, or the first one when none is selected yet.
 * The gate reads `sampleMimeType`/`channelCount` straight off the returned `Format` -- no libVLC
 * type involved.
 */
internal fun localAudioFormat(tracks: Tracks): Format? {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.isEmpty()) return null
    val index = audio.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: 0
    val group = audio[index]
    val track = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
    return group.getTrackFormat(track)
}

/**
 * The `Format` of the video the LOCAL player is using.
 *
 * Exists to read `pixelWidthHeightRatio`, which is one of exactly two things that can force
 * media3's Transformer to RE-ENCODE instead of copying samples across containers
 * (`TransformerUtil.shouldTranscodeVideo`: a ratio other than 1 returns true unconditionally).
 * Knowing it tells a remux that costs minutes and loses quality from one that is a file copy.
 */
internal fun localVideoFormat(tracks: Tracks): Format? {
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (video.isEmpty()) return null
    val index = video.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: 0
    val group = video[index]
    val track = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
    return group.getTrackFormat(track)
}
