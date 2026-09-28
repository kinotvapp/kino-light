package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
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
        assertFalse(XuperPrivilege.grants(record("xuper-plugin/kino-plugin-xuper-clone")))
    }

    // 2026-09-28: the official repo moved to the xuper-plugin owner; installs made from the old
    // kinotvapp repo (still online) must keep every privilege, with the same exact-match strictness.

    @Test fun `the official repo is the xuper-plugin one, and the kinotvapp one is its legacy address`() {
        assertEquals("xuper-plugin/kino-plugin-xuper", XuperPrivilege.SOURCE_REPO)
        assertEquals(setOf("kinotvapp/kino-plugin-xuper"), XuperPrivilege.LEGACY_SOURCE_REPOS)
    }

    @Test fun `an install from the legacy address still grants`() {
        assertTrue(XuperPrivilege.grants(record("kinotvapp/kino-plugin-xuper")))
    }

    @Test fun `a ref, a subfolder or a look-alike of either address never grants`() {
        for (address in listOf(
            "kinotvapp/kino-plugin-xuper@dev", "kinotvapp/kino-plugin-xuper/sub",
            "xuper-plugin/kino-plugin-xuper@dev", "xuper-plugin/kino-plugin-xuper/sub",
            "Xuper-Plugin/kino-plugin-xuper", "xuper-plugin/other", "someone-else/kino-plugin-xuper",
        )) {
            assertFalse(address, XuperPrivilege.grants(record(address)))
            assertFalse(address, XuperPrivilege.isOfficial(address))
        }
    }

    @Test fun `isOfficial is exactly what grants compares`() {
        assertTrue(XuperPrivilege.isOfficial(XuperPrivilege.SOURCE_REPO))
        assertTrue(XuperPrivilege.isOfficial("kinotvapp/kino-plugin-xuper"))
        assertFalse(XuperPrivilege.isOfficial(null))
        assertFalse(XuperPrivilege.isOfficial(""))
    }

    @Test fun `the reserved Xuper id may be claimed by the new and the old repo, and nobody else`() {
        assertEquals(
            setOf("xuper-plugin/kino-plugin-xuper", "kinotvapp/kino-plugin-xuper"),
            ReservedPluginIds.OWNERS[XuperPrivilege.MANIFEST_ID],
        )
    }
}
