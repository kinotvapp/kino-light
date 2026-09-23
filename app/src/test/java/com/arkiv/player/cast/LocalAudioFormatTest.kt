package com.arkiv.player.cast

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast audio gate reads the audio the LOCAL player is using. With the local player an
 * ExoPlayer, that comes from its `Tracks`; these tests pin which track is read and, together with
 * [CastAudioSupport], that its `Format` fields (`sampleMimeType`, `channelCount`) drive the same
 * decisions the old libVLC-fourcc gate made.
 */
class LocalAudioFormatTest {

    private fun audio(mime: String, channels: Int, id: String) =
        Format.Builder().setId(id).setSampleMimeType(mime).setChannelCount(channels).build()

    private fun group(format: Format, selected: Boolean) =
        Tracks.Group(TrackGroup(format.id!!, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))

    private val video = Tracks.Group(
        TrackGroup("v", Format.Builder().setId("v").setSampleMimeType(MimeTypes.VIDEO_H265).build()),
        false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
    )

    @Test fun `the selected audio track is the one read`() {
        val tracks = Tracks(
            listOf(
                video,
                group(audio(MimeTypes.AUDIO_AAC, 2, "a1"), selected = false),
                group(audio(MimeTypes.AUDIO_AC3, 6, "a2"), selected = true),
            ),
        )
        val format = localAudioFormat(tracks)!!
        assertEquals(MimeTypes.AUDIO_AC3, format.sampleMimeType)
        assertEquals(6, format.channelCount)
    }

    @Test fun `with none selected the first audio track is read`() {
        val tracks = Tracks(
            listOf(
                group(audio(MimeTypes.AUDIO_AAC, 2, "a1"), selected = false),
                group(audio(MimeTypes.AUDIO_DTS, 6, "a2"), selected = false),
            ),
        )
        val format = localAudioFormat(tracks)!!
        assertEquals(MimeTypes.AUDIO_AAC, format.sampleMimeType)
    }

    @Test fun `no audio track means nothing to decide`() {
        assertNull(localAudioFormat(Tracks(listOf(video))))
        assertNull(localAudioFormat(Tracks.EMPTY))
    }

    /** The downloads: HEVC + stereo AAC. They must keep casting directly. */
    @Test fun `stereo AAC goes direct and AC-3, E-AC-3 and DTS do not`() {
        fun decodes(mime: String, ch: Int) = CastAudioSupport.receiverDecodes(mime, ch)
        assertTrue(decodes(MimeTypes.AUDIO_AAC, 2))
        assertFalse(decodes(MimeTypes.AUDIO_AAC, 6))
        assertFalse(decodes(MimeTypes.AUDIO_AC3, 6))
        assertFalse(decodes(MimeTypes.AUDIO_E_AC3, 6))
        assertFalse(decodes(MimeTypes.AUDIO_DTS, 6))
        assertFalse(decodes(MimeTypes.AUDIO_TRUEHD, 8))
        assertTrue(decodes(MimeTypes.AUDIO_MPEG, 2))
        assertTrue(decodes(MimeTypes.AUDIO_OPUS, 2))
        assertTrue(decodes(MimeTypes.AUDIO_FLAC, 2))
    }

    /** A codec the gate has never heard of is treated as "may play muted" (whitelist). */
    @Test fun `an unknown mime is not waved through, a missing mime is`() {
        assertFalse(CastAudioSupport.receiverDecodes("audio/x-something-new", 2))
        // No mime at all means the track isn't parsed yet: the gate's "don't know → direct" stays.
        assertTrue(CastAudioSupport.receiverDecodes(null, 2))
    }
}
