package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [fallbackAudioTracks]: the decision behind "an unusable audio track must never take the video
 * down" (see [StreamExoPlayer]'s `onPlayerError`). A `MergingMediaSource` is all-or-nothing --
 * one bad audio URL fails the whole prepare -- so on any error while tracks are still merged in,
 * this decides what to retry with: drop the one track the failure names, or every track when it
 * can't be pinned on just one.
 */
class StreamExoPlayerTest {

    private val en = ResolvedAudioTrack(lang = "en", url = "https://x.example/a-en.aac")
    private val es = ResolvedAudioTrack(lang = "es-419", url = "https://x.example/a-es.aac")
    private val fr = ResolvedAudioTrack(lang = "fr", url = "https://x.example/a-fr.aac")

    @Test
    fun `an empty list is returned unchanged, nothing to drop`() {
        assertSame(emptyList<ResolvedAudioTrack>(), fallbackAudioTracks(emptyList(), "boom"))
    }

    @Test
    fun `the one track named in the failure is dropped, the rest survive`() {
        val failure = "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException: Response code: 404, uri=https://x.example/a-es.aac"
        assertEquals(listOf(en, fr), fallbackAudioTracks(listOf(en, es, fr), failure))
    }

    @Test
    fun `a failure that names none of the tracks drops all of them`() {
        assertEquals(emptyList<ResolvedAudioTrack>(), fallbackAudioTracks(listOf(en, es, fr), "java.net.SocketTimeoutException: timeout"))
    }

    @Test
    fun `a failure that happens to name more than one track is ambiguous and drops all of them`() {
        val failure = "tried https://x.example/a-en.aac and https://x.example/a-es.aac"
        assertEquals(emptyList<ResolvedAudioTrack>(), fallbackAudioTracks(listOf(en, es, fr), failure))
    }

    @Test
    fun `with a single audio track, naming it drops down to a plain video`() {
        val failure = "404 at https://x.example/a-en.aac"
        assertEquals(emptyList<ResolvedAudioTrack>(), fallbackAudioTracks(listOf(en), failure))
    }
}
