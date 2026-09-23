package com.arkiv.player.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent

/**
 * `beforeSend` scrubber for the Sentry integration (see `ArkivApp.installSentry`). Pure
 * functions, no Android dependency, so this is unit-testable on the JVM without a device/
 * emulator -- see `SentryScrubberTest`.
 *
 * Two passes, since a credential can leak two different ways:
 *  - By KEY: a tag/extra/breadcrumb-data field is itself named like a secret -- the field's
 *    VALUE doesn't matter, the field is dropped outright ([removeExtra]/[removeTag]/
 *    `Breadcrumb.removeData`, driven by [sensitiveKeyPattern]).
 *  - By VALUE, inside free text: an exception message or a breadcrumb's own `message` can
 *    contain a `key=value`/`key: value` substring -- or an HTTP `Authorization: Bearer <token>`
 *    header, formatted straight into a log/exception message -- that looks credential-shaped even
 *    though the field itself ("exception message") isn't named "token". [redactSensitiveText]
 *    regex-redacts just the value half, keeping the key word/auth scheme for context
 *    (`"token=abc123"` -> `"token=[REDACTED]"`, `"Authorization: Bearer eyJ..."` ->
 *    `"Authorization=Bearer [REDACTED]"`), driven by [sensitiveValuePattern].
 *
 * Not a full-text DLP scanner -- "obviously sensitive fields/values", not every possible leak
 * shape. This is defense in depth: nothing in this app's own Sentry wiring hands the SDK a
 * credential on purpose (no OkHttp/network breadcrumbs are wired in -- `autoInstallation` is off
 * in `app/build.gradle.kts`), so in practice these passes should rarely find anything to redact.
 */
object SentryScrubber {
    /** Field-name shape for the by-KEY pass (tags/extras/breadcrumb data). */
    private val sensitiveKeyPattern = Regex(
        "password|token|secret|credential|auth|cookie|api[_-]?key|session|bearer",
        RegexOption.IGNORE_CASE,
    )

    /**
     * By-VALUE pass, two shapes (free-text messages), combined so a single [redactSensitiveText]
     * pass catches both:
     *  1. `key[=:]value` (`(?<key>...)`) -- `token=abc123`, `Cookie: session_id_xyz`,
     *     `Authorization: <token>`, and `bearer=<token>`/`bearer: <token>` (`bearer` is a key
     *     here too, not just a scheme word -- see shape 2 below for the space-separated form).
     *     An optional `Bearer `/`Basic ` scheme word right after the separator
     *     (`(?<scheme1>...)`) is recognized and kept, so `Authorization: Bearer eyJ...` redacts
     *     just the token, not the scheme.
     *  2. Standalone `Bearer <token>` (`(?<scheme2>...)`) -- real HTTP auth headers formatted
     *     straight into a message with no leading "Authorization" word AND no `=`/`:` separator
     *     at all (just whitespace).
     *
     * Deliberately does NOT include a standalone-`Basic` alternative in shape 2 (only inside
     * shape 1, after a `key[=:]` separator): unlike "bearer", "basic" is an ordinary English word
     * (see the "avoid over-redaction of ordinary prose" design goal above) -- `\bbasic\s+\S+`
     * would mangle harmless text like "a very basic setup" into "a very Basic [REDACTED]". Real
     * HTTP Basic auth is essentially always written as `Authorization: Basic <base64>`, which
     * shape 1 already covers.
     *
     * No nested/overlapping quantifiers (`\s*`/`\s+`/`\S+` each appear once per branch, never
     * inside another repeated group) -- linear-time matching, not ReDoS-prone.
     */
    private val sensitiveValuePattern = Regex(
        "(?i)\\b(?<key>password|token|secret|credential|authorization|auth|bearer|cookie|api[_-]?key|session)" +
            "\\s*[=:]\\s*(?:(?<scheme1>bearer|basic)\\s+)?\\S+" +
            "|\\b(?<scheme2>bearer)\\s+\\S+",
    )

    /** Redacts `key=value`/`key: value`/`Authorization: Bearer <token>`-shaped credential
     *  substrings in free text, keeping the key word and/or auth scheme for context. Null/blank
     *  input passes through unchanged. */
    fun redactSensitiveText(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        return sensitiveValuePattern.replace(text) { match ->
            val groups = match.groups as MatchNamedGroupCollection
            val key = groups["key"]?.value
            val scheme = groups["scheme1"]?.value ?: groups["scheme2"]?.value
            buildString {
                if (key != null) {
                    append(key)
                    append('=')
                }
                if (scheme != null) {
                    append(scheme)
                    append(' ')
                }
                append("[REDACTED]")
            }
        }
    }

    /** Applies both passes to a full [SentryEvent] before it leaves the device. */
    fun scrub(event: SentryEvent): SentryEvent {
        // HTTP request bodies/headers/cookies aren't attached anywhere in this app's Sentry setup
        // (no OkHttp integration is wired in), but drop the block outright regardless.
        event.request = null

        event.extras?.keys?.filter { sensitiveKeyPattern.containsMatchIn(it) }?.toList()
            ?.forEach { event.removeExtra(it) }
        event.tags?.keys?.filter { sensitiveKeyPattern.containsMatchIn(it) }?.toList()
            ?.forEach { event.removeTag(it) }

        event.message?.let { message ->
            message.formatted = redactSensitiveText(message.formatted)
            message.message = redactSensitiveText(message.message)
        }
        event.exceptions?.forEach { it.value = redactSensitiveText(it.value) }

        event.breadcrumbs?.forEach { breadcrumb -> scrubBreadcrumb(breadcrumb) }

        return event
    }

    /** By-key AND by-value scrub for one breadcrumb: its own `message` text (by value) and its
     *  `data` map (by key). */
    private fun scrubBreadcrumb(breadcrumb: Breadcrumb) {
        breadcrumb.message = redactSensitiveText(breadcrumb.message)
        val data = breadcrumb.data
        data.keys.filter { sensitiveKeyPattern.containsMatchIn(it) }.toList()
            .forEach { data.remove(it) }
    }
}
