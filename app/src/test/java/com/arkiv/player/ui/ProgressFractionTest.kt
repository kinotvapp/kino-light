package com.arkiv.player.ui

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The red part of every progress bar. The player's seek bar used to divide by a stand-in duration
 * of 1 when it had none, so a TS whose duration the extractor could not work out opened FULL.
 */
class ProgressFractionTest {

    @Test
    fun `an unknown duration is an empty bar, never a full one`() {
        assertEquals(0f, ProgressFraction.of(1_140_000L, 0L), 0f)
        assertEquals(0f, ProgressFraction.of(1_140_000L, -1L), 0f)
        assertEquals(0f, ProgressFraction.of(1_140_000L, C.TIME_UNSET), 0f)
        assertEquals(0f, ProgressFraction.of(1_140_000f, 0L), 0f)
    }

    @Test
    fun `a known duration gives position over duration`() {
        assertEquals(0.25f, ProgressFraction.of(1_917_000L, 7_668_000L), 1e-4f)
    }

    @Test
    fun `a position past the end is clamped to full, a negative one to empty`() {
        assertEquals(1f, ProgressFraction.of(9_000_000L, 7_668_000L), 0f)
        assertEquals(0f, ProgressFraction.of(-5_000L, 7_668_000L), 0f)
        assertEquals(0f, ProgressFraction.of(Float.NaN, 7_668_000L), 0f)
    }

    @Test
    fun `the slider thumb stays at the start while the duration is unknown`() {
        // Range is 0..1 without a duration: a position in ms would clamp the thumb to the END.
        assertEquals(0f, ProgressFraction.sliderValue(1_140_000f, 0L), 0f)
        assertEquals(0f, ProgressFraction.sliderValue(1_140_000f, C.TIME_UNSET), 0f)
    }

    @Test
    fun `the slider value is the position, kept inside the range`() {
        assertEquals(1_140_000f, ProgressFraction.sliderValue(1_140_000f, 7_668_000L), 0f)
        assertEquals(7_668_000f, ProgressFraction.sliderValue(9_000_000f, 7_668_000L), 0f)
        assertEquals(0f, ProgressFraction.sliderValue(-1f, 7_668_000L), 0f)
    }

    @Test
    fun `uri kind never carries the host, path or query`() {
        assertEquals("file .ts", ProgressDiagnostics.uriKind("file:///storage/emulated/0/Android/data/x/files/Movies/Deadpool.ts"))
        assertEquals("http(loopback)", ProgressDiagnostics.uriKind("http://127.0.0.1:41234/p?u=https%3A%2F%2Fcdn.example%2Fa.ts&h=abc"))
        assertEquals("https .m3u8", ProgressDiagnostics.uriKind("https://cdn.example.com/hls/master.m3u8?token=secret"))
        assertEquals("none", ProgressDiagnostics.uriKind(null))
        val kind = ProgressDiagnostics.uriKind("https://cdn.example.com/a/b.mp4?sig=SECRET")
        assertFalse(kind.contains("cdn") || kind.contains("SECRET"))
    }

    @Test
    fun `the save log speaks on the first save, a changed duration and then once a minute`() {
        val log = ProgressSaveLog(everyMs = 60_000L)
        assertTrue(log.shouldLog("magis::1", 7_668_000L, nowMs = 0L))
        assertFalse(log.shouldLog("magis::1", 7_668_000L, nowMs = 5_000L))
        assertTrue(log.shouldLog("magis::1", 7_700_000L, nowMs = 10_000L))
        assertFalse(log.shouldLog("magis::1", 7_700_000L, nowMs = 15_000L))
        assertTrue(log.shouldLog("magis::1", 7_700_000L, nowMs = 70_000L))
        assertTrue(log.shouldLog("magis::2", 7_700_000L, nowMs = 71_000L))
    }
}
