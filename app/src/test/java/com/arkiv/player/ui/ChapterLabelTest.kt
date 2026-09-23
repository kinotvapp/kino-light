package com.arkiv.player.ui

import com.arkiv.player.data.ItemDetail
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a chapter is named in the detail screen.
 *
 * The case that started this: Magis chapters save `season = null` and `episode = N`, and the old
 * version (private in `TvDetailScreen`) required season AND episode together, so it fell to the
 * `orderIndex` branch and showed chapter 5 as "E6".
 */
class ChapterLabelTest {

    private fun ep(
        orderIndex: Int = 0,
        season: Int? = null,
        episode: Int? = null,
        id: String = "item::x",
        displayName: String = "",
        itemId: String = "item",
        section: String = "",
    ) = Episode(
        id = id, itemId = itemId, section = section, displayName = displayName, orderIndex = orderIndex,
        durationSeconds = 0.0, thumbPath = null,
        season = season, episode = episode,
    )

    @Test fun `magis numbers by episode even without a season`() {
        assertEquals("E5", ChapterLabel.number(ep(orderIndex = 5, episode = 5)))
    }

    @Test fun `with season and episode both show`() {
        assertEquals("T2 · E5", ChapterLabel.number(ep(season = 2, episode = 5)))
    }

    @Test fun `a torrent pack numbers from orderIndex`() {
        // Packs encode season*1000 + episode, and declare it in the section. Both are needed: the
        // number alone doesn't tell this apart from an archive.org correlative (below 1000, season
        // 0) or from an absolute-numbering pack (above it).
        assertEquals(
            "T1 · E3",
            ChapterLabel.number(ep(itemId = "torrent:abc123", section = "Temporada 1", orderIndex = 1003)),
        )
    }

    @Test fun `with nothing, the order is 1-based`() {
        // archive.org: correlative 0..N-1.
        assertEquals("E1", ChapterLabel.number(ep(orderIndex = 0)))
    }

    /**
     * Magis numbers from 1 (`MagisEntities.chapterEntity`: `orderIndex = number`), the opposite of
     * archive.org's 0..N-1 correlative. Rows saved before that function wrote `episode` ended up
     * with `episode = null` and fall to the orderIndex branch: adding one showed Dragon Ball's e126
     * as "E127". Seen on the Fire TV on 2026-08-12, with the hero saying "E127 · Shen-Long revive"
     * -- and "Shen-Long revive" is e126's title.
     */
    @Test fun `magis with no saved episode doesn't shift a chapter`() {
        assertEquals(
            "E126",
            ChapterLabel.number(ep(itemId = "magis:3DE4A7E7C4EB4EFBABF927DBF1F7EF58", orderIndex = 126)),
        )
    }

    // --- Season 0 (specials) ------------------------------------------------------------------

    @Test fun `a season 0 special isn't shifted to the correlative`() {
        // The bug: a special's orderIndex is 0*1000 + 3 = 3, which does NOT reach 1000, so the
        // branch decoding season*1000 + episode didn't catch it and it fell to the archive.org
        // correlative -- special 3 showed up as "E4".
        assertEquals(
            "T0 · E3",
            ChapterLabel.number(
                ep(itemId = "torrent:series:tt0903747", section = "Temporada 0", orderIndex = 3),
            ),
        )
    }

    @Test fun `a season 0 special, also on web`() {
        assertEquals(
            "T0 · E1",
            ChapterLabel.number(ep(itemId = "web:series:tt0944947", section = "Temporada 0", orderIndex = 1)),
        )
    }

    /**
     * The counterexample that rules out "any small orderIndex is season 0": on archive.org
     * orderIndex is a 0..N-1 correlative, and a Spanish-language upload can have its files in a
     * folder named the same as the section that sources which DO encode write. What actually tells
     * them apart is which source the item comes from, not the size of the number.
     */
    @Test fun `archive in a folder named Temporada is still a correlative`() {
        assertEquals(
            "E1",
            ChapterLabel.number(ep(itemId = "mi-serie-favorita", section = "Temporada 1", orderIndex = 0)),
        )
    }

    /** A pack with absolute numbering (One Piece 1085) is not "T1 · E85". */
    @Test fun `a pack with absolute numbering isn't read as a season`() {
        assertEquals(
            "E1085",
            ChapterLabel.number(ep(itemId = "torrent:abc123", section = "", episode = 1085, orderIndex = 1085)),
        )
    }

    @Test fun `the real name goes NEXT TO the number, not in its place`() {
        // The bug: the phone detail row showed `tmdbTitle ?: displayName`, so on a Magis chapter the
        // number disappeared -- only "Panzy" showed where it used to say "E5  Daima T1_5". The
        // number identifies the chapter that's about to play and is the reliable data even when the
        // TMDB match is off: it can't be replaced by the name.
        assertEquals(
            "E5  ·  Panzy",
            ChapterLabel.withName(ep(orderIndex = 5, episode = 5, displayName = "E5  Daima T1_5"), "Panzy"),
        )
        assertEquals(
            "T5 · E8  ·  Ozymandias",
            ChapterLabel.withName(ep(season = 5, episode = 8, displayName = "s05e08.mkv"), "Ozymandias"),
        )
    }

    @Test fun `with no name resolved the file's stays`() {
        // TMDB doesn't always resolve. There, displayName wins, which in sources that number
        // already carries the number inside -- prepending "E5 · " would duplicate it.
        val e = ep(orderIndex = 5, episode = 5, displayName = "E5  Daima T1_5")
        assertEquals("E5  Daima T1_5", ChapterLabel.withName(e, null))
        assertEquals("E5  Daima T1_5", ChapterLabel.withName(e, "   "))
    }

    private fun detail(progress: Map<String, PlaybackEntity> = emptyMap()) = ItemDetail(
        identifier = "magis:ABC", title = "Daima", description = null, thumbnailUrl = "",
        episodes = (1..20).map { ep(orderIndex = it, episode = it, id = "magis:ABC::e$it") },
        progress = progress,
    )

    @Test fun `not started yet only says how many there are`() {
        assertEquals("20 episodios", ChapterLabel.progressSummary(detail(), "episodios"))
        assertEquals("Reproducir", ChapterLabel.playButtonLabel(detail()))
    }

    @Test fun `started says where you're at`() {
        val progress = mapOf(
            "magis:ABC::e5" to PlaybackEntity("magis:ABC::e5", 120_000L, 600_000L, false, 50L),
        )
        assertEquals("Vas en E5  ·  20 episodios", ChapterLabel.progressSummary(detail(progress), "episodios"))
        assertEquals("Reproducir E5", ChapterLabel.playButtonLabel(detail(progress)))
    }

    @Test fun `a finished chapter points to the next one`() {
        val progress = mapOf(
            "magis:ABC::e5" to PlaybackEntity("magis:ABC::e5", 600_000L, 600_000L, true, 50L),
        )
        assertEquals("Vas en E6  ·  20 episodios", ChapterLabel.progressSummary(detail(progress), "episodios"))
        assertEquals("Reproducir E6", ChapterLabel.playButtonLabel(detail(progress)))
    }

    @Test fun `a movie doesn't say where you're at`() {
        val movie = ItemDetail(
            identifier = "magis:X", title = "Duro de matar", description = null, thumbnailUrl = "",
            episodes = listOf(ep(id = "magis:X::0")),
            progress = mapOf("magis:X::0" to PlaybackEntity("magis:X::0", 120_000L, 600_000L, false, 50L)),
        )
        assertEquals("Reproducir", ChapterLabel.playButtonLabel(movie))
    }
}
