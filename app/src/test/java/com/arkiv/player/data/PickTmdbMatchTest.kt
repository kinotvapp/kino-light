package com.arkiv.player.data

import com.arkiv.player.data.catalog.TmdbItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the TMDB results is the art for a library item.
 *
 * The real bug: `ensureArtwork` used to settle for `results.first()`, and TMDB sorts by its
 * relevance score, not by exact match. For `search/tv?query=Dragon Ball` it returns "Dragon Ball
 * Z" first and the 1986 "Dragon Ball" in position 7 of 9, so all three Dragon Ball titles in the
 * library ended up with Z's `tmdbId`: with Z's cover art and —worse— merged into ONE single card,
 * because [LibraryGrouping] groups series by `tv:<tmdbId>`.
 *
 * The lists here are the REAL gateway responses (es-MX, 2026-08-11), not made up: the order is
 * exactly what's under discussion.
 */
class PickTmdbMatchTest {

    private fun tv(id: Int, title: String, original: String, year: String) =
        TmdbItem(id = id, type = "tv", title = title, originalTitle = original, posterUrl = "", year = year)

    /** Real response for `search/tv?query=Dragon Ball`, in its real order. */
    private val dragonBall = listOf(
        tv(12971, "Dragon Ball Z", "ドラゴンボールゼット", "1989"),
        tv(236994, "Dragon Ball Daima", "ドラゴンボールDAIMA", "2024"),
        tv(80020, "Dragon Ball Heroes", "スーパードラゴンボールヒーローズ", "2018"),
        tv(62715, "Dragon Ball Super", "ドラゴンボール超（スーパー）", "2015"),
        tv(61709, "Dragon Ball Z Kai", "ドラゴンボール改「カイ」", "2009"),
        tv(12697, "Dragon Ball GT", "ドラゴンボールGT", "1996"),
        tv(12609, "Dragon Ball", "ドラゴンボール", "1986"),
        tv(330965, "DragonBall Z Abridged", "DragonBall Z Abridged", "2008"),
    )

    @Test
    fun `picks the exact match even if TMDB sends it to the bottom`() {
        assertEquals(12609, pickTmdbMatch("Dragon Ball", dragonBall)?.item?.id)
    }

    /**
     * The same case backwards, so the rule is "the exact title wins" and not "the shortest one
     * wins": with the 1986 one first, searching "Dragon Ball Z" still has to return Z.
     */
    @Test
    fun `the exact match also wins when the short title comes first`() {
        assertEquals(12971, pickTmdbMatch("Dragon Ball Z", dragonBall.reversed())?.item?.id)
    }

    /**
     * With no exact match, the old behavior (the first one) is kept, which is the best bet left:
     * the library has "Dragon Ball Kai" and TMDB calls it "Dragon Ball Z Kai".
     */
    @Test
    fun `with no exact match it falls back to the first result`() {
        val kai = listOf(tv(61709, "Dragon Ball Z Kai", "ドラゴンボール改「カイ」", "2009"))
        assertEquals(61709, pickTmdbMatch("Dragon Ball Kai", kai)?.item?.id)
    }

    /**
     * TMDB returns the title in es-MX, but releases usually come with the original in English.
     * Without looking at `originalTitle`, "The Simpsons" wouldn't match "Los Simpson" and would
     * fall back to the first one by accident (which here happens to be the right one, that's why
     * the order was deliberately altered).
     */
    @Test
    fun `also matches against the original title`() {
        val simpsons = listOf(
            tv(304530, "Fortnite x Los Simpson", "Fortnite x The Simpsons", "2025"),
            tv(456, "Los Simpson", "The Simpsons", "1989"),
        )
        assertEquals(456, pickTmdbMatch("The Simpsons", simpsons)?.item?.id)
    }

    /** Normalization: accents, capitalization and punctuation must not break the exact match. */
    @Test
    fun `the exact match ignores accents, capitalization and punctuation`() {
        val list = listOf(
            tv(1, "Otra Cosa", "Something Else", "2020"),
            tv(2, "El Señor de los Cielos", "El Señor de los Cielos", "2013"),
        )
        assertEquals(2, pickTmdbMatch("el senor de los cielos!", list)?.item?.id)
    }

    /**
     * A title that comes out empty after normalizing (Japanese, Cyrillic) would "exact"-match any
     * original that also normalizes to empty — which is almost all anime. There's no match worth
     * anything there: it falls back to the first one.
     */
    @Test
    fun `a title with no latin characters doesn't make up an exact match`() {
        assertEquals(12971, pickTmdbMatch("ドラゴンボール", dragonBall)?.item?.id)
    }

    @Test
    fun `with no results there's no match`() {
        assertNull(pickTmdbMatch("Lo Que Sea", emptyList()))
    }

    @Test
    fun `a match by equal title is marked exact`() {
        assertTrue(pickTmdbMatch("Dragon Ball", dragonBall)!!.exact)
    }

    @Test
    fun `a match by elimination is NOT marked exact`() {
        // This is the one that matters. The first result is good enough to pull a
        // decent backdrop for "Dragon Ball Kai", but it's NOT identity: `ensureArtwork`
        // used to save that id and `LibraryGrouping` groups by it, so a title TMDB
        // doesn't know -- "Built by men" -- would take the id of whatever first
        // result landed and merge two unrelated works into one card.
        val kai = listOf(tv(61709, "Dragon Ball Z Kai", "ドラゴンボール改「カイ」", "2009"))
        val m = pickTmdbMatch("Dragon Ball Kai", kai)
        assertEquals(61709, m?.item?.id)
        assertFalse(m!!.exact)
    }

    @Test
    fun `with no usable query the first result isn't exact either`() {
        // Blank query after cleaning: something is returned for the art, but there's
        // NOTHING to claim it's the same work.
        val m = pickTmdbMatch("", dragonBall)
        assertEquals(12971, m?.item?.id)
        assertFalse(m!!.exact)
    }
}
