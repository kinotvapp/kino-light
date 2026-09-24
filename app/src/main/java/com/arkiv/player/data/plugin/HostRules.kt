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
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".internal", ".localhost", ".home.arpa")

    fun isValidPattern(pattern: String): Boolean {
        val host = if (pattern.startsWith("*.")) pattern.removePrefix("*.") else pattern
        if (host.isEmpty() || '*' in host || host.length > 253) return false
        if (':' in host || '[' in host) return false
        if (host == "localhost" || PRIVATE_SUFFIXES.any { host.endsWith(it) }) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (!labels.all { LABEL.matches(it) }) return false
        // A numeric last label is an IPv4 literal (or garbage): real TLDs are never all digits.
        return !labels.last().all { it.isDigit() }
    }

    fun matches(host: String, patterns: Collection<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return patterns.any { p ->
            if (p.startsWith("*.")) h.endsWith("." + p.removePrefix("*.")) else h == p
        }
    }
}
