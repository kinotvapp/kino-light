package com.arkiv.player.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind the canonical seriesId: the SAME series has to give the SAME id no matter which
 * way the user came in. The real bug: DAN DA DAN ended up saved as `web:series:tt30217403` (24
 * chapters, from "Movies and series") and as `web:series:anilist171018` (1 chapter, from anime),
 * with the same chapter downloaded twice.
 */
class CanonicalSeriesIdTest {

    @Test
    fun `imdb wins over tmdb and over anilist`() {
        assertEquals(
            "tt30217403",
            SeriesItemIds.canonicalSeriesId(imdbId = "tt30217403", tmdbId = 240411, anilistId = 171018),
        )
    }

    @Test
    fun `with no imdb it falls back to tmdb`() {
        assertEquals(
            "tmdb240411",
            SeriesItemIds.canonicalSeriesId(imdbId = "", tmdbId = 240411, anilistId = 171018),
        )
        assertEquals(
            "tmdb240411",
            SeriesItemIds.canonicalSeriesId(imdbId = null, tmdbId = 240411, anilistId = 171018),
        )
    }

    /** With no mapping, NOTHING gets invented: the anilist id stays, i.e. what the app did until now. */
    @Test
    fun `with no imdb or tmdb it falls back to anilist`() {
        assertEquals(
            "anilist171018",
            SeriesItemIds.canonicalSeriesId(imdbId = null, tmdbId = null, anilistId = 171018),
        )
    }

    /** The non-anime path comes through here with no anilistId; having nothing to return isn't a case. */
    @Test
    fun `the non-anime path gives exactly what it gave before`() {
        assertEquals("tt0388629", SeriesItemIds.canonicalSeriesId("tt0388629", 37854))
        assertEquals("tmdb37854", SeriesItemIds.canonicalSeriesId("", 37854))
    }

    /**
     * The shape of the imdb id coming from TMDB is NOT validated: Android's `org.json` returns the
     * string `"null"` (not `""`) when `optString` lands on a JSON `null`, and TMDB sends
     * `"imdb_id": null` for series with no IMDb — meaning there are items today saved as
     * `web:series:null`. Rejecting it would move them to `web:series:tmdb<id>`: an identity change
     * with no mapping in between, the same bug this is meant to close but in reverse. (This test
     * passes the same way with the JVM's org.json, which does filter out the null; the string is
     * passed by hand precisely because of that.)
     */
    @Test
    fun `an oddly-shaped imdb id from the TMDB path is respected as-is`() {
        assertEquals("null", SeriesItemIds.canonicalSeriesId(imdbId = "null", tmdbId = 240411))
        assertEquals("unknown", SeriesItemIds.canonicalSeriesId(imdbId = "unknown", tmdbId = 240411))
    }

    /**
     * The anime dataset (Fribb) sometimes brings `imdb_id` as a list and sometimes as several ids
     * stuck together with a comma. That one DOES get validated: that id is NEW to the app (anime
     * never even looked at the mapping before), so discarding it moves nothing already saved, and a
     * malformed one would be a different item from the one the TMDB path builds — the same
     * duplication bug.
     */
    @Test
    fun `normalizes the imdb id from the anime dataset`() {
        assertEquals("tt30217403", SeriesItemIds.normalizeImdbId("tt30217403"))
        assertEquals("tt30217403", SeriesItemIds.normalizeImdbId("  tt30217403 "))
        assertEquals("tt30217403", SeriesItemIds.normalizeImdbId("tt30217403,tt9999999"))
        assertNull(SeriesItemIds.normalizeImdbId(""))
        assertNull(SeriesItemIds.normalizeImdbId(null))
        assertNull(SeriesItemIds.normalizeImdbId("unknown"))
        assertNull(SeriesItemIds.normalizeImdbId("null"))
        assertNull(SeriesItemIds.normalizeImdbId("30217403"))
    }

    /** A junk imdb id from the DATASET is discarded and anime falls back to tmdb, what TMDB gives. */
    @Test
    fun `a junk imdb id from the dataset isn't used and falls back to tmdb`() {
        assertEquals(
            "tmdb240411",
            SeriesItemIds.canonicalSeriesId(
                imdbId = SeriesItemIds.normalizeImdbId("unknown"),
                tmdbId = 240411,
                anilistId = 171018,
            ),
        )
    }

    /**
     * With no mapping repository (or no anilistId) the result is the usual one. `"anilistnull"` is
     * ugly but it's LITERALLY what `"anilist${card.anilistId ?: animeShow?.id}"` used to build with
     * both at null: kept on purpose so what's already saved doesn't change which id it lives under.
     */
    @Test
    fun `with no mapping available it uses anilist's fallback`() = runBlocking {
        assertEquals("anilist171018", SeriesItemIds.animeSeriesId(mappings = null, anilistId = 171018))
        assertEquals("anilistnull", SeriesItemIds.animeSeriesId(mappings = null, anilistId = null))
    }

    @Test
    fun `translates the local identifier into the bare seriesId`() {
        assertEquals("tt30217403", SeriesItemIds.seriesIdOrNull("web:series:tt30217403"))
        assertEquals("tt30217403", SeriesItemIds.seriesIdOrNull("torrent:series:tt30217403"))
        assertNull(SeriesItemIds.seriesIdOrNull("dragon-ball-gt"))
        assertNull(SeriesItemIds.seriesIdOrNull("torrent:anime:171018"))
    }
}
