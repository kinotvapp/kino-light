package com.arkiv.player.data.plugin

/**
 * Best-effort, STATIC analysis of a Nuvio scraper's raw JS: this never runs the code (an install-time
 * probe already refuses network access, [ProbePluginHost]), so it can only see what's literally
 * written in the file. A host built at runtime by string concatenation, or fetched from anywhere other
 * than the `domains.json` pattern below, is invisible here -- that gap is exactly what reactive host
 * approval (a separate plan) recovers from later, one real miss at a time.
 */
object NuvioHostExtractor {
    private val DOMAIN = Regex("""(?:https?://)?([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)""", RegexOption.IGNORE_CASE)
    /** A match ending in one of these is almost always a filename, not a domain, in scraper source. */
    private val NOISE_SUFFIXES = setOf("js", "json", "css", "png", "jpg", "jpeg", "gif", "svg", "html", "mp4", "m3u8")
    private val DOMAINS_JSON_URL = Regex("""https://raw\.githubusercontent\.com/\S*domains?\.json""", RegexOption.IGNORE_CASE)

    /** Every domain-shaped string literal in [source], lowercased, de-duplicated, in first-seen order. */
    fun extractHosts(source: String): List<String> =
        DOMAIN.findAll(source)
            .map { it.groupValues[1].lowercase() }
            .filter { it.substringAfterLast('.') !in NOISE_SUFFIXES }
            .distinct()
            .toList()

    /**
     * A `raw.githubusercontent.com` URL that looks like a remote list of mirror domains: several
     * active Nuvio scrapers (4khdhub, uhdmovies) load one because their real site's domain rotates
     * (spec §5.3). `raw.githubusercontent.com` is already one of Kino's ten fixed hosts, so fetching
     * this ONE url during conversion -- never the domains it lists -- is safe before the plugin exists.
     */
    fun findDomainsJsonUrl(source: String): String? = DOMAINS_JSON_URL.find(source)?.value
}
