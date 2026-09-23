package com.arkiv.player.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function scrubber, no Android/device needed -- these are plain JVM model classes from the
 * `sentry` core artifact. Covers both passes described in [SentryScrubber]'s class KDoc: by KEY
 * (tags/extras/breadcrumb-data field names) and by VALUE (credential-shaped substrings inside
 * free text -- exception messages, captured messages, breadcrumb messages).
 */
class SentryScrubberTest {

    @Test
    fun `redacts a credential-shaped value in free text, keeping the key word`() {
        val redacted = SentryScrubber.redactSensitiveText("auth failed token=abc123")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("abc123"))
        assertTrue(redacted, redacted.contains("token="))
    }

    @Test
    fun `redacts the colon separator shape too`() {
        val redacted = SentryScrubber.redactSensitiveText("Cookie: session_id_xyz")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("session_id_xyz"))
    }

    @Test
    fun `leaves ordinary text untouched`() {
        val text = "stream not found for episode 4"

        assertEquals(text, SentryScrubber.redactSensitiveText(text))
    }

    @Test
    fun `redacts an Authorization Bearer header formatted into a message, keeping the scheme`() {
        val redacted = SentryScrubber.redactSensitiveText("Authorization: Bearer eyJabc.def.ghi")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("eyJabc.def.ghi"))
        assertTrue(redacted, redacted.contains("Bearer"))
    }

    @Test
    fun `redacts a standalone Bearer token with no leading Authorization word`() {
        val redacted = SentryScrubber.redactSensitiveText("Bearer abc123")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("abc123"))
        assertTrue(redacted, redacted.contains("Bearer"))
    }

    @Test
    fun `redacts Authorization equals Bearer shape too`() {
        val redacted = SentryScrubber.redactSensitiveText("Authorization=Bearer x")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("=Bearer x"))
    }

    @Test
    fun `redacts bearer equals token, the separator-based shape with no Authorization prefix`() {
        val redacted = SentryScrubber.redactSensitiveText("bearer=abc123")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("abc123"))
    }

    @Test
    fun `redacts bearer colon token, the separator-based shape with no Authorization prefix`() {
        val redacted = SentryScrubber.redactSensitiveText("bearer: abc123")!!

        assertTrue(redacted, redacted.contains("[REDACTED]"))
        assertFalse(redacted, redacted.contains("abc123"))
    }

    @Test
    fun `an ordinary sentence containing the word authorization with no token isn't mangled`() {
        val text = "authorization failed for this device"

        assertEquals(text, SentryScrubber.redactSensitiveText(text))
    }

    @Test
    fun `an ordinary sentence containing the word basic isn't over-redacted`() {
        // "basic" is an ordinary English word -- only Bearer is recognized standalone (no leading
        // key/separator); see sensitiveValuePattern's KDoc for why.
        val text = "this is a very basic setup"

        assertEquals(text, SentryScrubber.redactSensitiveText(text))
    }

    @Test
    fun `handles null and empty text without blowing up`() {
        assertNull(SentryScrubber.redactSensitiveText(null))
        assertEquals("", SentryScrubber.redactSensitiveText(""))
    }

    @Test
    fun `scrub drops the HTTP request block outright`() {
        val event = SentryEvent()
        event.request = Request().apply {
            url = "https://example.com"
            cookies = "session=abc123"
        }

        SentryScrubber.scrub(event)

        assertNull(event.request)
    }

    @Test
    fun `scrub removes tags and extras whose KEY looks sensitive, keeps the rest`() {
        val event = SentryEvent()
        event.setTag("auth_token", "abc123")
        event.setTag("screen", "player")
        event.setExtra("password", "hunter2")
        event.setExtra("episode", "4")

        SentryScrubber.scrub(event)

        assertNull(event.getTag("auth_token"))
        assertEquals("player", event.getTag("screen"))
        assertNull(event.extras?.get("password"))
        assertEquals("4", event.extras?.get("episode"))
    }

    @Test
    fun `scrub redacts exception message VALUES, not just keyed fields`() {
        val event = SentryEvent()
        val exception = SentryException().apply {
            type = "IllegalStateException"
            value = "login failed token=abc123"
        }
        event.exceptions = mutableListOf(exception)

        SentryScrubber.scrub(event)

        val redactedValue = event.exceptions!!.single().value!!
        assertTrue(redactedValue, redactedValue.contains("[REDACTED]"))
        assertFalse(redactedValue, redactedValue.contains("abc123"))
    }

    @Test
    fun `scrub redacts a hand-captured message's text`() {
        val event = SentryEvent()
        event.message = Message().apply {
            message = "auth failed token=abc123"
            formatted = "auth failed token=abc123"
        }

        SentryScrubber.scrub(event)

        val formatted = event.message!!.formatted!!
        assertTrue(formatted, formatted.contains("[REDACTED]"))
        assertFalse(formatted, formatted.contains("abc123"))
    }

    @Test
    fun `scrub redacts a breadcrumb's message text and drops sensitive data keys`() {
        val event = SentryEvent()
        val breadcrumb = Breadcrumb("request failed, api_key=abc123").apply {
            setData("password", "hunter2")
            setData("url", "https://example.com/ok")
        }
        event.breadcrumbs = mutableListOf(breadcrumb)

        SentryScrubber.scrub(event)

        val scrubbed = event.breadcrumbs!!.single()
        assertTrue(scrubbed.message!!, scrubbed.message!!.contains("[REDACTED]"))
        assertFalse(scrubbed.message!!, scrubbed.message!!.contains("abc123"))
        assertNull(scrubbed.getData("password"))
        assertEquals("https://example.com/ok", scrubbed.getData("url"))
    }
}
