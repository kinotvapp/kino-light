package com.arkiv.player.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a rooted device.
 *
 * The case that matters most NOT to fail is the false positive: blocking a factory device leaves
 * the owner without an app and without a way to fix it. That's why the first test is the real
 * Fire TV, with its values as measured over ADB on 2026-08-12.
 */
class RootDetectionTest {

    @Test fun `a factory fire tv is not considered rooted`() {
        // Measured on the AFTKM: `tags` carries more than "release-keys", so comparing the whole
        // string against "release-keys" would have blocked it.
        val signals = RootSignals(tags = "amz-p,release-keys", type = "user")
        assertEquals(emptyList<String>(), RootDetection.reasons(signals))
        assertFalse(RootDetection.hasRoot(signals))
    }

    @Test fun `a factory phone isn't either`() {
        val signals = RootSignals(tags = "release-keys", type = "user")
        assertFalse(RootDetection.hasRoot(signals))
    }

    @Test fun `the su binary gives it away`() {
        val signals = RootSignals(suBinaries = listOf("/system/xbin/su"), tags = "release-keys", type = "user")
        assertTrue(RootDetection.hasRoot(signals))
        assertTrue(RootDetection.reasons(signals).single().contains("/system/xbin/su"))
    }

    @Test fun `the magisk app gives it away`() {
        val signals = RootSignals(rootPackages = listOf("com.topjohnwu.magisk"), type = "user")
        assertTrue(RootDetection.hasRoot(signals))
    }

    @Test fun `magisk traces give it away even without its app`() {
        // The case of someone who uninstalls the app but leaves root in place.
        val signals = RootSignals(magiskTraces = listOf("/data/adb/magisk"), type = "user")
        assertTrue(RootDetection.hasRoot(signals))
    }

    @Test fun `a rom signed with test-keys gives it away`() {
        assertTrue(RootDetection.hasRoot(RootSignals(tags = "test-keys", type = "user")))
    }

    @Test fun `a userdebug build gives it away`() {
        assertTrue(RootDetection.hasRoot(RootSignals(tags = "release-keys", type = "userdebug")))
    }

    // --- Mounts: the only thing that survives Shamiko ----------------------------------------

    @Test fun `a mount pointing at data adb is suspicious`() {
        assertTrue(RootDetection.isMountSuspicious("123 45 0:1 / /system/bin rw - ext4 /data/adb/modules rw"))
    }

    @Test fun `an overlay over system is suspicious`() {
        assertTrue(RootDetection.isMountSuspicious("36 24 0:29 / /system rw,relatime - overlay overlay rw"))
    }

    @Test fun `a normal tmpfs outside system is not`() {
        assertFalse(RootDetection.isMountSuspicious("22 20 0:18 / /dev rw,nosuid - tmpfs tmpfs rw"))
        assertFalse(RootDetection.isMountSuspicious("30 24 253:5 / /data rw,nosuid - ext4 /dev/block/dm-5 rw"))
    }

    @Test fun `two threads with different tables give away namespace manipulation`() {
        // The mount namespace belongs to the PROCESS: this can't happen on a clean device, and
        // it's what's left once everything else is hidden.
        val signals = RootSignals(tags = "release-keys", type = "user", inconsistentMounts = true)
        assertTrue(RootDetection.hasRoot(signals))
    }

    @Test fun `all reasons accumulate, not just the first`() {
        // The lock screen shows all of them: a warning that explains nothing is indistinguishable
        // from a bug.
        val signals = RootSignals(
            suBinaries = listOf("/sbin/su"),
            rootPackages = listOf("com.topjohnwu.magisk"),
            tags = "test-keys",
            type = "userdebug",
        )
        assertEquals(4, RootDetection.reasons(signals).size)
    }
}
