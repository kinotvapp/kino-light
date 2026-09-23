package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long to wait on archive.org and when to retry. See [OriginPolicy] for the why.
 */
class OriginPolicyTest {

    // ─── the read timeout grows with each attempt ──────────────────────
    // Measured on 2026-08-10 against the Evangelion item: 72.3 s to the first byte (a correct 206,
    // just slow). With the old fixed 20 s, EVERY attempt died by timeout.

    @Test fun the_first_attempt_does_not_wait_forever() {
        // The common case (a healthy origin) answers in seconds: if the first attempt already paid
        // the worst case's 90 s, a genuinely dead origin would leave the user staring at nothing for 4 minutes.
        assertEquals(20_000, OriginPolicy.responseMs(0))
    }

    @Test fun each_retry_gives_the_origin_more_room() {
        assertTrue(OriginPolicy.responseMs(1) > OriginPolicy.responseMs(0))
        assertTrue(OriginPolicy.responseMs(2) > OriginPolicy.responseMs(1))
    }

    @Test fun the_last_attempt_covers_the_measured_72s() {
        assertTrue(
            "the last attempt has to hold out for the measured worst case (72.3 s)",
            OriginPolicy.responseMs(OriginPolicy.ATTEMPTS - 1) > 72_300,
        )
    }

    @Test fun an_extra_attempt_does_not_go_past_the_cap() {
        // Nobody should ever ask for attempt #9, but if it happens it can't return half an hour.
        assertEquals(OriginPolicy.responseMs(OriginPolicy.ATTEMPTS - 1), OriginPolicy.responseMs(9))
    }

    @Test fun connecting_is_short_because_the_handshake_is_not_the_slow_part() {
        // Measured: 4.28 s to connect against 72.3 s to the first byte. What's slow is the node serving.
        assertTrue(OriginPolicy.CONNECT_MS <= 20_000)
    }

    // ─── the wait between attempts grows ────────────────────────────────────
    // It used to be a flat 400 ms: against a saturated origin, three attempts in 1.2 s are three
    // quick blows at the same node that's already saying it can't keep up.

    @Test fun the_wait_between_attempts_grows() {
        assertTrue(OriginPolicy.waitMs(1) > OriginPolicy.waitMs(0))
        assertTrue(OriginPolicy.waitMs(2) > OriginPolicy.waitMs(1))
    }

    @Test fun the_first_wait_stays_short() {
        // An occasional no recovers right away; the good case shouldn't be punished.
        assertEquals(400L, OriginPolicy.waitMs(0))
    }

    // ─── what's worth retrying ───────────────────────────────────────
    // This is the distinction that didn't exist: the code used to treat "it rejected me" and "it
    // didn't answer in time" as the same thing.

    @Test fun a_404_is_never_retried() {
        // The file isn't there: retrying means losing 3 timeouts to land on the same 404. It's
        // also the signal that archive renamed it and the metadata needs revalidating.
        assertFalse(OriginPolicy.worthRetrying(404))
    }

    @Test fun a_503_is_retried() {
        assertTrue(OriginPolicy.worthRetrying(503))
    }

    @Test fun a_timeout_is_retried() {
        // -1 is the code the proxy uses when the connection died with no response.
        assertTrue(OriginPolicy.worthRetrying(-1))
    }

    @Test fun the_other_server_errors_are_retried() {
        listOf(429, 500, 502, 504).forEach {
            assertTrue("$it should be retried", OriginPolicy.worthRetrying(it))
        }
    }

    @Test fun a_success_is_not_retried() {
        assertFalse(OriginPolicy.worthRetrying(200))
        assertFalse(OriginPolicy.worthRetrying(206))
    }

    @Test fun a_410_is_not_retried_either() {
        // Same as 404: the resource doesn't come back by insisting.
        assertFalse(OriginPolicy.worthRetrying(410))
    }

    // ─── the total budget can't blow up ────────────────────────────

    @Test fun the_full_worst_case_does_not_exceed_three_minutes() {
        // A dead origin has to give up in a time a human tolerates staring at the screen.
        val total = (0 until OriginPolicy.ATTEMPTS).sumOf {
            OriginPolicy.responseMs(it).toLong() + OriginPolicy.waitMs(it)
        }
        assertTrue("total budget = ${total}ms", total <= 180_000)
    }

