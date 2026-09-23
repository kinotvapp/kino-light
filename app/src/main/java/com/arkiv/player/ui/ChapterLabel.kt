package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.MagisEntities
import com.arkiv.player.data.EncodedNumbering
import com.arkiv.player.data.model.Episode

/**
 * How a chapter is named and where you're at, in both the TV detail screen and the phone one.
 *
 * Lives here, shared, because the two screens HAVE TO say the same thing: this used to be a
 * private function of the TV detail screen, and the phone one didn't number anything.
 */
object ChapterLabel {

    /**
     * "T1 · E5" / "E5", from the loose values.
     *
     * The part that isn't known is omitted instead of made up: "E5" alone is preferable to a
     * "T1 · E5" pointing at the wrong chapter. The preference order matters: `episode` wins even
     * without a `season` -- a Magis chapter saved without season context (`MagisEntities.build`,
     * loose chapter) ends up with `season = null`, even though the ones that do have it
     * (`buildSeason`) already number "T1 · E5" -- and only after that does it fall back to
     * `orderIndex`.
     *
     * The `orderIndex` doesn't mean the same thing for every row: torrent and web (both removed
     * from this branch, only legacy rows saved before the pruning still carry their `itemId`
     * prefix) packed the season/episode numbering into it, while archive.org (also removed) used
     * it as a plain 0..N-1 correlative. [EncodedNumbering] tells them apart by looking at the
     * row's source, not the size of the number — looking at the number is what broke season 0,
     * where special 3 showed up as "E4". That's why it needs [itemId] and [section]. When the
     * source doesn't encode, `orderIndex` is the correlative and is shown 1-based.
     *
     * Takes the loose values instead of an [Episode] because the home hero has them that way, from
     * a "Continue watching" row (`ContinueRow`), not as a model. The rule lives in ONE place and
     * the three surfaces -- the two detail screens and the hero -- share it.
     */
    fun number(season: Int?, episode: Int?, orderIndex: Int, itemId: String, section: String): String {
        if (season != null && episode != null) return "T$season · E$episode"
        if (episode != null) return "E$episode"
        val coded = EncodedNumbering.coordinates(itemId, section, orderIndex)
        if (coded != null) return "T${coded.first} · E${coded.second}"
        // orderIndex doesn't mean the same thing for every source: archive.org and torrent packs
        // (both removed from this branch; this only matters for legacy rows saved before the
        // pruning) distributed it with mapIndexed (0..N-1), but Magis stores the chapter number
        // as-is (`MagisEntities.chapterEntity`: `orderIndex = number`). Adding one to a Magis item
        // shifted the whole chapter: Dragon Ball's e126 showed up as "E127" in the home hero, in
        // the detail screen and on the "Play" button. Chapters saved by the current version carry
        // `episode` and take the branch above without reaching here; the old ones have it null
        // and are the ones this count depends on.
        if (itemId.startsWith(MagisEntities.PREFIX)) return "E$orderIndex"
        return "E${orderIndex + 1}"
    }

    /** "T1 · E5" / "E5" for an episode already loaded as a model. See the loose-values version. */
    fun number(ep: Episode): String = number(ep.season, ep.episode, ep.orderIndex, ep.itemId, ep.section)

    /**
     * "T1 · E5  ·  La conspiración": the number and, NEXT TO IT, the chapter's real name.
     *
     * The number is never replaced by the name. It identifies the chapter that's about to play and
     * stays the reliable data even when the TMDB match is off for that season; the name is what
     * gets added, not what replaces it. Without this rule, the phone detail row showed only
     * "Panzy" where it used to say "E5  Daima T1_5" and there was no way to tell which was which.
     *
     * With no name resolved ([name] null or blank) it falls back to [Episode.displayName], which
     * is the file name and, in sources that number (Magis, packs), already carries the number inside.
     */
    fun withName(ep: Episode, name: String?): String {
        val trimmed = name?.trim().orEmpty()
        return if (trimmed.isEmpty()) ep.displayName else "${number(ep)}  ·  $trimmed"
    }

    /**
     * "Vas en E5  ·  20 episodios", or "20 episodios" if you haven't started yet.
     *
     * [unit] is "episodios" (TV) or "videos" (phone), which is what each screen calls it today.
     */
    fun progressSummary(detail: ItemDetail, unit: String): String {
        val total = "${detail.episodes.size} $unit"
        if (detail.progress.isEmpty() || detail.episodes.size <= 1) return total
        val current = detail.resumeEpisode ?: return total
        return "Vas en ${number(current)}  ·  $total"
    }

    /**
     * "Reproducir" or "Reproducir E5".
     *
     * Names the chapter the button will actually play ([ItemDetail.resumeEpisode]), which isn't
     * always the one you're watching: if you finished E5, it plays E6. On a movie (a single
     * episode) there's nothing to number, and neither is there with no progress yet -- checking
     * whether `resumeEpisode` is null isn't enough there, because as soon as there are episodes it
     * ALWAYS returns one (the first).
     */
    fun playButtonLabel(detail: ItemDetail): String {
        if (detail.episodes.size <= 1 || detail.progress.isEmpty()) return "Reproducir"
        val current = detail.resumeEpisode ?: return "Reproducir"
        return "Reproducir ${number(current)}"
    }

    /** Below this there's nothing useful left to say about the remaining time. */
    private const val MINIMUM_REMAINING_MS = 60_000L

    /**
     * The chapter's data line for the home hero: "T1 · E5  ·  La conspiración  ·  te faltan 12
     * min".
     *
     * **Each part is omitted when unknown, never made up.** The hero is the first thing read on
     * the screen, so a made-up value there is worse than a missing one:
     *  - the **name** depends on TMDB having matched that chapter (`episode_still`);
     *  - the **time** depends on knowing the duration, and on Magis the probe takes a while:
     *    `durationMs` arrives as 0 until it resolves. It's also omitted under a minute from the
     *    end, where "te falta 1 min" doesn't help decide anything.
     *
     * On a **movie** there's no number: "Continue watching" also brings back half-watched movies,
     * and numbering them would leave an absurd "E1" under the title. There, only the time is left,
     * which is exactly what you want to know about a movie you've started.
     *
     * Returns "" when no part is left (a movie with no known duration); whoever uses it decides
     * what to do with that -- both screens simply don't draw the line.
     */
    fun heroLine(
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        orderIndex: Int,
        itemId: String,
        section: String,
        name: String?,
        positionMs: Long,
        durationMs: Long,
    ): String {
        val parts = mutableListOf<String>()
        if (!isMovie) {
            parts += number(season, episode, orderIndex, itemId, section)
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        val remaining = durationMs - positionMs
        if (durationMs > 0 && remaining >= MINIMUM_REMAINING_MS) {
            // formatRuntime already knows to switch to hours above 60 minutes ("1 h 26 min"):
            // without this, a movie just started would say "te faltan 118 min".
            parts += "te faltan ${formatRuntime(remaining / 1000.0)}"
        }
        return parts.joinToString("  ·  ")
    }
}
