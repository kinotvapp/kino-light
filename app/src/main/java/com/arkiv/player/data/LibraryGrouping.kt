package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * A library series seen as ONE single thing, with every acquisition that makes it up. [primary]
 * is the one shown and opened by default; [members] is all of them (including [primary]), which
 * is what feeds the detail screen's source picker.
 */
data class LibraryGroup(
    val key: String,
    val primary: LibraryRow,
    val members: List<LibraryRow>,
) {
    /** How many distinct acquisitions are behind the card (1 = not grouped). */
    val sourceCount: Int get() = members.size

    /**
     * The most complete acquisition's chapter count, NOT the sum of all of them: the sources are
     * alternative copies of the SAME series, not disjoint content, so summing them inflates the
     * number (6 Naruto acquisitions summed to 794 ep. for a series of ~220). Each source's own
     * detail is already visible separately in the "Sources" chips; nothing is lost here.
     */
    val episodeCount: Int get() = members.maxOf { it.episodeCount }

    /**
     * New chapters since the last time the detail screen was opened, for the card's badge.
     *
     * It's the **maximum** across sources for the same reason as [episodeCount], not for
     * convenience: the acquisitions are alternative copies of the SAME series, not disjoint
     * content. If the same chapter shows up in both the archive copy and the web one, that's ONE
     * new chapter, not two -- summing them would lie the same way it summed to 794 episodes for a
     * series of 220.
     */
    val newEpisodes: Int get() = members.maxOf {
        com.arkiv.player.data.newcontent.NewEpisodeCounter.count(it.episodeCount, it.episodiosVistosEnLista)
    }
}

/**
 * Groups the library so a series entered from several sources (Magis, Ditu, or the legacy
 * web/torrent/archive sources from before this branch's pruning) becomes a single card.
 *
 * `identifier`s are prefixed by source ON PURPOSE so they don't collide in `items` (see
 * [SeriesItemIds]); that's fine for saving, but the home screen shouldn't show them separately.
 * This only joins them for DISPLAY: no row is touched or deleted, so sync never finds out and
 * this is reversible.
 */
object LibraryGrouping {

    /**
     * The key two items are joined by.
     *
     * Preference order, measured against the real library (2026-08-10):
     *  1. **Movies: never.** `artwork`'s `tmdbId` is resolved by searching by title, and for
     *     movies it gets it wrong -- it joins "Lego Batman" with "Batman (1966)" under
     *     `movie:324849`. Grouping them would be worse than the duplicate, so every movie is its
     *     own group.
     *  2. **`artwork`'s `tmdbId` with `tmdbType == "tv"`.** For series it does get it right (the 4
     *     Naruto ones under `tv:46260`, DAN DA DAN under `tv:240411`) and it's the ONLY thing that
     *     crosses different sources, because it doesn't depend on the identifier's prefix. Ranma
     *     1989 and the 2024 remake fall on different ids, so it doesn't merge them.
     *  3. **The item's own `tmdbId`**, which the gateway fills in by canonizing the title against
     *     TMDB. Comes after the artwork one because that one was already tried; it rescues rows
     *     whose title doesn't exist on TMDB and that the artwork step could therefore never
     *     resolve on its own.
     *  4. **The identifier's seriesId**, which is exact but only exists on `web:series:` and
     *     `torrent:series:`.
     *  5. The identifier itself: a group of one, i.e. what the app does today.
     */
    fun groupKeyOf(row: LibraryRow, artwork: ArtworkEntity?): String {
        // The canonical type overrides the "1 video = movie" heuristic: a standalone chapter has
        // a single video and with no `categoryOverride` falls in as a movie, which is exactly
        // what kept it from joining its series. Measured against the Fire TV's database: all five
        // Evangelion rows stayed separate even though they already had their tmdbId. If the
        // gateway verified against TMDB that the work is a series, it's a series.
        val canonTv = row.tmdbId?.takeIf { it > 0 && row.tipo == "tv" }
        if (row.isMovie) return canonTv?.let { "tv:$it" } ?: "item:${row.identifier}"
        val tvId = artwork?.tmdbId?.takeIf { artwork.tmdbType == "tv" }
        if (tvId != null) return "tv:$tvId"
        // Fallback: the `tmdbId` the gateway put on the ITEM by canonizing its title. Comes AFTER
        // the artwork one on purpose -- whatever groups today has to keep grouping the same way --
        // and it rescues exactly what the artwork can't: a standalone chapter saved with the
        // chapter's own title doesn't match any TMDB search, so `ensureArtwork` never resolves
        // anything for it and each row is left on its own card. The `> 0` isn't paranoia:
        // PocketBase's numeric field starts at 0, and grouping by "tv:0" would join the entire
        // un-canonized library into a single card.
        // `tipo != "movie"`: what we don't know keeps grouping as it does today, but an id the
        // gateway marked as a MOVIE doesn't join anything -- same reason movies never group.
        row.tmdbId?.takeIf { it > 0 && row.tipo != "movie" }?.let { return "tv:$it" }
        SeriesItemIds.seriesIdOrNull(row.identifier)?.let { return "series:$it" }
        return "item:${row.identifier}"
    }

