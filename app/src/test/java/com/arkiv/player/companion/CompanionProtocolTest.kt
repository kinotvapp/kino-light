package com.arkiv.player.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    @Test fun encodeThenDecodeRoundTrips() {
        val env = newEnvelope(TYPE_HELLO, JSONObject().put("deviceId", "abc").put("code", "482913"))
        val back = decodeEnvelope(env.encode())
        assertNotNull(back)
        assertEquals(CompanionProtocol.VERSION, back!!.v)
        assertEquals(TYPE_HELLO, back.type)
        assertEquals(env.id, back.id)
        assertEquals("abc", back.payload.getString("deviceId"))
        assertEquals("482913", back.payload.getString("code"))
    }

    @Test fun decodeRejectsMalformedJson() { assertNull(decodeEnvelope("not json")) }

    @Test fun decodeRejectsMissingFields() {
        assertNull(decodeEnvelope(JSONObject().put("type", "hello").toString())) // no v/id/payload
    }

    @Test fun decodeRejectsUnknownVersion() {
        val s = JSONObject().put("v", 999).put("type", "hello").put("id", "x")
            .put("payload", JSONObject()).toString()
        assertNull(decodeEnvelope(s))
    }

    @Test fun decodeRejectsOversized() {
        val big = "x".repeat(CompanionProtocol.MAX_MESSAGE_BYTES + 1)
        val s = JSONObject().put("v", 1).put("type", "hello").put("id", "x")
            .put("payload", JSONObject().put("blob", big)).toString()
        assertNull(decodeEnvelope(s))
    }

    @Test fun decodeRejectsOversizedMultibyteBytes() {
        // 30000 CJK chars: UTF-16 length ~30k (< 65536) but UTF-8 ~90k bytes (> 65536).
        val blob = "中".repeat(30000)
        val s = org.json.JSONObject().put("v", 1).put("type", "hello").put("id", "x")
            .put("payload", org.json.JSONObject().put("blob", blob)).toString()
        assertTrue(s.length < CompanionProtocol.MAX_MESSAGE_BYTES)          // passes the char check
        assertNull(decodeEnvelope(s))                                       // rejected by the byte check
    }
}
