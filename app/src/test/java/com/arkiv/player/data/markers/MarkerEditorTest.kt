package com.arkiv.player.data.markers

import com.arkiv.player.data.ChapterMarker
import com.arkiv.player.data.db.SkipMarkerEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerEditorTest {

    private val dao = FakeSkipMarkerDao()
    private val editor = MarkerEditor(dao = dao, clock = { 555L })

    private fun row(itemId: String, episodeId: String) =
        dao.rows[ChapterMarker.idFor(itemId, episodeId)]

    @Test fun marking_the_opening_s_end_writes_a_manual_for_THAT_episode() = runBlocking {
        // The case nobody could produce: the two manual paths there were always wrote with
        // `episodeId = ""`, i.e. a manual for the WHOLE SERIES. `ChapterMarker.choose`'s
        // `manualChapter` branch was tested and unreachable.
        editor.setOpeningEnd("daima", "daima::e3", 92_000)
        val saved = row("daima", "daima::e3")!!
        assertEquals("daima::e3", saved.episodeId)
        assertEquals(ChapterMarker.SOURCE_MANUAL, saved.origen)
        assertEquals(92_000L, saved.openingEndMs)
        assertEquals(555L, saved.updatedAt)
    }

    @Test fun fixing_one_episode_does_not_touch_the_others() = runBlocking {
        // The whole reason this exists: a SERIES manual overrides the correct automatic marker of
        // every other episode, by `choose`'s precedence.
        dao.upsert(auto("daima", "daima::e1", openingEnd = 90_000))
        dao.upsert(auto("daima", "", openingEnd = 88_000))
        editor.setOpeningEnd("daima", "daima::e3", 92_000)
        assertEquals(90_000L, row("daima", "daima::e1")!!.openingEndMs)
        assertEquals(88_000L, row("daima", "")!!.openingEndMs)
    }

    @Test fun fixing_the_opening_keeps_the_ending_the_episode_already_had() = runBlocking {
        // The automatic source gets one field wrong at a time: for an episode it returned the
        // credits labeled as opening. Fixing one can't erase the other.
        dao.upsert(auto("daima", "daima::e3", openingEnd = 30_000, endingStart = 1_300_000))
        editor.setOpeningEnd("daima", "daima::e3", 92_000)
        val saved = row("daima", "daima::e3")!!
        assertEquals(92_000L, saved.openingEndMs)
        assertEquals(1_300_000L, saved.endingStartMs)
        assertEquals("fixed by hand stops being automatic", ChapterMarker.SOURCE_MANUAL, saved.origen)
    }

    @Test fun marking_the_ending_s_start_keeps_the_opening() = runBlocking {
        dao.upsert(auto("daima", "daima::e3", openingEnd = 90_000))
        editor.setEndingStart("daima", "daima::e3", 1_280_000)
        val saved = row("daima", "daima::e3")!!
        assertEquals(90_000L, saved.openingEndMs)
        assertEquals(1_280_000L, saved.endingStartMs)
    }

    @Test fun clearing_an_episode_s_leaves_an_empty_manual_row() = runBlocking {
        // Deleting the row outright would make the searcher download the same wrong automatic
        // marker again on the next playback. The empty row says "this episode has none": it has
        // no times, so `choose` ignores it and draws no buttons, but it exists.
        dao.upsert(auto("daima", "daima::e3", openingEnd = 30_000, endingStart = 1_300_000))
        editor.clear("daima", "daima::e3")
        val saved = row("daima", "daima::e3")!!
        assertNull(saved.openingEndMs)
        assertNull(saved.endingStartMs)
        assertEquals(ChapterMarker.SOURCE_MANUAL, saved.origen)
        assertNull(
            "with no times it doesn't win over anything",
            ChapterMarker.choose(fromChapter = saved, fromSeries = null),
        )
    }

    @Test fun clearing_the_series_s_deletes_the_row_as_always() = runBlocking {
        dao.upsert(auto("daima", "", openingEnd = 88_000))
        editor.clear("daima", "")
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun an_episode_manual_wins_over_the_series_manual() = runBlocking {
        editor.setOpeningEnd("daima", "", 88_000)
        editor.setOpeningEnd("daima", "daima::e3", 92_000)
        val chosen = ChapterMarker.choose(
            fromChapter = row("daima", "daima::e3"),
            fromSeries = row("daima", ""),
        )
        assertEquals(92_000L, chosen!!.openingEndMs)
    }

    private fun auto(itemId: String, episodeId: String, openingEnd: Long? = null, endingStart: Long? = null) =
        SkipMarkerEntity(
            id = ChapterMarker.idFor(itemId, episodeId),
            itemId = itemId,
            episodeId = episodeId,
            openingStartMs = 0,
            openingEndMs = openingEnd,
            endingStartMs = endingStart,
            updatedAt = 1,
            origen = ChapterMarker.SOURCE_AUTO,
        )
}
