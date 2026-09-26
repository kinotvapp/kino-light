package com.arkiv.player.data.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast, direct pins on [XuperPrivilege.grants]'s exact-match behavior -- no install, no runtime.
 * [XuperPrivilegeGateTest] covers the same gate end to end through a real install and a real
 * runtime; these exist so the cheap, exhaustive negative cases don't have to pay for that.
 */
class XuperPrivilegeTest {
    private fun record(address: String) =
        InstalledRecord(address = address, version = "1.0.0", sha256 = "", hosts = emptyList(), installedAt = 0L)

    @Test fun `the exact recognized address grants`() {
        assertTrue(XuperPrivilege.grants(record(XuperPrivilege.SOURCE_REPO)))
    }

    @Test fun `a ref suffix never grants`() {
        assertFalse(XuperPrivilege.grants(record("${XuperPrivilege.SOURCE_REPO}@some-branch")))
    }

    @Test fun `a subfolder path never grants`() {
        assertFalse(XuperPrivilege.grants(record("${XuperPrivilege.SOURCE_REPO}/sub")))
    }

    @Test fun `a different owner never grants`() {
        assertFalse(XuperPrivilege.grants(record("someone-else/kino-plugin-xuper")))
    }

    @Test fun `a different repo under the same owner never grants`() {
        assertFalse(XuperPrivilege.grants(record("kinotvapp/kino-plugin-xuper-clone")))
    }
}