    /**
     * Builds the groups preserving entry order by each group's most recent: grouping must not
     * reorder the list, which already arrives ordered by `addedAt DESC`. That order is the
     * TIEBREAKER: whoever shows the groups reorders them afterward by last-watched (see
     * `ArkivRepository.observeLibraryGroups` and `biblioteca.LibraryOrder`), and since Kotlin's
     * ordering is stable, two groups with the same recency keep this one.
     *
     * The representative is the one with the MOST chapters (more chapters = the most complete
     * acquisition; the one worth opening), and on a chapter-count tie, the most recent one.
     */
    fun group(rows: List<LibraryRow>, artwork: Map<String, ArtworkEntity>): List<LibraryGroup> =
        rows.groupBy { groupKeyOf(it, artwork[it.identifier]) }
            .map { (key, members) ->
                LibraryGroup(
                    key = key,
                    primary = members.maxWith(compareBy({ it.episodeCount }, { it.addedAt })),
                    members = members,
                )
            }
            .sortedByDescending { g -> g.members.maxOf { it.addedAt } }

    /**
     * The members to show for a route key ([TvDetailScreen]/`DetailScreen`), which may have
     * stopped being a live group's key: `ensureArtwork` runs in the background and can resolve a
     * tv `tmdbId` for the item WHILE the detail screen is open, the moment its group moves from
     * `item:<identifier>` to `tv:<tmdbId>` and the old key stops existing.
     *
     * Four cases, in order:
     *  1. [groupKey] is still a group's key: its members, most complete first.
     *  2. No, but it's an `item:<identifier>` key and THAT identifier now lives inside ANOTHER
     *     group: that group's members. Without this step, the detail screen is left pointing at a
     *     ghost key and disappears (black screen) the moment the artwork resolves.
     *  3. No, but it's a `series:<seriesId>` key and THAT seriesId now lives inside ANOTHER group
     *     (same problem as step 2, but for series): a `series:<X>` is NEVER equal to an identifier
     *     (which come prefixed `web:series:`/`torrent:series:`, see [SeriesItemIds]), so without
     *     this step 4 never finds it and the detail screen is left with a black screen the moment
     *     the artwork resolves a tv `tmdbId` for the item and its group moves from
     *     `series:<id>` to `tv:<id>`.
     *  4. None of the above: the standalone row with that identifier (or empty if not even that
     *     exists).
     *
     * Steps 2 and 3 ON PURPOSE only apply to `item:`/`series:` keys, not to a bare identifier.
     * "Continue watching", the long-press menu and the phone's detail screen navigate with a
     * specific row's bare identifier assuming "this exact row"; if they also followed the trail to
     * the group, an identifier that ended up part of a group (e.g. because another source of the
     * same series already had a tmdbId) would switch their item on them without the person having
     * asked for it. `series:<seriesId>`, on the other hand, ONLY ever arrives from `TvHomeScreen`
     * navigating with the group's key (`onOpenItem(it.key)`), a caller that DOES want to follow
     * the trail -- same as `item:`.
     */
    fun resolveMembers(
        groupKey: String,
        groups: List<LibraryGroup>,
        rows: List<LibraryRow>,
    ): List<LibraryRow> {
        groups.firstOrNull { it.key == groupKey }?.let {
            return it.members.sortedByDescending { m -> m.episodeCount }
        }
        if (groupKey.startsWith("item:")) {
            val identifier = groupKey.removePrefix("item:")
            groups.firstOrNull { g -> g.members.any { m -> m.identifier == identifier } }
                ?.let { return it.members.sortedByDescending { m -> m.episodeCount } }
            return rows.filter { it.identifier == identifier }
        }
        if (groupKey.startsWith("series:")) {
            val seriesId = groupKey.removePrefix("series:")
            groups.firstOrNull { g -> g.members.any { m -> SeriesItemIds.seriesIdOrNull(m.identifier) == seriesId } }
                ?.let { return it.members.sortedByDescending { m -> m.episodeCount } }
        }
        return rows.filter { it.identifier == groupKey }
    }

    /** Grace window before retrying TMDB for an item that came up with no match. */
    private const val ARTWORK_RETRY_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * Whether `ensureArtwork` has to ask TMDB for this item's artwork again.
     *
     * NO, if it already has [ArtworkEntity.tmdbId] (resolved) or already has backdrops even with
     * no tmdbId -- artwork another source put there, like the portal's backdrop
     * `ArkivRepository.addMagisSource` saves. That row is never touched again: if it were
     * retried, a Magis item with no TMDB match would lose its real backdrop (overwritten by a
     * `"[]"`) every 7 days, forever, with the person doing nothing.
     *
     * Also not if it's empty (no tmdbId or backdrops) but recent: that way a title TMDB doesn't
     * know isn't queried on every launch. Yes, if it's empty and the window has already passed:
     * then it's worth retrying in case the earlier dirty title now matches (see
     * `cleanTitleForSearch`).
     */
    fun shouldRefetchArtwork(existing: ArtworkEntity?, now: Long): Boolean {
        if (existing == null) return true
        if (existing.tmdbId != null) return false
        if (existing.backdrops.isNotEmpty()) return false
        return now - existing.fetchedAt >= ARTWORK_RETRY_WINDOW_MS
    }

    /**
     * The two flows combined. Lives here (and not in the repository) so it can be tested without
     * Room: the repository only wires it up with its DAOs.
     */
    fun groupsFlow(
        rows: Flow<List<LibraryRow>>,
        artwork: Flow<Map<String, ArtworkEntity>>,
    ): Flow<List<LibraryGroup>> =
        combine(rows, artwork) { r, a -> group(r, a) }
}
