package com.arkiv.player.data.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRulesTest {
    @Test fun `plain and wildcard DNS names are valid patterns`() {
        listOf("archive.org", "*.archive.org", "cdn-1.example.co", "a.b.c.example.com").forEach {
            assertTrue(it, HostRules.isValidPattern(it))
        }
    }

    @Test fun `dangerous or malformed patterns are refused`() {
        listOf(
            "*", "*.", "*.*.org", "a*.org", "localhost", "foo.localhost", "printer.local", "nas.lan",
            "svc.internal", "router.home.arpa", "192.168.1.1", "10.0.0.1", "[::1]", "::1", "org",
            "Archive.org", "exa mple.com", "-bad.com", "bad-.com", "a..b.com", "", "example.com.",
            "x.123",
        ).forEach { assertFalse(it, HostRules.isValidPattern(it)) }
    }

    @Test fun `exact pattern matches only that host`() {
        assertTrue(HostRules.matches("archive.org", listOf("archive.org")))
        assertTrue(HostRules.matches("ARCHIVE.org.", listOf("archive.org")))
        assertFalse(HostRules.matches("ia800.us.archive.org", listOf("archive.org")))
        assertFalse(HostRules.matches("evilarchive.org", listOf("archive.org")))
    }

    @Test fun `wildcard matches subdomains but not the bare domain`() {
        val p = listOf("*.archive.org")
        assertTrue(HostRules.matches("dn600309.us.archive.org", p))
        assertTrue(HostRules.matches("ia.archive.org", p))
        assertFalse(HostRules.matches("archive.org", p))
        assertFalse(HostRules.matches("notarchive.org", p))
    }
}
