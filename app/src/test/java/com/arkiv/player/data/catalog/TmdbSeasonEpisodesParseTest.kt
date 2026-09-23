package com.arkiv.player.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The difference between "couldn't be queried" and "was queried and there was nothing".
 *
 * The case that started these tests: `seasonEpisodes` returned `emptyList()` for both, so
 * `ArkivRepository.ensureEpisodeStills` couldn't tell them apart and wrote every chapter's row the
 * same way regardless. For a series with no previous rows, a single detail-open with no network
 * sealed the whole thing to null: its early cutoff (`previas.containsAll(...)`) evaluated true from
 * then on, and that series was left with no images or names until the app was reinstalled.
 *
 * The opposite error —treating "TMDB answered and there was nothing" as a failure— is just as bad:
 * it would re-ask forever, on every open. That's why these are two distinct values instead of a
 * single "there was data" boolean.
 */
class TmdbSeasonEpisodesParseTest {

    @Test fun `with no response it returns null so it gets retried`() {
        // `get()` returns null on timeout as much as on 429 or 5xx: all of them are "couldn't ask".
        assertNull(parseSeasonEpisodes(json = null, seasonNumber = 1))
    }

    @Test fun `an unreadable response also counts as a failure`() {
        // A body that isn't JSON (a gateway error, a wifi's captive portal) isn't a real TMDB
        // response: better to retry than to seal the series to null.
        assertNull(parseSeasonEpisodes(json = "<html>502 Bad Gateway</html>", seasonNumber = 1))
    }

    @Test fun `a season with no chapters returns an empty list, not null`() {
        // This IS a response: the row gets written empty as a mark of "already asked". If null
        // came out here, it would re-ask on every detail-open, forever.
        assertEquals(emptyList<TmdbEpisode>(), parseSeasonEpisodes("""{"episodes":[]}""", 1))
        // A valid JSON that's missing the `episodes` key is the same case, not a failure.
        assertEquals(emptyList<TmdbEpisode>(), parseSeasonEpisodes("""{"id":1234}""", 1))
    }

    @Test fun `a season with chapters parses in full`() {
        val eps = parseSeasonEpisodes(
            """
            {"episodes":[
              {"season_number":5,"episode_number":1,"name":"La conspiración",
               "overview":"Goku entrena…","air_date":"2013-08-11","still_path":"/abc.jpg"}
            ]}
            """.trimIndent(),
            seasonNumber = 5,
        )
        assertNotNull(eps)
        assertEquals(1, eps!!.size)
        assertEquals(5, eps[0].season)
        assertEquals(1, eps[0].episode)
        assertEquals("La conspiración", eps[0].name)
        assertEquals("Goku entrena…", eps[0].overview)
        assertEquals("2013-08-11", eps[0].air)
        assertEquals("https://image.tmdb.org/t/p/w300/abc.jpg", eps[0].stillUrl)
    }

    @Test fun `with no still, the URL stays empty instead of half-built`() {
        // A blank `stillUrl` is what `ensureEpisodeStills` reads as "TMDB has no image"; a
        // half-built URL would get saved as if there were one and leave the gap showing in the UI.
        val eps = parseSeasonEpisodes("""{"episodes":[{"episode_number":2}]}""", 3)
        assertEquals("", eps!![0].stillUrl)
        // With no `season_number` in the JSON, the season that was requested wins, since that's the one queried.
        assertEquals(3, eps[0].season)
        // With no name, a readable fallback instead of an empty string.
        assertTrue(eps[0].name.isNotBlank())
    }
}
