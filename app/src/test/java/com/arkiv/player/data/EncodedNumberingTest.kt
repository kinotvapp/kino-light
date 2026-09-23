package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a row's `orderIndex` carries the numbering inside it and when it doesn't.
 *
 * The case that started this: the old rule was "if `orderIndex >= 1000`, it's encoded", and a
 * season 0 gives 0*1000 + N -- below 1000. Special 3 showed up as "E4".
 */
class EncodedNumberingTest {

    @Test fun `a normal torrent season decodes`() {
        assertEquals(1 to 3, EncodedNumbering.coordinates("torrent:series:tt1", "Temporada 1", 1003))
    }

    @Test fun `a season 0 decodes even below 1000`() {
        assertEquals(0 to 3, EncodedNumbering.coordinates("torrent:series:tt1", "Temporada 0", 3))
    }

    @Test fun `web encodes the same as torrent`() {
        assertEquals(0 to 1, EncodedNumbering.coordinates("web:series:tt1", "Temporada 0", 1))
    }

    @Test fun `a pack also encodes`() {
        assertEquals(2 to 7, EncodedNumbering.coordinates("torrent:abc123", "Temporada 2", 2007))
    }

    /** archive.org uses orderIndex as a correlative: there's nothing to decode. */
    @Test fun `archive never encodes`() {
        assertNull(EncodedNumbering.coordinates("mi-serie-favorita", "", 3))
    }

    /**
     * The counterexample that forces looking at BOTH things: an archive.org upload in Spanish
     * could well have its files in a folder called "Temporada 1". The section alone isn't enough.
     */
    @Test fun `archive with a folder named Temporada still doesn't encode`() {
        assertNull(EncodedNumbering.coordinates("mi-serie-favorita", "Temporada 1", 0))
    }

    /**
     * The other counterexample, from above: an anime pack with absolute numbering goes past 1000
     * without being encoded. The old rule read it as "T1 · E85".
     */
    @Test fun `a pack with absolute numbering doesn't encode`() {
        assertNull(EncodedNumbering.coordinates("torrent:abc123", "", 1085))
    }

    /** Magis numbers in its own column and leaves the section empty. */
    @Test fun `magis doesn't encode`() {
        assertNull(EncodedNumbering.coordinates("magis:ABC", "", 5))
    }

    /** A section that only STARTS like the season one doesn't count: the name has to match exactly. */
    @Test fun `a similar but different section doesn't count`() {
        assertNull(EncodedNumbering.coordinates("torrent:series:tt1", "Temporada 1 (dual)", 1003))
        assertNull(EncodedNumbering.coordinates("torrent:series:tt1", "Temporadas", 1003))
    }
}
