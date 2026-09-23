package com.arkiv.player.data.ditu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DituRefTest {

    @Test fun `round trip of a movie`() {
        val ref = DituRef(contentId = "12345", contentType = "VOD")
        assertEquals("ditu1:VOD:12345", ref.encode())
        assertEquals(ref, DituRef.decode("ditu1:VOD:12345"))
    }

    @Test fun `round trip of a series`() {
        val ref = DituRef(contentId = "998", contentType = "BUNDLE")
        assertEquals("ditu1:BUNDLE:998", ref.encode())
        assertEquals(ref, DituRef.decode(ref.encode()))
    }

    /** The contentId goes LAST so it doesn't matter if it ever carries a `:` inside. */
    @Test fun `a contentId with two colons survives`() {
        val ref = DituRef(contentId = "a:b:c", contentType = "VOD")
        assertEquals(ref, DituRef.decode(ref.encode()))
    }

    @Test fun `only BUNDLE and GROUP_OF_BUNDLES are series`() {
        assertTrue(DituRef("1", "BUNDLE").isSeries)
        assertTrue(DituRef("1", "GROUP_OF_BUNDLES").isSeries)
        assertFalse(DituRef("1", "VOD").isSeries)
    }

    @Test fun `a ref from another source isn't understood`() {
        assertNull(DituRef.decode("magis1:movie:0:C42"))
        assertNull(DituRef.decode(""))
        assertNull(DituRef.decode("ditu1:VOD"))
        assertNull(DituRef.decode("ditu1:VOD:"))
    }

    /**
     * An old gateway ref (`base64url(json).hmac`) reads the same: it's opaque by contract, not by
     * cryptography. The signature isn't validated —there's nothing to validate it with, and what
     * comes out of here authorizes nothing, it only says what to ask Caracol for— and expiration
     * is ignored on purpose.
     */
    @Test fun `an old gateway ref is understood`() {
        val json = """{"s":"ditu","p":{"content_id":"777","content_type":"BUNDLE"}}"""
        val data = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        val read = DituRef.decode("$data.signaturenobodyvalidates")
        assertEquals(DituRef("777", "BUNDLE"), read)
    }

    /** An old ref from ANOTHER source isn't ours, even with the same shape. */
    @Test fun `an old magis ref isn't claimed by ditu`() {
        val json = """{"s":"magis","p":{"content_id":"C42","program_type":"movie"}}"""
        val data = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertNull(DituRef.decode("$data.signature"))
    }

    /** With no `content_type` the gateway used to send an implicit VOD. */
    @Test fun `an old ref with no content_type falls back to VOD`() {
        val json = """{"s":"ditu","p":{"content_id":"5"}}"""
        val data = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals(DituRef("5", "VOD"), DituRef.decode("$data.signature"))
    }
}
