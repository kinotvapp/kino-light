package com.arkiv.player.ui

/** Readable duration from seconds: "1 h 26 min", "45 min", "0 min". */
fun formatRuntime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

/** Secondary label of a library card, based on its type. */
fun libraryMeta(isMovie: Boolean, durationSeconds: Double, episodeCount: Int): String = when {
    !isMovie -> "$episodeCount episodios"
    else -> formatRuntime(durationSeconds)
}

private val HtmlTag = Regex("<[^>]*>")
private val Whitespace = Regex("\\s+")

/**
 * Synopsis ready to render. AniList returns HTML (`<br>`, `<i>`) with raw line breaks; TMDB
 * overviews go through here too, though they already arrive cleaner. Returns "" when nothing is
 * left, so the call site falls back to its own default with `ifBlank`.
 */
fun plainSynopsis(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // Tags are stripped BEFORE decoding entities: the other way around, a literal "&lt;b&gt;"
    // would become "<b>" and the strip would eat text the author wrote on purpose.
    // With a space, not "": a "<br>" between words has to leave a separator.
    return raw.replace(HtmlTag, " ")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        // "&amp;" goes last: if it were first, "&amp;lt;" would decode twice and end up as "<"
        // instead of the literal "&lt;" the author wrote.
        .replace("&amp;", "&")
        .replace(Whitespace, " ")
        .trim()
}

/**
 * Hero subtitle: the title's synopsis, or [fallback] when it doesn't work.
 *
 * "Doesn't work" includes the case that looks odd and isn't: the description **being** the
 * title. This check's reason is historical -from when archive.org (a source removed in this
 * branch's pruning) was a candidate: whoever uploaded the file tended to repeat the name in the
 * description ("Night Of The Living Dead 1990" → "Night of the living dead 1990")-, but the check
 * still applies to any source whose description happens to equal the title: rendering it below
 * would give exactly the duplication this subtitle exists to avoid.
 *
 * Only equality is discarded, not a prefix: a synopsis that opens with the title
 * ("Avatar Aang, the last Airbender of the world, finds out…") is legitimate and kept.
 */
fun heroSubtitle(title: String, description: String?, fallback: String): String {
    val synopsis = plainSynopsis(description)
    return if (synopsis.equals(title.trim(), ignoreCase = true)) fallback else synopsis.ifBlank { fallback }
}

/**
 * Fallback for the hero subtitle in "Continue watching" when the item has no synopsis: the
 * episode's label WITHOUT the series title, which is already above in the hero.
 *
 * Returns "" when what would be left is the repeated title -- the movie case, where
 * `displayName` is directly the file's clean title.
 */
fun heroFallback(itemTitle: String, displayName: String): String {
    val title = itemTitle.trim()
    val prefix = "$title · "
    // If the label was formatted with a different showTitle (the title was edited later), there's
    // no prefix to strip and it's left as-is: better too much than eating text on a partial match.
    val rest = displayName.let {
        if (it.startsWith(prefix, ignoreCase = true)) it.substring(prefix.length) else it
    }.trim()
    // Case-insensitive: saved titles and files' cleanName() often differ in capitalization, and a
    // duplication that slips through over a capital letter is exactly the bug this guards against.
    return if (rest.equals(title, ignoreCase = true)) "" else rest
}
