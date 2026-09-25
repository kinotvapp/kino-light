package com.arkiv.player.data.plugin

/**
 * Which hosts a plugin may declare, and whether a host matches what it declared.
 *
 * Declared hosts are the ONLY network destinations a plugin gets (see `PluginHttp`), and the
 * person approves them on screen, so a pattern must name a public DNS domain: no bare `*`, no IP
 * literals (IPv4 or IPv6), nothing that resolves inside the home network (`localhost`, `.local`,
 * `.lan`, `.internal`, `.home.arpa`). `*.example.com` covers subdomains only, not `example.com`
 * itself — a plugin that needs both declares both.
 */
object HostRules {
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `hostRules.labelPattern` to it. */
    internal val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `hostRules.privateSuffixes` to it. */
    internal val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".internal", ".localhost", ".home.arpa")
    const val MAX_HOST_CHARS = 253

    fun isValidPattern(pattern: String): Boolean {
        val host = if (pattern.startsWith("*.")) pattern.removePrefix("*.") else pattern
        if (host.isEmpty() || '*' in host || host.length > MAX_HOST_CHARS) return false
        if (':' in host || '[' in host) return false
        if (host == "localhost" || PRIVATE_SUFFIXES.any { host.endsWith(it) }) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (!labels.all { LABEL.matches(it) }) return false
        // A numeric last label is an IPv4 literal (or garbage): real TLDs are never all digits.
        return !labels.last().all { it.isDigit() }
    }

    /**
     * An address that points into the device or the home network by construction: an IPv4 literal
     * (any all-digit last label, which also covers the `2130706433` / `127.1` shorthands Java's
     * resolver accepts), an IPv6 literal, `localhost` or a local-only suffix. Never a valid declared
     * pattern (see [isValidPattern]); the host gate and the image filter refuse it outright.
     */
    fun isLocalAddress(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || ':' in h || '[' in h) return true
        if (h == "localhost" || PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        return h.substringAfterLast('.').all { it.isDigit() }
    }

    fun matches(host: String, patterns: Collection<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return patterns.any { p ->
            if (p.startsWith("*.")) h.endsWith("." + p.removePrefix("*.")) else h == p
        }
    }
}
