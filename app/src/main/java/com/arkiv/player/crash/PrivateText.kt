package com.arkiv.player.crash

/**
 * The one place that knows what an address looks like inside free text: URLs, e-mails, IPv4 and IPv6
 * literals, hostnames and long ids. Used by `PluginTelemetry.cleanReason` (a plugin's own error text)
 * and by [SentryScrubber] (every event's exception values and message), so a person's server, their
 * LAN, a Jellyfin user id or a signed URL never reaches the error board, whatever produced the text.
 *
 * Pure and JVM-testable (see `PrivateTextTest`, `SentryScrubberTest`).
 */
object PrivateText {
    /**
     * [text] with every address replaced by a placeholder (`[url]`, `[email]`, `[ip]`, `[host]`,
     * `[id]`). A URL keeps only its scheme (`http://[url]`: whether it was cleartext is useful and
     * says nothing about the person).
     *
     * [keepCodeNames]: text written by the app or the platform (an exception's message) is full of
     * dotted names that are not hosts -- `java.lang.String.length()`, `com.arkiv.player.Foo`,
     * `plugin.js` -- and keeping them is what makes a crash readable. With it on, a dotted name with
     * an upper-case letter in a label, one starting with a usual package root, or one ending in a
     * file extension is kept. A plugin's own text (off) loses every dotted name: nothing in it is
     * worth the risk.
     */
    fun scrubAddresses(text: String, keepCodeNames: Boolean = false): String {
        var out = URL.replace(text) { m -> m.groupValues[1] + "://[url]" }
        out = EMAIL.replace(out, "[email]")
        out = IPV4.replace(out, "[ip]")
        out = IPV6.replace(out) { m -> if (looksLikeIpv6(m.value)) "[ip]" else m.value }
        out = HOSTNAME.replace(out) { m -> if (keepCodeNames && isCodeName(m.value)) m.value else "[host]" }
        out = LONG_TOKEN.replace(out, "[id]")
        return out
    }

    /** `2800:484:1a2b::5`, `fe80::1%wlan0`, a full eight-group address; never a clock time (`12:30:45`). */
    private fun looksLikeIpv6(candidate: String): Boolean {
        val bare = candidate.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (bare.count { it == ':' } < 2) return false
        return "::" in bare || bare.count { it == ':' } >= 6
    }

    private fun isCodeName(name: String): Boolean {
        val labels = name.substringBefore(':').split('.')
        if (labels.any { label -> label.any { it.isUpperCase() } }) return true
        if (labels.first() in PACKAGE_ROOTS) return true
        return labels.last().lowercase() in FILE_EXTENSIONS
    }

    private val PACKAGE_ROOTS = setOf(
        "java", "javax", "jdk", "sun", "android", "androidx", "dalvik", "libcore", "kotlin", "kotlinx",
        "com", "org", "io", "net", "okhttp3", "okio", "coil", "coil3", "retrofit2", "dagger",
    )

    private val FILE_EXTENSIONS = setOf(
        "js", "mjs", "json", "kt", "java", "class", "dex", "jar", "so", "apk", "txt", "xml", "html",
        "m3u", "m3u8", "mpd", "ts", "mp4", "mkv", "webm", "vtt", "srt", "png", "jpg", "jpeg", "webp", "zip",
    )

    /** Any `scheme://…` up to the next whitespace; group 1 is the scheme. */
    private val URL = Regex("\\b([A-Za-z][A-Za-z0-9+.-]{1,15})://\\S+")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b")

    /** A run of hex groups and colons (optionally bracketed, optionally with a zone); [looksLikeIpv6] decides. */
    private val IPV6 = Regex("\\[?(?<![\\w:.])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[\\w.]+)?(?![\\w:])\\]?(?::\\d+)?")
    private val HOSTNAME = Regex("\\b(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}(?::\\d+)?\\b")

    /** 16+ id characters with at least one digit: a user id, a session, a hash. */
    private val LONG_TOKEN = Regex("\\b(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{16,}\\b")
}
