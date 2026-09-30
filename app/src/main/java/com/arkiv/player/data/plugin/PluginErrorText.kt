package com.arkiv.player.data.plugin

/**
 * What the person reads for an error a plugin's SCRIPT threw ([PluginThrownException], a typed
 * error with a code Kino doesn't know, a Nuvio scraper's reported error): one short Spanish sentence,
 * never the engine's raw text. That raw text ("Error: [StreamWish2] No se pudo … https://…\n    at
 * <anonymous> (plugin.js:1226)\n …") is for the log only, where it stays whole ([PluginCalls] logs
 * it next to the sentence; the exception keeps it as its cause).
 *
 * The sentence is "<plugin> <what failed>" ([lead], by function: "no pudo obtener el video" for
 * `resolve`), plus ": <reason>" when the plugin's message cleans up ([reason]) into something a
 * person can read. At most [MAX_CHARS] characters.
 *
 * The cleaning rule, in order:
 * 1. Only the first line: the engine appends the stack after the first newline (and a stack frame
 *    "at f (plugin.js:12)" on that same line is cut too).
 * 2. Leading error-type prefixes ("Error:", "TypeError:", "Uncaught") and "[Tag]" prefixes (a
 *    scraper's "[StreamWish2]") are stripped, repeatedly, in any order.
 * 3. URLs are removed, spaces collapsed, and a ":" "," ";" or "-" left dangling at the end dropped.
 * 4. What is left is shown only if it reads as words: at least two words of two or more letters,
 *    none of the characters code is made of (`{}()[]<>=;$_\`"|\\/`), and none of JavaScript's own
 *    words (undefined, null, NaN, function, prototype, "is not defined", "unexpected token") -- "cannot read property
 *    'x' of undefined" tells the person nothing. Otherwise there is no reason, just the lead.
 * 5. It is cut at a word boundary with "…" so the whole sentence fits in [MAX_CHARS].
 */
object PluginErrorText {
    const val MAX_CHARS = 140

    /** The sentence for an untyped script error of [function] of the plugin called [name]. */
    fun of(name: String, function: String, raw: String?): String {
        val lead = "$name ${lead(function)}"
        val reason = reason(raw) ?: return lead
        val room = MAX_CHARS - lead.length - 2
        if (room < MIN_REASON_CHARS) return lead
        return "$lead: ${fit(lowerFirst(reason), room)}"
    }

    /** What failed, by the function that failed. */
    fun lead(function: String): String = when (function) {
        "resolve" -> "no pudo obtener el video"
        "episodes" -> "no pudo cargar los capítulos"
        "search" -> "no pudo buscar"
        "liveCategories", "liveChannels" -> "no pudo cargar los canales"
        "liveGuide" -> "no pudo cargar la guía"
        else -> "no pudo cargar el contenido"
    }

    /** [raw] cleaned by the rule above, or null when nothing readable is left. Not truncated. */
    fun reason(raw: String?): String? {
        var text = raw?.take(PluginRuntime.MAX_ERROR_CHARS)?.lineSequence()?.firstOrNull() ?: return null
        FRAME.find(text)?.let { text = text.substring(0, it.range.first) }
        while (true) {
            val stripped = text.trim().replaceFirst(PREFIX, "")
            if (stripped == text) break
            text = stripped
        }
        text = text.replace(URL, " ")
            .replace(SPACES, " ")
            .trim()
            .trimEnd(':', ',', ';', '-', '–')
            .trim()
        if (text.isEmpty() || CODE_CHARS.containsMatchIn(text) || CODE_WORDS.containsMatchIn(text)) return null
        if (WORD.findAll(text).count() < 2) return null
        return text
    }

    private fun fit(text: String, room: Int): String {
        if (text.length <= room) return text
        val cut = text.take(room - 1)
        val atWord = cut.substringBeforeLast(' ', cut).takeIf { it.length >= room / 2 } ?: cut
        return atWord.trimEnd(' ', ',', ';', ':', '.', '-') + "…"
    }

    /** "No se pudo…" reads as "…el video: no se pudo…"; an acronym ("HLS …") keeps its case. */
    private fun lowerFirst(s: String): String =
        if (s.length > 1 && s[0].isUpperCase() && !s[1].isUpperCase()) s[0].lowercaseChar() + s.substring(1) else s

    private const val MIN_REASON_CHARS = 20

    private val FRAME = Regex("\\s+at\\s+\\S*\\s*\\([^)]*:\\d+[^)]*\\)")
    private val PREFIX = Regex("^(?:Uncaught\\b\\s*|(?:[A-Za-z_$][\\w$.]*)?(?:Error|Exception)\\s*:\\s*|\\[[^\\]\\n]{0,40}\\]\\s*)")
    private val URL = Regex("\\b(?:https?|wss?|ftp)://\\S+", RegexOption.IGNORE_CASE)

    private val SPACES = Regex("\\s+")
    private val CODE_CHARS = Regex("[{}()\\[\\]<>=;\$_`\"|\\\\/]")
    private val CODE_WORDS = Regex("\\b(?:undefined|null|NaN|function|prototype|is not defined|unexpected token)\\b")
    private val WORD = Regex("\\p{L}{2,}")
}
