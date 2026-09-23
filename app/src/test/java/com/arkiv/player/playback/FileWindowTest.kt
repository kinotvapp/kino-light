package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resuming a magis TS by opening a window into the file instead of seeking. The why is in
 * [FileWindow]; here the arithmetic gets pinned, which is where a bug turns invisible: the video
 * plays fine either way, just starting somewhere else.
 */
class FileWindowTest {

    private val TOTAL = 1_118_023_968L // the real film the bug was reproduced with

    @Test fun start_lands_on_a_packet_boundary() {
        val i = FileWindow.start(TOTAL, 0.5f)
        assertEquals(0L, i % 188)
    }

    @Test fun start_is_proportional_to_the_fraction() {
        val i = FileWindow.start(TOTAL, 0.25f)
        // At most one packet below the ideal (it aligns downward).
        val ideal = (TOTAL * 0.25).toLong()
        assertEquals(true, i <= ideal && ideal - i < 188)
    }

    @Test fun with_no_fraction_there_is_no_window() {
        assertEquals(0L, FileWindow.start(TOTAL, 0f))
        assertEquals(0L, FileWindow.start(TOTAL, -1f))
    }

    @Test fun the_window_is_never_left_empty() {
        // Requesting the entire end would leave a 0-byte virtual file, which VLC reads as a failure.
        val i = FileWindow.start(TOTAL, 1f)
        assertEquals(true, FileWindow.visibleSize(TOTAL, i) >= 188)
    }

    @Test fun a_tiny_file_does_not_get_windowed() {
        assertEquals(0L, FileWindow.start(100L, 0.5f))
    }

    @Test fun the_visible_size_is_whatever_is_left() {
        assertEquals(TOTAL - 1880L, FileWindow.visibleSize(TOTAL, 1880L))
    }

    // ─── range translation ──────────────────────────────────────────────

    @Test fun the_player_s_open_range_starts_at_the_offset() {
        // VLC opens by requesting "bytes=0-": for it the file starts at 0.
        assertEquals("bytes=1880-", FileWindow.rangeToOrigin(ByteRange(0L, null), 1880L))
    }

    @Test fun with_no_range_requested_the_whole_window_is_requested() {
        assertEquals("bytes=1880-", FileWindow.rangeToOrigin(null, 1880L))
    }

    @Test fun a_closed_range_is_shifted_on_both_ends() {
        assertEquals("bytes=2880-3880", FileWindow.rangeToOrigin(ByteRange(1000L, 2000L), 1880L))
    }

    @Test fun the_content_range_goes_back_to_the_player_s_coordinates() {
        val visible = FileWindow.visibleContentRange("bytes 1880-1118023967/1118023968", 1880L)
        assertEquals("bytes 0-1118022087/1118022088", visible)
    }

    @Test fun an_unreadable_content_range_is_not_made_up() {
        assertNull(FileWindow.visibleContentRange(null, 1880L))
        assertNull(FileWindow.visibleContentRange("bytes */1118023968", 1880L))
    }

    @Test fun a_content_range_before_the_offset_is_discarded() {
        // The origin ignored the Range and answered from the start: translating it would go negative.
        assertNull(FileWindow.visibleContentRange("bytes 0-99/1118023968", 1880L))
    }

    // ─── when to open a window instead of seeking ─────────────────────────────

    @Test fun the_total_comes_from_the_content_range() {
        assertEquals(TOTAL, FileWindow.totalFromContentRange("bytes 0-4095/1118023968"))
        assertEquals(0L, FileWindow.totalFromContentRange("cualquier cosa"))
    }
}
