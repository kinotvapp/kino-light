package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * What a scraper's remote `domains.json` ([NuvioHostExtractor.parseDomainsJson]) contributes to its
 * generated manifest. [preferred] go FIRST in the host list (ahead of the scraper's own literals):
 * they are the current, rotated domain(s) the scraper will actually reach. [others] go LAST: every
 * entry of a shared, multi-scraper list when this scraper names none of its keys -- kept only as a
 * best effort, and the first thing the 20-host cap drops.
 */
data class NuvioRemoteHosts(val preferred: List<String>, val others: List<String>) {
    companion object {
        val NONE = NuvioRemoteHosts(emptyList(), emptyList())
    }
}

/**
 * Best-effort, STATIC analysis of a Nuvio scraper's raw JS: this never runs the code (an install-time
 * probe already refuses network access, [ProbePluginHost]), so it can only see what's literally
 * written in the file. A host built at runtime by string concatenation, or fetched from anywhere other
 * than the `domains.json` pattern below, is invisible here -- that gap is exactly what reactive host
 * approval (a separate plan) recovers from later, one real miss at a time.
 *
 * Only the INSIDE of string literals (`'…'`, `"…"`, `` `…` ``) is ever looked at -- never identifiers,
 * comments or regex literals -- and within a literal only two shapes count as a host:
 *  - the host of an `http(s)://` URL anywhere in it (`"https://api.themoviedb.org/3"`), or
 *  - the WHOLE literal being a bare hostname (`"4khdhub.one"`, a mirror list's entries).
 * So a `User-Agent` string's `Mozilla/5.0`/`537.36`, an IP like `120.0.0.0`, `module.exports` or a
 * CSS selector like `"div.entry-content a"` never become "hosts". Every candidate then goes through
 * [HostRules.isValidPattern] -- the SAME rule `ManifestParser` applies -- so nothing extracted here can
 * make the generated manifest invalid, or waste one of its 20 host slots on garbage.
 */
object NuvioHostExtractor {
    private val URL_HOST = Regex("""https?://([a-z0-9.-]+)""", RegexOption.IGNORE_CASE)
    private val BARE_HOST = Regex("""^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$""", RegexOption.IGNORE_CASE)

    /** A match ending in one of these is almost always a filename, not a domain, in scraper source. */
    private val NOISE_SUFFIXES = setOf("js", "mjs", "json", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "html", "htm", "php", "mp4", "mkv", "m3u8", "ts", "txt", "xml", "srt", "vtt")

    /**
     * A whole literal like `"div.content"` or `"a.btn"` is a CSS selector, not a host: cheerio-based
     * scrapers are full of them. Only checked for the bare-literal shape; a URL's host is never a tag.
     */
    private val HTML_TAGS = setOf(
        "a", "abbr", "article", "aside", "b", "body", "button", "div", "dl", "dt", "dd", "em", "figure", "footer",
        "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "i", "iframe", "img", "input", "label", "li",
        "link", "main", "meta", "nav", "ol", "option", "p", "pre", "script", "section", "select", "source", "span",
        "strong", "table", "tbody", "td", "th", "thead", "tr", "track", "ul", "video",
    )

    /**
     * Stops at the first quote, whitespace or backslash: in minified code an unbounded `\S*` ran on
     * past the real URL's closing quote into whatever token followed it.
     */
    private val DOMAINS_JSON_URL = Regex("""https://raw\.githubusercontent\.com/[^\s'"`\\<>]*?domains?\.json""", RegexOption.IGNORE_CASE)

    /** Every plausible host named in [source]'s string literals, lowercased, de-duplicated, in first-seen order. */
    fun extractHosts(source: String): List<String> =
        JsStringLiterals.of(source)
            .flatMap { hostsInLiteral(it) }
            .distinct()

    /**
     * The hosts that look like the scraper's OWN site rather than a hoster/CDN it resolves embeds on,
     * in first-seen order. Two shapes count, both read off the raw source (the caller keeps only
     * what [extractHosts] also found, so a match inside a comment never adds anything):
     *  - a string assigned to a name that says it's an address -- one containing `url`, `host`,
     *    `domain`, `base`, `site` or `api` (`const BASE_URL = "https://areshd.com"`, `MAIN_URL:`,
     *    `apiUrl = "…"`, `DOMAIN = "pelis182.net"`);
     *  - a template URL whose QUERY carries an expression (`` `https://cuevana.unbuendato.com/?id=${id}` ``,
     *    `` `https://site/?s=${query}` ``): the site's own search/lookup, while embed hosts are
     *    usually built on a path (`/e/${id}`).
     * Only used to rank candidates when a scraper names more than the 20 hosts a manifest can declare.
     */
    fun primaryHosts(source: String): List<String> {
        val found = ArrayList<String>()
        ADDRESS_ASSIGNMENT.findAll(source).forEach { found += hostsInLiteral(it.groupValues[1]) }
        QUERY_TEMPLATE_URL.findAll(source).forEach { m -> normalize(m.groupValues[1])?.let { found += it } }
        return found.distinct()
    }

    private val ADDRESS_ASSIGNMENT = Regex(
        """(?<![A-Za-z0-9_$])(?=[A-Za-z_$])[A-Za-z0-9_$]*?(?:url|host|domain|base|site|api)[A-Za-z0-9_$]*\s*[:=]\s*["'`]([^"'`\n]+)["'`]""",
        RegexOption.IGNORE_CASE,
    )
    private val QUERY_TEMPLATE_URL = Regex("""`https?://([a-z0-9.-]+)[^`\s]*\?[^`\s]*\$\{""", RegexOption.IGNORE_CASE)

    /**
     * A candidate [ManifestParser] would accept as a declared host AND that looks like a real public
     * domain: its last label is letters (or punycode) and isn't a file extension.
     */
    fun isPlausibleHost(host: String): Boolean {
        if (!HostRules.isValidPattern(host) || host.startsWith("*.")) return false
        val tld = host.substringAfterLast('.')
        if (tld in NOISE_SUFFIXES) return false
        return tld.startsWith("xn--") || (tld.length >= 2 && tld.all { it in 'a'..'z' })
    }

    private fun hostsInLiteral(literal: String): List<String> {
        val found = ArrayList<String>()
        URL_HOST.findAll(literal).forEach { m ->
            normalize(m.groupValues[1])?.let { found += it }
        }
        val whole = literal.trim()
        if (BARE_HOST.matches(whole) && whole.substringBefore('.').lowercase() !in HTML_TAGS) {
            normalize(whole)?.let { found += it }
        }
        return found
    }

    private fun normalize(raw: String): String? =
        raw.lowercase().trim('.').takeIf { isPlausibleHost(it) }

    /**
     * A `raw.githubusercontent.com` URL that looks like a remote list of mirror domains: several
     * active Nuvio scrapers (4khdhub, uhdmovies, moviesdrive) load one because their real site's
     * domain rotates (spec §5.3). `raw.githubusercontent.com` is already one of Kino's ten fixed hosts,
     * so fetching this ONE url during conversion -- never the domains it lists -- is safe before the
     * plugin exists.
     */
    fun findDomainsJsonUrl(source: String): String? = DOMAINS_JSON_URL.find(source)?.value

    /**
     * A fetched `domains.json` body. Two real shapes:
     *  - an ARRAY of hostnames or URLs (one scraper's own mirror list): every entry is [NuvioRemoteHosts.preferred];
     *  - an OBJECT of `name -> URL` shared by a whole repo's scrapers (`phisher98/TVVVV`'s real file:
     *    `{"moviesdrive": "https://new4.moviesdrive.christmas", "4khdhub": "https://4khdhub.one", …}`,
     *    ~50 entries). Only the entries whose key [scraperSource] actually names (`data.moviesdrive`,
     *    `domains["4khdhub"]`, `const { moviesdrive } = data`) are kept, as preferred; the rest -- other scrapers' domains -- are
     *    dropped. Only when NO key is named at all (no telling which one it reads) do they all stay,
     *    as [NuvioRemoteHosts.others].
     * A value that's a URL contributes only its host; anything that fails [isPlausibleHost] is dropped.
     * Unparseable text parses to [NuvioRemoteHosts.NONE].
     */
    fun parseDomainsJson(text: String, scraperSource: String): NuvioRemoteHosts = runCatching {
        when (val v = JSONTokener(text).nextValue()) {
            is JSONArray -> NuvioRemoteHosts(
                preferred = (0 until v.length()).mapNotNull { (v.opt(it) as? String)?.let(::hostOfEntry) }.distinct(),
                others = emptyList(),
            )
            is JSONObject -> {
                val preferred = ArrayList<String>()
                val others = ArrayList<String>()
                for (key in v.keys()) {
                    val host = (v.opt(key) as? String)?.let(::hostOfEntry) ?: continue
                    if (keyIsReferenced(key, scraperSource)) preferred += host else others += host
                }
                // Once the scraper names ANY key, the rest are other scrapers' domains: declaring
                // them would only crowd the consent sheet and fill the host cap, which is what
                // leaves room for reactive approval later.
                if (preferred.isNotEmpty()) NuvioRemoteHosts(preferred.distinct(), emptyList())
                else NuvioRemoteHosts(emptyList(), others.distinct())
            }
            else -> NuvioRemoteHosts.NONE
        }
    }.getOrDefault(NuvioRemoteHosts.NONE)

    /** `https://new4.moviesdrive.christmas/` -> `new4.moviesdrive.christmas`; a bare `4khdhub.one` stays as is. */
    private fun hostOfEntry(entry: String): String? {
        val s = entry.trim()
        val authority = if ("://" in s) s.substringAfter("://") else s
        val host = authority.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        return normalize(host)
    }

    /**
     * Whether [source] reads [key] off the domains object: `data.key`, `data["key"]` (any quoted
     * `"key"` at all), or a flat object destructuring declaration that names it as a property --
     * `const { key } = data`, `let { key: url = "…" } = await res.json()`, minified `var{key:e}=t`.
     * In `{ other: key }` the name is only the local variable `other` lands in, so it doesn't count.
     */
    private fun keyIsReferenced(key: String, source: String): Boolean {
        if (key.isEmpty()) return false
        val k = Regex.escape(key)
        if (Regex("""(?:\.\s*$k(?![A-Za-z0-9_$])|['"`]$k['"`])""").containsMatchIn(source)) return true
        return DESTRUCTURING.findAll(source).any { m ->
            m.groupValues[1].split(',').any { part ->
                part.substringBefore(':').substringBefore('=').trim() == key
            }
        }
    }

    /** `const|let|var { a, b: c, d = 1 } =` -- one flat pattern (a nested `{` isn't matched); group 1 is its inside. */
    private val DESTRUCTURING = Regex("""(?<![A-Za-z0-9_$])(?:const|let|var)\s*\{([^{}]*)\}\s*=(?![=>])""")
}

/**
 * A small JavaScript lexer that yields the CONTENTS of every string literal in a source file, skipping
 * comments and regex literals (so an apostrophe in `// don't` or in `/'/g` never flips what counts as
 * "inside a string"). A template literal's `${…}` expressions are lexed as code in turn (their own
 * nested literals are yielded separately); the template itself is yielded with each expression replaced
 * by a NUL, so neither a URL nor a bare host can run through one. A `'`/`"` literal that hits a raw
 * newline ends there (JS forbids that), which bounds the damage of any mis-lexing to one line.
 */
internal object JsStringLiterals {
    private const val EXPR_MARK = '\u0000'
    private const val REGEX_PRECEDERS = "(,=:[!&|?{};+-*%<>~^"
    private val REGEX_KEYWORDS = setOf("return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "instanceof", "yield", "await")

    fun of(source: String): List<String> {
        val out = ArrayList<String>()
        Lexer(source, out).code(0, untilCloseBrace = false)
        return out
    }

    private class Lexer(val s: String, val out: MutableList<String>) {
        val n = s.length

        /** Lexes code from [start]; with [untilCloseBrace], returns right after the `}` closing a template's `${`. */
        fun code(start: Int, untilCloseBrace: Boolean): Int {
            var i = start
            var depth = 0
            var prev = ' ' // last significant (non-space, non-comment) char, ' ' = none yet
            var prevEnd = -1 // index just past it, to read back a keyword before a `/`
            while (i < n) {
                val c = s[i]
                when {
                    c.isWhitespace() -> { i++; continue }
                    c == '/' && i + 1 < n && s[i + 1] == '/' -> {
                        i = s.indexOf('\n', i).let { if (it < 0) n else it }
                        continue
                    }
                    c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                        i = s.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                        continue
                    }
                    c == '/' && regexAllowed(prev, prevEnd) -> i = regex(i)
                    c == '\'' || c == '"' -> i = quoted(i, c)
                    c == '`' -> i = template(i)
                    c == '{' -> { depth++; i++ }
                    c == '}' -> {
                        if (untilCloseBrace && depth == 0) return i + 1
                        depth--; i++
                    }
                    else -> i++
                }
                prev = c
                prevEnd = i
            }
            return n
        }

        private fun regexAllowed(prev: Char, prevEnd: Int): Boolean {
            if (prev == ' ') return true
            if (prev in REGEX_PRECEDERS) return true
            if (prev == '}') return true
            if (prev.isLetter() || prev == '_' || prev == '$') {
                var b = prevEnd
                while (b > 0 && (s[b - 1].isLetterOrDigit() || s[b - 1] == '_' || s[b - 1] == '$')) b--
                return s.substring(b, prevEnd) in REGEX_KEYWORDS
            }
            return false
        }

        private fun quoted(start: Int, quote: Char): Int {
            val sb = StringBuilder()
            var i = start + 1
            while (i < n) {
                val c = s[i]
                when {
                    c == '\\' -> { if (i + 1 < n && s[i + 1] != '\n') sb.append(s[i + 1]); i += 2 }
                    c == quote -> { out += sb.toString(); return i + 1 }
                    c == '\n' -> { out += sb.toString(); return i }
                    else -> { sb.append(c); i++ }
                }
            }
            out += sb.toString()
            return n
        }

        private fun template(start: Int): Int {
            val sb = StringBuilder()
            var i = start + 1
            while (i < n) {
                val c = s[i]
                when {
                    c == '\\' -> { if (i + 1 < n) sb.append(s[i + 1]); i += 2 }
                    c == '`' -> { out += sb.toString(); return i + 1 }
                    c == '$' && i + 1 < n && s[i + 1] == '{' -> { sb.append(EXPR_MARK); i = code(i + 2, untilCloseBrace = true) }
                    else -> { sb.append(c); i++ }
                }
            }
            out += sb.toString()
            return n
        }

        /** Skips a regex literal (a `/` inside `[...]` doesn't end it); a raw newline ends it too. */
        private fun regex(start: Int): Int {
            var i = start + 1
            var inClass = false
            while (i < n) {
                when (s[i]) {
                    '\\' -> i++
                    '[' -> inClass = true
                    ']' -> inClass = false
                    '/' -> if (!inClass) return i + 1
                    '\n' -> return i
                }
                i++
            }
            return n
        }
    }
}
