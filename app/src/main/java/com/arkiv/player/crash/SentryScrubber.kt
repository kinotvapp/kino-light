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
 * Plus addresses, by value, in every event's message, exception values (the whole cause chain) and
 * kept breadcrumbs: URLs, e-mails, IPv4/IPv6 literals, hostnames and long ids become placeholders
 * ([PrivateText.scrubAddresses]) -- `Unable to resolve host "casa.duckdns.org"` or `Cleartext HTTP
 * traffic to 192.168.1.5` must not name the person's server. Exception TYPES and stack frames are
 * never touched: they are the app's code, not the person's data.
 *
 * Breadcrumbs ([breadcrumb], also the SDK's `beforeBreadcrumb`): only the SDK's own lifecycle,
 * navigation, device, network-state and UI-interaction categories ([allowedBreadcrumbCategories])
 * are kept. Anything else is dropped -- above all `Logcat`: the Gradle plugin's logcat
 * instrumentation is off (`app/build.gradle.kts`), but should it ever come back, every `Log.w` line
 * (a plugin's raw error text, `fetch GET <host><path>`) would ride along on every event.
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
            message.formatted = scrubText(message.formatted)
            message.message = scrubText(message.message)
        }
        // Values only (the message of each exception in the cause chain); type, module and
        // stacktrace stay exactly as captured.
        event.exceptions?.forEach { it.value = scrubText(it.value) }

        event.breadcrumbs?.let { crumbs ->
            val kept = crumbs.mapNotNull { breadcrumb(it) }
            if (kept.size != crumbs.size) event.breadcrumbs = kept
        }

        return event
    }

    /**
     * One breadcrumb as it may leave the device, or null to drop it: only [allowedBreadcrumbCategories]
     * are kept, with their message and string data scrubbed like an exception value and credential-
     * named data keys removed. The SDK's `beforeBreadcrumb` (see `ArkivApp.installSentry`), and
     * [scrub] again for whatever an event already carries.
     */
    fun breadcrumb(breadcrumb: Breadcrumb): Breadcrumb? {
        val category = breadcrumb.category ?: return null
        if (category !in allowedBreadcrumbCategories) return null
        breadcrumb.message = scrubText(breadcrumb.message)
        val data = breadcrumb.data
        data.keys.filter { sensitiveKeyPattern.containsMatchIn(it) }.toList()
            .forEach { data.remove(it) }
        data.entries.filter { it.value is String }.toList()
            .forEach { (key, value) -> data[key] = scrubText(value as String) }
        return breadcrumb
    }

    /** Credential shapes, then addresses; app/platform text, so dotted code names are kept. */
    private fun scrubText(text: String?): String? {
        val redacted = redactSensitiveText(text)
        if (redacted.isNullOrEmpty()) return redacted
        return PrivateText.scrubAddresses(redacted, keepCodeNames = true)
    }

    /**
     * The SDK's own breadcrumbs that carry no person data: app/activity lifecycle and navigation
     * (class names), system and connectivity events, orientation, taps/scrolls (view ids). Everything
     * else -- `Logcat`, `http`, a category nobody here chose -- is dropped.
     */
    private val allowedBreadcrumbCategories = setOf(
        "app.lifecycle", "ui.lifecycle", "navigation", "device.event", "device.orientation",
        "network.event", "ui.click", "ui.scroll", "ui.swipe", "session",
    )
}
