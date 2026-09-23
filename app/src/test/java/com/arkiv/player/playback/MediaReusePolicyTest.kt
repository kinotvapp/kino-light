package com.arkiv.player.playback

import com.arkiv.player.playback.MediaReusePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaReusePolicyTest {

    private val EP = "torrent:94c65c7ff4b0c5abdfce7659fcfd2b20b93805d9::0"

    /**
     * The TV's black-screen bug: reopening the SAME torrent episode after the previous session
     * closed. The old MediaItem is still in the controller with that episodeId, but its URL points
     * at the ephemeral port of the server `startStream()` already killed, and the new torrent is
     * serving on another port. Comparing only the mediaId said "it's the same, reuse it" and VLC
     * was left with the dead URL: it never opened anything.
     */
    @Test fun same_episode_with_a_new_url_reloads() {
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            loaded = listOf(LoadedMedia(EP, "http://127.0.0.1:41111/video")),
            currentMediaId = EP,
            fresh = listOf(LoadedMedia(EP, "http://127.0.0.1:46793/video")),
            requested = EP,
        )
        assertEquals(Decision.RELOAD, d)
    }

    /** The server is still alive on the same port (going back and re-entering without cutting the
     * stream): here it's worth reconnecting and keeping what's already buffered. */
    @Test fun same_episode_with_the_same_url_reuses() {
        val url = "http://127.0.0.1:46793/video"
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            loaded = listOf(LoadedMedia(EP, url)),
            currentMediaId = EP,
            fresh = listOf(LoadedMedia(EP, url)),
            requested = EP,
        )
        assertEquals(Decision.REUSE_CURRENT, d)
    }

    /** Navigating within an already-loaded archive series: same URLs, another chapter -> skip
     * within the playlist without reloading (that's what makes switching episodes instant). */
    @Test fun another_episode_of_the_same_playlist_skips() {
        val a = LoadedMedia("archive:serie::0", "https://archive.org/download/serie/e0.mp4")
        val b = LoadedMedia("archive:serie::1", "https://archive.org/download/serie/e1.mp4")
        val d = MediaReusePolicy.decide(
            episodeId = b.mediaId,
            loaded = listOf(a, b),
            currentMediaId = a.mediaId,
            fresh = listOf(a, b),
            requested = b.mediaId,
        )
        assertEquals(Decision.SKIP_IN_PLAYLIST, d)
    }

    /** Same playlist by ids but the target episode's URL changed (a torrent pack re-served on
     * another port): skipping would reuse the dead URL just like the bug above. */
    @Test fun another_episode_of_the_same_playlist_with_a_new_url_reloads() {
        val a = LoadedMedia("torrent:abc::0", "http://127.0.0.1:41111/video")
        val b = LoadedMedia("torrent:abc::1", "http://127.0.0.1:41111/video?f=1")
        val d = MediaReusePolicy.decide(
            episodeId = b.mediaId,
            loaded = listOf(a, b),
            currentMediaId = a.mediaId,
            fresh = listOf(
                a.copy(uri = "http://127.0.0.1:46793/video"),
                b.copy(uri = "http://127.0.0.1:46793/video?f=1"),
            ),
            requested = b.mediaId,
        )
        assertEquals(Decision.RELOAD, d)
    }

    /** Cold start: there's nothing in the controller. */
    @Test fun nothing_loaded_reloads() {
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            loaded = emptyList(),
            currentMediaId = null,
            fresh = listOf(LoadedMedia(EP, "http://127.0.0.1:46793/video")),
            requested = EP,
        )
        assertEquals(Decision.RELOAD, d)
    }

    /** Content different from what's loaded. */
    @Test fun an_episode_that_is_not_loaded_reloads() {
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            loaded = listOf(LoadedMedia("archive:otra::0", "https://archive.org/x.mp4")),
            currentMediaId = "archive:otra::0",
            fresh = listOf(LoadedMedia(EP, "http://127.0.0.1:46793/video")),
            requested = EP,
        )
        assertEquals(Decision.RELOAD, d)
    }

    /**
     * The chapter-carousel bug (Fire TV, 2026-08-12, Dragon Ball E130 → E131): picking the next
     * chapter played the one that was already sounding again, from the start.
     *
     * On navigating, the screen recomposes FROM SCRATCH (`loaded` goes back to false) but the
     * ViewModel survives -same back-stack entry- with the PREVIOUS chapter's playlist still
     * published. That old playlist arrived 4 ms after entering, well before the new one resolved
     * (~4 s on magis), and since its ids matched what was loaded it gave SKIP_IN_PLAYLIST: the
     * requested chapter's `indexOfFirst` gave -1, `coerceAtLeast(0)` sent it to index 0 and the
     * previous one played again. On top of that it left `loaded=true`, so when the good playlist
     * finally arrived the "already loaded" guard discarded it and there was no way out except
     * picking the chapter again (the second time: the ViewModel already had the right playlist).
     *
     * That's why the decision looks at [PlaylistData.pedido]: what arrived isn't mine yet -> wait.
     */
    @Test fun the_previous_chapter_s_playlist_waits() {
        val previous = LoadedMedia("magis:DB::e130", "https://cdn.example/e130.ts")
        val d = MediaReusePolicy.decide(
            episodeId = "magis:DB::e131",
            loaded = listOf(previous),
            currentMediaId = previous.mediaId,
            fresh = listOf(previous),
            requested = previous.mediaId,
        )
        assertEquals(Decision.WAIT, d)
    }

    /**
     * The playlist IS this chapter's but doesn't contain it: happens when the requested episode has
     * no playable variant and the section gets built without it. Not the case above -there's
     * nothing better to do than wait- so what arrived gets loaded, which is the usual behaviour.
     * Telling them apart is exactly what `requested` exists for: looking only at "is my episode in
     * the list?" would leave the screen waiting forever.
     */
    @Test fun own_playlist_that_does_not_contain_the_episode_reloads() {
        val other = LoadedMedia("archive:serie::0", "https://archive.org/download/serie/e0.mp4")
        val d = MediaReusePolicy.decide(
            episodeId = "archive:serie::7",
            loaded = emptyList(),
            currentMediaId = null,
            fresh = listOf(other),
            requested = "archive:serie::7",
        )
        assertEquals(Decision.RELOAD, d)
    }

    @Test
    fun coming_back_on_a_new_screen_reloads_even_if_it_is_the_same_media() {
        // Reusing the media with a NEW surface kills this device's HEVC decoder. Measured on the
        // Fire TV on 2026-08-13 over four logcat captures: with 0 reuses, 0 deaths (and 7 clean
        // reloads); with 4, 2 and 1 reuses, 6, 2 and 2 fatal errors respectively. The symptom is
        // `err 0x80001005` (OMX_ErrorBadParameter) + `DecoderErrorFatal = 1`, and 2 to 6 s of BLACK
        // screen with the audio running, with no spinner covering it.
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            loaded = listOf(LoadedMedia(EP, "http://127.0.0.1:8080/v")),
            currentMediaId = EP,
            fresh = listOf(LoadedMedia(EP, "http://127.0.0.1:8080/v")),
            requested = EP,
            newScreen = true,
        )
        assertEquals(Decision.RELOAD, d)
    }

    @Test
    fun on_the_same_screen_it_keeps_reusing() {
        // The reuse path isn't removed: with no new surface there's no decoder to die, and
        // reloading would throw the buffer away for nothing.
        val d = MediaReusePolicy.decide(
            episodeId = EP,
            loaded = listOf(LoadedMedia(EP, "http://127.0.0.1:8080/v")),
            currentMediaId = EP,
            fresh = listOf(LoadedMedia(EP, "http://127.0.0.1:8080/v")),
            requested = EP,
            newScreen = false,
        )
        assertEquals(Decision.REUSE_CURRENT, d)
    }
}
