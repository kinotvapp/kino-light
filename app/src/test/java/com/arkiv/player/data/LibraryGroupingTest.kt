package com.arkiv.player.data

import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.LibraryRow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Library grouping: the SAME series entered through several sources has to give ONE card. The
 * cases are the ones measured against the Fire TV's real DB on 2026-08-10 (see the plan).
 */
class LibraryGroupingTest {

    private fun row(
        id: String,
        title: String,
        eps: Int,
        source: String = "web",
        category: String? = "series",
        addedAt: Long = 0L,
        tmdbId: Int? = null,
        tipo: String? = null,
    ) = LibraryRow(
        identifier = id,
        title = title,
        description = null,
        thumbnailUrl = "",
        episodeCount = eps,
        durationSeconds = 0.0,
        addedAt = addedAt,
        categoryOverride = category,
        source = source,
        tmdbId = tmdbId,
        tipo = tipo,
    )

    private fun art(id: String, tmdbId: Int?, type: String?) =
        ArtworkEntity(itemId = id, tmdbId = tmdbId, tmdbType = type, backdropsJson = "[]", fetchedAt = 0L)

    @Test
    fun `a series with a resolved tv tmdbId groups by that id`() {
        val a = row("web:series:tt30217403", "DAN DA DAN", 24)
        val b = row("web:series:anilist171018", "DAN DA DAN", 1)
        val artwork = mapOf(
            a.identifier to art(a.identifier, 240411, "tv"),
            b.identifier to art(b.identifier, 240411, "tv"),
        )
        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(a, artwork[a.identifier]))
        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(b, artwork[b.identifier]))
    }

    /** The artwork resolver confuses distinct movies (Lego Batman vs Batman 1966): never group. */
    @Test
    fun `movies never group even if they share a tmdbId`() {
        val a = row("torrent:aaa", "Lego Batman: la película", 1, source = "torrent", category = null)
        val b = row("torrent:bbb", "Batman: La película (1966)", 1, source = "torrent", category = null)
        val artA = art(a.identifier, 324849, "movie")
        val artB = art(b.identifier, 324849, "movie")
        assertEquals("item:torrent:aaa", LibraryGrouping.groupKeyOf(a, artA))
        assertEquals("item:torrent:bbb", LibraryGrouping.groupKeyOf(b, artB))
    }

    /** With no artwork resolved it falls to the identifier's seriesId, which is exact. */
    @Test
    fun `with no tmdbId it falls to the identifier's seriesId`() {
        val r = row("web:series:tt0409591", "Naruto", 300)
        assertEquals("series:tt0409591", LibraryGrouping.groupKeyOf(r, art(r.identifier, null, null)))
        assertEquals("series:tt0409591", LibraryGrouping.groupKeyOf(r, null))
    }

    /** With none of the above, the item is its own group (today's behavior). */
    @Test
    fun `with no tmdbId or seriesId the item is left alone`() {
        val r = row("alfa:series:jkanime:3f7d7ee2", "Naruto", 1, source = "alfa")
        assertEquals("item:alfa:series:jkanime:3f7d7ee2", LibraryGrouping.groupKeyOf(r, null))
    }

    /** Different series with the same title don't merge: Ranma 1989 and the 2024 remake. */
    @Test
    fun `two same-named series with different tmdbIds stay separate`() {
        val old = row("web:series:tt0096686", "Ranma ½", 161)
        val new_ = row("web:series:tt32766897", "Ranma1/2", 24)
        val groups = LibraryGrouping.group(
            listOf(old, new_),
            mapOf(
                old.identifier to art(old.identifier, 33840, "tv"),
                new_.identifier to art(new_.identifier, 240909, "tv"),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `the group's representative is the one with the most chapters`() {
        val few = row("web:series:anilist171018", "DAN DA DAN", 1, addedAt = 200)
        val many = row("web:series:tt30217403", "DAN DA DAN", 24, addedAt = 100)
        val groups = LibraryGrouping.group(
            listOf(few, many),
            mapOf(
                few.identifier to art(few.identifier, 240411, "tv"),
                many.identifier to art(many.identifier, 240411, "tv"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("web:series:tt30217403", groups[0].primary.identifier)
        assertEquals(2, groups[0].sourceCount)
        // episodeCount is the maximum across sources (24), NOT the sum (25): they're alternative
        // copies of the same series, not disjoint content.
        assertEquals(24, groups[0].episodeCount)
    }

    /** The home's order is by the group's most recent member, so grouping doesn't reorder the row. */
    @Test
    fun `groups come out ordered by their most recent member`() {
        val old = row("web:series:tt1", "Vieja", 10, addedAt = 100)
        val new_ = row("web:series:tt2", "Nueva", 10, addedAt = 300)
        val groups = LibraryGrouping.group(listOf(old, new_), emptyMap())
        assertEquals(listOf("Nueva", "Vieja"), groups.map { it.primary.title })
    }

    /**
     * The combine of the two flows: if the artwork arrives AFTER the library (which is normal --
     * `ensureArtwork` goes out to the network), the group has to recompute on its own. Otherwise
     * the home screen is left with separate cards until the app reopens.
     */
    @Test
    fun `groups recompute when the artwork arrives`() = kotlinx.coroutines.runBlocking {
        val a = row("web:series:tt30217403", "DAN DA DAN", 24)
        val b = row("web:series:anilist171018", "DAN DA DAN", 1)
        val artwork = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ArtworkEntity>>(emptyMap())
        val flow = LibraryGrouping.groupsFlow(
            kotlinx.coroutines.flow.flowOf(listOf(a, b)),
            artwork,
        )
        val emissions = mutableListOf<List<LibraryGroup>>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            flow.collect { emissions += it }
        }
        assertEquals(2, emissions.last().size)
        artwork.value = mapOf(
            a.identifier to art(a.identifier, 240411, "tv"),
            b.identifier to art(b.identifier, 240411, "tv"),
        )
        assertEquals(1, emissions.last().size)
        job.cancel()
    }

    // --- resolveMembers ------------------------------------------------------------------

    /** (i) A live group key returns its members, most complete first. */
    @Test
    fun `resolveMembers with a live group key returns its members, most complete first`() {
        val few = row("web:series:anilist171018", "DAN DA DAN", 1)
        val many = row("web:series:tt30217403", "DAN DA DAN", 24)
        val groups = LibraryGrouping.group(
            listOf(few, many),
            mapOf(
                few.identifier to art(few.identifier, 240411, "tv"),
                many.identifier to art(many.identifier, 240411, "tv"),
            ),
        )
        val result = LibraryGrouping.resolveMembers("tv:240411", groups, groups.flatMap { it.members })
        assertEquals(listOf(many.identifier, few.identifier), result.map { it.identifier })
    }

    /**
     * (ii) Finding 2's REGRESSION: an `item:<identifier>` key the detail screen opened with when
     * the item didn't have a tmdbId yet. If the artwork resolves AFTERWARD (while the detail
     * screen is still open), the item moves to a `tv:` group, the `item:` key stops existing, and
     * without this fallback `observeGroupMembers` returned empty -> black screen in the detail.
     */
    @Test
    fun `resolveMembers with an item key whose item joined a tv group resolves to that group`() {
        val naruto = row("torrent:abc123", "Naruto — Pack", 220, source = "torrent")
        // The key the item had at the moment of navigating: no tmdbId yet.
        val routeGroupKey = LibraryGrouping.groupKeyOf(naruto, null)
        assertEquals("item:torrent:abc123", routeGroupKey)

        // The artwork resolves AFTERWARD: now the item (and a sibling from another source) live in tv:46260.
        val otherSource = row("web:series:tt0409591", "Naruto", 300)
        val groups = LibraryGrouping.group(
            listOf(naruto, otherSource),
            mapOf(
                naruto.identifier to art(naruto.identifier, 46260, "tv"),
                otherSource.identifier to art(otherSource.identifier, 46260, "tv"),
            ),
        )
        assertEquals(emptyList<LibraryGroup>(), groups.filter { it.key == routeGroupKey }) // the old key no longer exists

        val result = LibraryGrouping.resolveMembers(routeGroupKey, groups, groups.flatMap { it.members })
        assertEquals(setOf(naruto.identifier, otherSource.identifier), result.map { it.identifier }.toSet())
    }

    /**
     * (iii) The SAME regression as (ii) but for a `series:<seriesId>` key (2026-08-10 closeout
     * finding): unlike `item:<identifier>`, a `series:` is never equal to any identifier
     * (identifiers come prefixed `web:series:`/`torrent:series:`), so the earlier step 3
     * (`rows.filter { it.identifier == groupKey }`) never found it. Without this fallback,
     * TvHomeScreen navigates with `series:tt...`, the artwork resolves while the detail screen is
     * still open, the `series:` key stops existing and the detail screen is left with a black
     * screen.
     */
    @Test
    fun `resolveMembers with a series key whose item joined a tv group resolves to that group`() {
        val few = row("web:series:tt30217403", "DAN DA DAN", 24)
        // The key the item had at the moment of navigating: no tmdbId yet.
        val routeGroupKey = LibraryGrouping.groupKeyOf(few, null)
        assertEquals("series:tt30217403", routeGroupKey)

        // The artwork resolves AFTERWARD: now the item (and a sibling from another source) live in tv:240411.
        val many = row("torrent:series:tt30217403", "DAN DA DAN — Pack", 25, source = "torrent")
        val groups = LibraryGrouping.group(
            listOf(few, many),
            mapOf(
                few.identifier to art(few.identifier, 240411, "tv"),
                many.identifier to art(many.identifier, 240411, "tv"),
            ),
        )
        assertEquals(emptyList<LibraryGroup>(), groups.filter { it.key == routeGroupKey }) // the old key no longer exists

        val result = LibraryGrouping.resolveMembers(routeGroupKey, groups, groups.flatMap { it.members })
        assertEquals(setOf(few.identifier, many.identifier), result.map { it.identifier }.toSet())
    }

    /** (iv) A bare identifier (Continue watching / long-press menu) resolves to that single row. */
    @Test
    fun `resolveMembers with a bare identifier resolves to that single row`() {
        val standalone = row("torrent:xyz789", "Alguna película", 1, category = null)
        val groups = LibraryGrouping.group(listOf(standalone), emptyMap())
        val result = LibraryGrouping.resolveMembers("torrent:xyz789", groups, groups.flatMap { it.members })
        assertEquals(listOf("torrent:xyz789"), result.map { it.identifier })
    }

    /** (v) A key that matches nothing (neither group nor row) returns empty. */
    @Test
    fun `resolveMembers with an unknown key returns empty`() {
        val r = row("web:series:tt1", "Algo", 5)
        val groups = LibraryGrouping.group(listOf(r), emptyMap())
        val result = LibraryGrouping.resolveMembers("tv:99999999", groups, groups.flatMap { it.members })
        assertEquals(emptyList<LibraryRow>(), result)
    }

    // --- shouldRefetchArtwork --------------------------------------------------------------

    private val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L

    /** Already resolved (tmdbId != null): never retried, no matter how old. */
    @Test
    fun `shouldRefetchArtwork with a resolved tmdbId is false even if old`() {
        val existing = ArtworkEntity(itemId = "x", tmdbId = 240411, tmdbType = "tv", backdropsJson = "[]", fetchedAt = 0L)
        assertEquals(false, LibraryGrouping.shouldRefetchArtwork(existing, now = sevenDaysMs * 100))
    }

    /**
     * Finding 1's REGRESSION: no tmdbId but WITH backdrops (the Magis case, which saves the
     * portal's backdrop with tmdbId=null). Before the fix this was retried after 7 days and
     * `ensureArtwork` would overwrite the backdrop with a `"[]"` if TMDB found no match.
     */
    @Test
    fun `shouldRefetchArtwork with backdrops but no tmdbId is false even if old`() {
        val existing = ArtworkEntity(
            itemId = "magis:1", tmdbId = null, tmdbType = null,
            backdropsJson = """["https://portal/backdrop.jpg"]""", fetchedAt = 0L,
        )
        assertEquals(false, LibraryGrouping.shouldRefetchArtwork(existing, now = sevenDaysMs * 100))
    }

    /** Empty (no tmdbId or backdrops) and the 7-day window has already passed: yes, retry. */
    @Test
    fun `shouldRefetchArtwork empty and old is true`() {
        val existing = ArtworkEntity(itemId = "x", tmdbId = null, tmdbType = null, backdropsJson = "[]", fetchedAt = 0L)
        assertEquals(true, LibraryGrouping.shouldRefetchArtwork(existing, now = sevenDaysMs + 1))
    }

    /** Empty but recent (within the window): not retried yet. */
    @Test
    fun `shouldRefetchArtwork empty and fresh is false`() {
        val existing = ArtworkEntity(itemId = "x", tmdbId = null, tmdbType = null, backdropsJson = "[]", fetchedAt = 1000L)
        assertEquals(false, LibraryGrouping.shouldRefetchArtwork(existing, now = 1000L + sevenDaysMs - 1))
    }

    /** No previous row: asked for the first time. */
    @Test
    fun `shouldRefetchArtwork with no previous row is true`() {
        assertEquals(true, LibraryGrouping.shouldRefetchArtwork(null, now = 0L))
    }

    // ---- the item's own tmdbId: the one the gateway fills in by canonizing ----

    @Test
    fun `with no artwork resolved it groups by the item's own tmdbId`() {
        // The gateway writes `library_items.tmdbId` with the canonical work, verified against
        // TMDB. It's the key these rows are missing: their title doesn't exist on TMDB ("T1 - E7:
        // Construido por los hombres") so `ensureArtwork` never resolved anything for them and
        // each one was left on its own card.
        val a = row("web:9c9f5748", "T1 - E7: Construido por los hombres", 1, tmdbId = 890)
        val b = row("web:a59ba433", "T1 - E5: Rei, más allá de su corazón", 1, tmdbId = 890)

        assertEquals("tv:890", LibraryGrouping.groupKeyOf(a, null))
        assertEquals("tv:890", LibraryGrouping.groupKeyOf(b, null))
    }

    @Test
    fun `exact artwork still wins over the item's own tmdbId`() {
        // The item's tmdbId is a FALLBACK, not a replacement: whatever groups today has to keep
        // grouping the same way. If they ever disagree, what the artwork resolved on its own
        // wins, which is the behavior that was already proven.
        val a = row("web:series:x", "DAN DA DAN", 24, tmdbId = 999)

        assertEquals("tv:240411", LibraryGrouping.groupKeyOf(a, art(a.identifier, 240411, "tv")))
    }

    @Test
    fun `a movie doesn't group even with a tmdbId`() {
        // Same reason it doesn't group by the artwork's tmdbId: for movies, joining two rows is
        // worse than leaving the duplicate.
        val a = row("web:1", "Batman", 1, category = "movie", tmdbId = 414906)
        val b = row("web:2", "Batman", 1, category = "movie", tmdbId = 414906)

        assertEquals("item:web:1", LibraryGrouping.groupKeyOf(a, null))
        assertEquals("item:web:2", LibraryGrouping.groupKeyOf(b, null))
    }

    @Test
    fun `a tmdbId of zero isn't an id`() {
        // PocketBase's numeric field starts at 0 on rows that don't have it yet. Grouping by
        // "tv:0" would join the ENTIRE un-canonized library into a single card, the worst
        // possible outcome of this change.
        val a = row("web:1", "Algo", 5, tmdbId = 0)
        val b = row("web:2", "Otra cosa", 5, tmdbId = 0)

        assertEquals("item:web:1", LibraryGrouping.groupKeyOf(a, null))
        assertEquals("item:web:2", LibraryGrouping.groupKeyOf(b, null))
    }

    @Test
    fun `a standalone chapter groups with its series even though it looks like a movie`() {
        // Measured against the Fire TV's database: standalone chapters come in with a SINGLE
        // video and no categoryOverride, so `isMovie` calls them movies by the "1 video = movie"
        // rule -- and movies never group. Result: all five Evangelion rows stayed separate right
        // after they got their tmdbId. The gateway verified the canonical type against TMDB: if
        // it says series, it's a series.
        val a = row("web:9c9f5748", "T1 - E7: Construido por los hombres", 1, category = null,
            tmdbId = 890, tipo = "tv")
        val b = row("web:a59ba433", "Shin seiki evangerion Temp.1", 26, tmdbId = 890, tipo = "tv")

        assertEquals("tv:890", LibraryGrouping.groupKeyOf(a, null))
        assertEquals("tv:890", LibraryGrouping.groupKeyOf(b, null))
    }

    @Test
    fun `a real movie still doesn't group`() {
        // The rule that guards against the artwork's fuzzy match isn't touched: if the canonical
        // type says movie, it stays alone even if it shares an id with another.
        val a = row("web:1", "Batman", 1, category = "movie", tmdbId = 414906, tipo = "movie")
        val b = row("web:2", "Batman", 1, category = "movie", tmdbId = 414906, tipo = "movie")

        assertEquals("item:web:1", LibraryGrouping.groupKeyOf(a, null))
        assertEquals("item:web:2", LibraryGrouping.groupKeyOf(b, null))
    }

    @Test
    fun `with no canonical type a movie still doesn't group`() {
        // What isn't known doesn't enable anything: with no type, the usual heuristic wins.
        val a = row("web:1", "Algo", 1, category = null, tmdbId = 555)

        assertEquals("item:web:1", LibraryGrouping.groupKeyOf(a, null))
    }
}
