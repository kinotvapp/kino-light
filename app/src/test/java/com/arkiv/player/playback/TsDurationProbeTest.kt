package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An MPEG-TS doesn't say how long it runs in any header: the duration is deduced by subtracting
 * the clock (PCR) at the start from the one at the end. These tests pin that calculation with
 * synthetic TS packets.
 */
class TsDurationProbeTest {

    /** 188-byte TS packet with a PCR (90 kHz base) in the adaptation field. */
    private fun packetWithPcr(pid: Int, base90k: Long): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x20                              // adaptation field only, no payload
        p[4] = 183.toByte()                      // adaptation field length
        p[5] = 0x10                              // PCR-present flag
        p[6] = ((base90k shr 25) and 0xFF).toByte()
        p[7] = ((base90k shr 17) and 0xFF).toByte()
        p[8] = ((base90k shr 9) and 0xFF).toByte()
        p[9] = ((base90k shr 1) and 0xFF).toByte()
        p[10] = ((base90k and 1L) shl 7).toByte()
        p[11] = 0
        return p
    }

    /** TS packet with no PCR (payload only), same pid. */
    private fun packetWithoutPcr(pid: Int): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = 0x10                              // payload only
        return p
    }

    private fun block(vararg packets: ByteArray): ByteArray =
        packets.fold(ByteArray(0)) { acc, p -> acc + p }

    @Test fun duration_is_the_tail_s_last_pcr_minus_the_head_s_first() {
        val head = block(
            packetWithPcr(0x100, 90_000L),        // 1 s
            packetWithoutPcr(0x100),
            packetWithPcr(0x100, 180_000L),
        )
        val tail = block(
            packetWithPcr(0x100, 900_000_000L),
            packetWithPcr(0x100, 913_050_000L),   // 10,145 s
        )
        // (913050000 - 90000) / 90 = 10144000 ms
        assertEquals(10_144_000L, TsDurationProbe.durationMs(head, tail))
    }

    @Test fun aligns_when_the_tail_starts_mid_packet() {
        val head = block(packetWithPcr(0x100, 0L))
        // A Range request can land on any byte: the tail arrives misaligned and sync has to be
        // found before parsing.
        val tail = ByteArray(57) { 0x11 } + block(
            packetWithPcr(0x100, 90_000L),
            packetWithPcr(0x100, 180_000L),
            packetWithPcr(0x100, 270_000L),
        )
        assertEquals(3_000L, TsDurationProbe.durationMs(head, tail))
    }

    @Test fun ignores_pcrs_from_another_pid() {
        val head = block(packetWithPcr(0x100, 90_000L))
        val tail = block(
            packetWithPcr(0x100, 90_090_000L),    // the right one: 1000 s later
            packetWithPcr(0x200, 500_000_000L),   // another program, must not count
        )
        assertEquals(1_000_000L, TsDurationProbe.durationMs(head, tail))
    }

    @Test fun zero_when_the_tail_carries_no_pcr_of_the_same_pid() {
        val head = block(packetWithPcr(0x100, 90_000L))
        val tail = block(packetWithPcr(0x200, 90_090_000L))
        assertEquals(0L, TsDurationProbe.durationMs(head, tail))
    }

    @Test fun zero_when_there_is_no_pcr_at_all() {
        val head = block(packetWithoutPcr(0x100), packetWithoutPcr(0x100))
        val tail = block(packetWithoutPcr(0x100))
        assertEquals(0L, TsDurationProbe.durationMs(head, tail))
    }

    @Test fun handles_the_33_bit_counter_wraparound() {
        // The PCR is 33 bits at 90 kHz: it wraps around every ~26.5 h. A file that starts near the
        // cap ends with a PCR SMALLER than the one at the start.
        val cap = 1L shl 33
        val head = block(packetWithPcr(0x100, cap - 90_000L))   // 1 s before the wrap
        val tail = block(packetWithPcr(0x100, 180_000L))        // 2 s after the wrap
        assertEquals(3_000L, TsDurationProbe.durationMs(head, tail))
    }

    @Test fun zero_when_the_result_is_not_believable() {
        // More than 24 h = the parsing went haywire (a PCR from another program, garbage in the
        // buffer). Better to show no duration than a made-up one.
        val head = block(packetWithPcr(0x100, 0L))
        val tail = block(packetWithPcr(0x100, 90_000L * 60 * 60 * 25))
        assertEquals(0L, TsDurationProbe.durationMs(head, tail))
    }

    // ─── how long the probe can take ──────────────────────────────────────
    // The video does NOT start until this finishes, so every second here is a second of spinner.
    // Measured on 2026-08-11 on the Fire TV: one stretch ate its 8 s timeout and the whole probe
    // cost 9.01 s of a 13.4 s startup. The retry answered in ~1 s -- the problem wasn't the CDN, it
    // was how long a connection that was already dead got waited on.

    @Test fun the_probe_does_not_hold_out_longer_than_the_proxy_on_the_same_dead_connection() {
        // It hits the SAME CDN as ArchiveCacheProxy and the deadlines come from the same place
        // -OriginPolicy-, but through its own profile: the probe blocks startup and what's at
        // stake is a bar with no duration, while the proxy risks the film cutting out. What's
        // required is that it NEVER waits longer than playback does.
        assertTrue(
            "the probe cannot hold out longer than the proxy",
            TsDurationProbe.readTimeoutMs(0) <=
                OriginPolicy.responseMs(0, OriginPolicy.Profile.MAGIS),
        )
        assertEquals(
            OriginPolicy.responseMs(0, OriginPolicy.Profile.MAGIS_PROBE),
            TsDurationProbe.readTimeoutMs(0),
        )
    }

    @Test fun the_probe_s_budget_fits_what_a_human_will_wait() {
        // It used to be 30 s: half a minute of spinner for a progress bar. The duration is a
        // nicety, never a reason not to play. This cap doesn't come from a round number but from
        // the measured case (the test below): what's required is covering it without overshooting.
        assertTrue(
            "budget = ${TsDurationProbe.BUDGET_MS}ms",
            TsDurationProbe.BUDGET_MS <= 15_000,
        )
    }

    @Test fun the_measured_case_one_dead_connection_per_stretch_fits_the_budget() {
        // It's the REAL case, not the theoretical worst one: each stretch eats one dead connection
        // and recovers on the second attempt. If that doesn't fit the budget, the probe gets
        // cancelled and the bar is left with no duration in exactly the case that did have a fix.
        val perStretch = TsDurationProbe.readTimeoutMs(0) +
            TsDurationProbe.waitBetweenAttemptsMs(0) +
            TsDurationProbe.readTimeoutMs(1)
        assertTrue(
            "two stretches in series = ${2 * perStretch}ms against ${TsDurationProbe.BUDGET_MS}ms",
            2 * perStretch <= TsDurationProbe.BUDGET_MS,
        )
    }
}
