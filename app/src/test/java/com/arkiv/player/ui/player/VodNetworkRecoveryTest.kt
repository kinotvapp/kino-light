package com.arkiv.player.ui.player

import androidx.media3.common.PlaybackException
import com.arkiv.player.playback.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodNetworkRecoveryTest {
    private val failed = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    // The real-device case (2026-10-01): a paused Xuper movie, ~40 s in the background, came back
    // with ERROR_CODE_IO_NETWORK_CONNECTION_FAILED.
    @Test fun `a lost connection, a timeout or an HTTP error is the network's`() {
        assertTrue(VodNetworkRecovery.isNetworkError(failed))
        assertTrue(VodNetworkRecovery.isNetworkError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(VodNetworkRecovery.isNetworkError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test fun `errors a retry would only repeat are not`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            1003,
        ).forEach { assertFalse("code $it", VodNetworkRecovery.isNetworkError(it)) }
    }

    @Test fun `a VOD that had played is retried`() {
        assertTrue(VodNetworkRecovery.shouldRetry(failed, live = false, everReady = true, retriesSpent = 0))
    }

    @Test fun `a live channel never is`() {
        assertFalse(VodNetworkRecovery.shouldRetry(failed, live = true, everReady = true, retriesSpent = 0))
    }

    @Test fun `a stream that never opened fails at once, as before`() {
        assertFalse(VodNetworkRecovery.shouldRetry(failed, live = false, everReady = false, retriesSpent = 0))
    }

    @Test fun `the attempts are capped`() {
        val max = VodNetworkRecovery.MAX_RETRIES
        assertEquals(3, max)
        assertTrue(VodNetworkRecovery.shouldRetry(failed, live = false, everReady = true, retriesSpent = max - 1))
        assertFalse(VodNetworkRecovery.shouldRetry(failed, live = false, everReady = true, retriesSpent = max))
    }

    @Test fun `a non-network error is not retried even with attempts left`() {
        assertFalse(
            VodNetworkRecovery.shouldRetry(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, live = false, everReady = true, retriesSpent = 0),
        )
    }

    @Test fun `first re-prepares in place, second resolves again when it can, third re-prepares`() {
        assertEquals(NetworkRetryStep.REPREPARE, VodNetworkRecovery.step(1, canReResolve = true))
        assertEquals(NetworkRetryStep.RE_RESOLVE, VodNetworkRecovery.step(2, canReResolve = true))
        assertEquals(NetworkRetryStep.REPREPARE, VodNetworkRecovery.step(3, canReResolve = true))
        assertEquals(NetworkRetryStep.REPREPARE, VodNetworkRecovery.step(2, canReResolve = false))
    }

    @Test fun `the waits grow`() {
        val waits = (1..3).map(VodNetworkRecovery::delayMs)
        assertEquals(waits.sorted(), waits)
        assertTrue(waits.first() > 0)
    }

    @Test fun `only a plugin VOD title is resolved again`() {
        assertTrue(VodNetworkRecovery.canReResolve(SourceKind.PLUGIN, live = false, hostQuestionOpen = false))
        assertFalse(VodNetworkRecovery.canReResolve(SourceKind.PLUGIN, live = true, hostQuestionOpen = false))
        assertFalse(VodNetworkRecovery.canReResolve(SourceKind.PLUGIN, live = false, hostQuestionOpen = true))
        assertFalse(VodNetworkRecovery.canReResolve(SourceKind.MAGIS, live = false, hostQuestionOpen = false))
        assertFalse(VodNetworkRecovery.canReResolve(null, live = false, hostQuestionOpen = false))
    }

    @Test fun `re-resolves are capped within the window and come back after it`() {
        val budget = NetworkReResolveBudget(max = 2, windowMs = 10_000L)
        assertTrue(budget.tryTake(0L))
        assertTrue(budget.tryTake(1_000L))
        assertFalse(budget.tryTake(2_000L))
        assertFalse(budget.tryTake(9_999L))
        assertTrue(budget.tryTake(10_000L))
        assertFalse(budget.tryTake(10_500L))
    }

    @Test fun `a new title starts with a full budget`() {
        val budget = NetworkReResolveBudget(max = 1, windowMs = 60_000L)
        assertTrue(budget.tryTake(0L))
        assertFalse(budget.tryTake(1L))
        budget.reset()
        assertTrue(budget.tryTake(2L))
    }

    // A CDN that opens, reaches READY and dies again resets the per-READY attempts every time.
    @Test fun `recoveries are capped overall, READY or not, and back off`() {
        val budget = NetworkRecoveryBudget(max = 8, windowMs = 600_000L)
        val now = 1_000_000L
        repeat(8) { assertTrue(budget.tryTake(now + it)) }
        assertFalse(budget.tryTake(now + 9))
        assertEquals(8, budget.recent(now + 9))
        // After the window, the stream gets them back.
        assertTrue(budget.tryTake(now + 600_000L))
        // The first few recoveries wait as before; past them each wait grows, up to a ceiling.
        assertEquals(VodNetworkRecovery.delayMs(1), VodNetworkRecovery.delayMs(1, recent = VodNetworkRecovery.MAX_RETRIES))
        val later = (VodNetworkRecovery.MAX_RETRIES + 1..VodNetworkRecovery.MAX_RETRIES + 8).map { VodNetworkRecovery.delayMs(1, it) }
        assertEquals(later.sorted(), later)
        assertTrue(later.first() > VodNetworkRecovery.delayMs(1))
        assertEquals(500L + VodNetworkRecovery.MAX_BACKOFF_MS, later.last())
    }
}
