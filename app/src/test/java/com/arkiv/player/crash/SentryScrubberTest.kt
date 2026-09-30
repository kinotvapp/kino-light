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
            category = "navigation"
            setData("password", "hunter2")
            setData("url", "https://example.com/ok")
            setData("screen", "com.arkiv.player.MainActivity")
        }
        event.breadcrumbs = mutableListOf(breadcrumb)

        SentryScrubber.scrub(event)

        val scrubbed = event.breadcrumbs!!.single()
        assertTrue(scrubbed.message!!, scrubbed.message!!.contains("[REDACTED]"))
        assertFalse(scrubbed.message!!, scrubbed.message!!.contains("abc123"))
        assertNull(scrubbed.getData("password"))
        // A URL keeps its scheme only; a class name stays readable.
        assertEquals("https://[url]", scrubbed.getData("url"))
        assertEquals("com.arkiv.player.MainActivity", scrubbed.getData("screen"))
    }

    private fun crumb(category: String?, message: String) = Breadcrumb(message).apply { this.category = category }

    @Test
    fun `logcat breadcrumbs, and any category nobody chose, never leave the device`() {
        val pluginSaid = crumb("Logcat", "[x] search failed -- plugin said: Error: [StreamWish2] No se pudo https://hglink.to/e/a?token=zz at <anonymous> (plugin.js:1226)")
        val fetch = crumb("Logcat", "[x] fetch GET 192.168.1.20/Users/9f3c0a1b2c3d4e5f6a7b/Items -> 200")
        val http = crumb("http", "GET https://mi-servidor.duckdns.org/api")
        val none = crumb(null, "whatever")
        assertNull(SentryScrubber.breadcrumb(pluginSaid))
        assertNull(SentryScrubber.breadcrumb(fetch))
        assertNull(SentryScrubber.breadcrumb(http))
        assertNull(SentryScrubber.breadcrumb(none))

        val lifecycle = crumb("ui.lifecycle", "resumed")
        val event = SentryEvent().apply { breadcrumbs = mutableListOf(pluginSaid, lifecycle, fetch, http) }
        SentryScrubber.scrub(event)
        assertEquals(listOf("ui.lifecycle"), event.breadcrumbs!!.map { it.category })
        assertEquals("resumed", event.breadcrumbs!!.single().message)
    }

    @Test
    fun `exception values lose hosts, LAN IPs, IPv6 and URLs but keep types, frames and class names`() {
        val frames = io.sentry.protocol.SentryStackTrace(
            listOf(io.sentry.protocol.SentryStackFrame().apply { module = "com.arkiv.player.ui.player.StreamExoPlayer"; function = "onPlayerError"; lineno = 507 }),
        )
        val values = listOf(
            "Unable to resolve host \"casa.duckdns.org\": No address associated with hostname",
            "Cleartext HTTP traffic to 192.168.1.5 not permitted",
            "failed to connect to /2800:484:1a2b::5 (port 8096) after 8000ms",
            "Response code: 403 for https://jelly.casa-perez.net:8096/Videos/9f3c0a1b2c3d4e5f6a7b/stream?api_key=secretKEY123&Static=true",
            "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
        )
        val event = SentryEvent().apply {
            exceptions = values.map { v ->
                SentryException().apply { type = "IOException"; module = "java.io"; value = v; stacktrace = frames }
            }.toMutableList()
        }
        SentryScrubber.scrub(event)

        val out = event.exceptions!!.map { it.value!! }
        val all = out.joinToString(" | ")
        listOf("duckdns", "casa", "192.168", "2800:484", "jelly", "perez", "9f3c0a1b", "secretKEY123", "api_key=secret").forEach {
            assertFalse("leaked $it in: $all", all.contains(it, ignoreCase = true))
        }
        assertTrue(all, out[0].contains("[host]"))
        assertTrue(all, out[1].contains("[ip]"))
        assertTrue(all, out[2].contains("[ip]"))
        assertTrue(all, out[3].startsWith("Response code: 403 for https://[url]"))
        assertEquals(values[4], out[4])
        event.exceptions!!.forEach {
            assertEquals("IOException", it.type)
            assertEquals("java.io", it.module)
            assertEquals("onPlayerError", it.stacktrace!!.frames!!.single().function)
        }
    }

    @Test
    fun `a plugin-failure style message and a raw plugin said line are scrubbed too`() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "-- plugin said: Error: no se pudo https://own.server.lan:8920/x?token=abc en 10.0.0.2" }
        }
        SentryScrubber.scrub(event)
        val formatted = event.message!!.formatted!!
        listOf("own.server.lan", "abc", "10.0.0.2", "8920").forEach { assertFalse("leaked $it in $formatted", formatted.contains(it)) }
    }
}