    // ─── the tolerance CANNOT be the same for every origin ──────────
    // Measured on 2026-08-11 against magis's CDN (`yuwc.swzablvpm.com`), 20 shots at the same file:
    // head 0.11-0.13 s, tail 0.13-0.30 s, and with three connections draining at once the tail kept
    // answering in 0.44-0.82 s. Magis answers in UNDER A SECOND or never answers at all -- the
    // "slow but it arrives" case that justifies archive's 20 s doesn't exist here.
    //
    // What it cost on device: VLC requested the file's tail, that connection came out dead, and
    // the proxy sat waiting on it for 20.1 s. The retry answered in 101 ms.

    @Test fun magis_does_not_wait_twenty_seconds_on_a_dead_connection() {
        assertTrue(
            "a healthy magis CDN answers in <1 s: waiting longer is dead time",
            OriginPolicy.responseMs(0, OriginPolicy.Profile.MAGIS) <= 4_000,
        )
    }

    @Test fun magis_scales_its_deadlines_because_the_CDN_is_erratic() {
        // This USED TO require the whole budget to fit inside 10 s, to beat VLC's "no picture ->
        // software" rescue to the punch. That invariant DIED once magis moved to ExoPlayer: there's
        // no longer a media reload to get ahead of, and the tight deadline was only strangling
        // healthy requests. Measured on the Fire Stick on 2026-08-22: two ranges marked "rejected by
        // the origin" at 3.002 s and 3.004 s -the timer, pinned, not the CDN- and 18 s of waiting
        // before the first picture.
        //
        // What's required now is the opposite: that the deadlines GROW, because the same range that
        // answers in 164 ms sometimes goes past 4 s with nothing else at stake.
        val profile = OriginPolicy.Profile.MAGIS
        val deadlines = (0 until OriginPolicy.attempts(profile)).map { OriginPolicy.responseMs(it, profile) }
        assertTrue(
            "magis's deadlines have to increase, and they are $deadlines",
            deadlines.zipWithNext().all { (a, b) -> b > a },
        )
    }

    @Test fun the_duration_probe_gives_up_sooner_than_playback() {
        // Same CDN, different game: the probe blocks startup -every second of it is a spinner- and
        // the worst that happens if it fails is a bar with no duration. Playback, on the other hand,
        // cuts out. That's why the probe gives up sooner; if the two ever line up again, TsDurationProbe's
        // budget overflows (see its own test).
        val playback = OriginPolicy.responseMs(1, OriginPolicy.Profile.MAGIS)
        val probe = OriginPolicy.responseMs(1, OriginPolicy.Profile.MAGIS_PROBE)
        assertTrue("probe=${probe}ms has to be less than playback=${playback}ms", probe < playback)
    }

    // Waiting for the RESPONSE and riding out a stall reading the BODY are two different things,
    // and stuffing them into the same number is what makes lowering it dangerous:
    // HttpURLConnection's `readTimeout` governs both. An origin that doesn't answer in 2 s is dead;
    // a stream that goes 2 s without data mid-film is a normal WiFi hiccup, and cutting it off there
    // would be a regression.

    @Test fun riding_out_a_body_stall_is_longer_than_waiting_for_the_response() {
        OriginPolicy.Profile.entries.forEach { profile ->
            assertTrue(
                "$profile: the body must hold out longer than the response",
                OriginPolicy.bodyMs(profile) > OriginPolicy.responseMs(0, profile),
            )
        }
    }

    @Test fun magis_rides_out_a_wifi_hiccup_mid_film() {
        assertTrue(
            "cutting the body off after a few seconds would break playback, not fix it",
            OriginPolicy.bodyMs(OriginPolicy.Profile.MAGIS) >= 20_000,
        )
    }

    @Test fun archive_keeps_the_tolerance_it_needed() {
        // Not an acceptable regression: the Evangelion case (72.3 s to the first byte) still has to fit.
        val profile = OriginPolicy.Profile.ARCHIVE
        assertEquals(20_000, OriginPolicy.responseMs(0, profile))
        assertTrue(OriginPolicy.responseMs(OriginPolicy.attempts(profile) - 1, profile) > 72_300)
    }

    @Test fun magis_does_not_reuse_pool_sockets() {
        // The hang's hypothesis, and the reason `curl` never reproduced it: curl opens a new socket
        // every time and didn't hang once in 26 shots. On device the open connection always hung
        // right after `preWarm` abandoned a 209 MB `bytes=0-` having read 2 MB -- i.e. with an
        // undrained body left in the keep-alive pool.
        assertFalse(OriginPolicy.Profile.MAGIS.reuseSockets)
    }

    @Test fun archive_still_reuses_sockets() {
        // Archive.org does behave well with keep-alive, and reusing saves it the handshake.
        assertTrue(OriginPolicy.Profile.ARCHIVE.reuseSockets)
    }
}
