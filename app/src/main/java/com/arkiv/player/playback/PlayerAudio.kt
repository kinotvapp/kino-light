package com.arkiv.player.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/** How Kino's video players declare their audio: movie media. Android uses it to route the sound and to decide who yields. */
internal val KINO_VIDEO_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * Makes the player ask Android for audio focus: another app's music pauses (or lowers) when a film starts, and the
 * film pauses for a call or an alarm and comes back after a short one. ExoPlayer does none of it by default, so a
 * player built without this plays right over whatever else is sounding.
 */
internal fun ExoPlayer.Builder.withAudioFocus(): ExoPlayer.Builder = setAudioAttributes(KINO_VIDEO_AUDIO, /* handleAudioFocus = */ true)
