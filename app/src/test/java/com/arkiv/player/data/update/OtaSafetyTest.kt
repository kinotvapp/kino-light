package com.arkiv.player.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two safety-critical OTA primitives: the SHA-256 the integrity gate compares against, and the
 * staggered-rollout timing (only surface once the randomized time passed, never when dismissed).
 */
class OtaSafetyTest {

    @Test
    fun `sha256Hex matches known vectors (lowercase hex)`() {
        // FIPS 180-4 test vectors.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkDownloader.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkDownloader.sha256Hex("abc".toByteArray()),
        )
    }

    private fun pending(promoteAt: Long, dismissed: Boolean = false) =
        PendingUpdate(UpdateInfo(2, "0.2.0", "https://example.com/app.apk", "", ""), promoteAt, dismissed)

    @Test
    fun `isDue only once the staggered time has passed`() {
        assertFalse("before promote time", pending(promoteAt = 1_000).isDue(now = 999))
        assertTrue("at promote time", pending(promoteAt = 1_000).isDue(now = 1_000))
        assertTrue("after promote time", pending(promoteAt = 1_000).isDue(now = 2_000))
    }

    @Test
    fun `a dismissed pending update never becomes due`() {
        assertFalse(pending(promoteAt = 1_000, dismissed = true).isDue(now = 9_999))
    }
}
