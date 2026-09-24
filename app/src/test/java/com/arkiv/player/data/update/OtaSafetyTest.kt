package com.arkiv.player.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staggered-rollout timing: a pending update only surfaces once its randomized time has passed,
 * and never when dismissed. (There is no app-side sha integrity gate anymore -- Android verifies the
 * APK at install time; see [ApkDownloader].)
 */
class OtaSafetyTest {

    private fun pending(promoteAt: Long, dismissed: Boolean = false) =
        PendingUpdate(UpdateInfo(2, "0.2.0", "https://example.com/app.apk", ""), promoteAt, dismissed)

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
