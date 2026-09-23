package com.arkiv.player.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPairingTest {
    private fun hello(deviceId: String = "phone1", code: String? = null, token: String? = null) =
        JSONObject().put("deviceId", deviceId).put("name", "Phone").apply {
            if (code != null) put("code", code); if (token != null) put("token", token)
        }

    @Test fun rightCodeAcceptsAndIssuesToken() {
        val r = CompanionPairing.decideHost("482913", hello(code = "482913"),
            { null }, { "TOK" })
        assertEquals(PairResult.Accept("TOK"), r)
    }

    @Test fun wrongCodeRejects() {
        val r = CompanionPairing.decideHost("482913", hello(code = "000000"), { null }, { "TOK" })
        assertTrue(r is PairResult.Reject)
    }

    @Test fun knownTokenAcceptsWithoutCode() {
        val peers = PeerLookup { if (it == "phone1") Peer("phone1", "Phone", "GOODTOKEN", "", 0) else null }
        val r = CompanionPairing.decideHost("482913", hello(token = "GOODTOKEN"), peers, { "NEW" })
        assertEquals(PairResult.Accept("GOODTOKEN"), r)
    }

    @Test fun unknownTokenAndNoCodeRejects() {
        val r = CompanionPairing.decideHost("482913", hello(token = "BAD"), { null }, { "NEW" })
        assertTrue(r is PairResult.Reject)
    }

    @Test fun generateCodeIsSixDigits() {
        repeat(50) { assertTrue(CompanionPairing.generateCode().matches(Regex("\\d{6}"))) }
    }

    @Test fun backoffCapsAt5s() {
        assertEquals(1000L, backoffDelayMs(1))
        assertEquals(2000L, backoffDelayMs(2))
        assertEquals(4000L, backoffDelayMs(3))
        assertEquals(5000L, backoffDelayMs(4))
        assertEquals(5000L, backoffDelayMs(10))
    }
}
