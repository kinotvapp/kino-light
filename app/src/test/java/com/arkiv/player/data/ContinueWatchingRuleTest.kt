package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where you're at in a series: the rule shared by "Continue watching" and the "Reproducir" button.
 *
 * The case that motivated it (device, 2026-08-13): Dragon Ball watched through e136 --all
 * finished-- and an e104 abandoned at 38% the morning before. Both surfaces offered e104, because
 * both looked for "the most recent UNFINISHED one" instead of first looking at what was actually
 * played last.
 */
class ContinueWatchingRuleTest {

    private fun p(
        episodeId: String,
        lastPlayedAt: Long,
        positionMs: Long = 0L,
        watched: Boolean = false,
    ) = ChapterProgress(
        episodeId = episodeId,
        positionMs = positionMs,
        watched = watched,
        lastPlayedAt = lastPlayedAt,
    )

    /** Ordered chapter list: the next one after "e5" is "e6". */
    private fun inOrder(vararg ids: String): (String) -> String? = { id ->
        val i = ids.indexOf(id)
        if (i >= 0) ids.getOrNull(i + 1) else null
    }

    private val minute = 60_000L

    @Test
    fun `if you finished the last chapter it offers the next one`() {
        val r = ContinueWatchingRule.choose(
            listOf(p("e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true)),
            inOrder("e136", "e137"),
        )
        assertEquals("e137", r?.episodeId)
        assertEquals(true, r?.isNext)
    }

    @Test
    fun `a chapter abandoned a while ago does NOT beat something finished later`() {
        // The reported bug, as-is: e104 halfway in the morning, e136 finished at night.
        val r = ContinueWatchingRule.choose(
            listOf(
                p("e104", lastPlayedAt = 100L, positionMs = 567_000L),
                p("e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true),
            ),
            inOrder("e104", "e136", "e137"),
        )
        assertEquals("e137", r?.episodeId)
    }

    @Test
    fun `the order is set by the last playback, not the offered chapter`() {
        // The home row is ordered by this: if the offered chapter (which was never played) drove
        // it, a series you finished last night would fall to the bottom behind anything old.
        val r = ContinueWatchingRule.choose(
            listOf(p("e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true)),
            inOrder("e136", "e137"),
        )
        assertEquals(900L, r?.lastPlayedAt)
    }

    @Test
    fun `a recent halfway chapter beats the next one`() {
        val r = ContinueWatchingRule.choose(
            listOf(
                p("e2", lastPlayedAt = 100L, positionMs = 1_400_000L, watched = true),
                p("e3", lastPlayedAt = 900L, positionMs = 213_000L),
            ),
            inOrder("e2", "e3", "e4"),
        )
        assertEquals("e3", r?.episodeId)
        assertEquals(false, r?.isNext)
    }

    @Test
    fun `if you finished the whole series there's nothing to continue`() {
        val r = ContinueWatchingRule.choose(
            listOf(p("e153", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true)),
            inOrder("e152", "e153"),
        )
        assertNull(r)
    }

    @Test
    fun `a chapter that's only OPEN is where you're at, with no second floor`() {
        // Regression that fixed inProgressEpisode and that the floor CANNOT bring back: hitting
        // play on e5 and leaving after three seconds (markInProgress leaves the row at 0) has to
        // say "you're on e5", not "you're on e1". That's why choose() knows no floor at all.
        val r = ContinueWatchingRule.choose(
            listOf(p("e5", lastPlayedAt = 900L, positionMs = 0L)),
            inOrder("e1", "e2", "e3", "e4", "e5"),
        )
        assertEquals("e5", r?.episodeId)
        // It's NOT "the next one": it's where you're at, even with no saved position. The detail
        // screen still marking it as the current chapter depends on this.
        assertEquals(false, r?.isNext)
    }

    @Test
    fun `a chapter that's only open doesn't beat one with real playback`() {
        // Regression of the old rule (see inProgressEpisode's doc): markInProgress writes a row at
        // position 0 just from OPENING a chapter. That row can't displace one that actually played.
        val r = ContinueWatchingRule.choose(
            listOf(
                p("e126", lastPlayedAt = 100L, positionMs = 210_000L),
                p("e127", lastPlayedAt = 800L, positionMs = 0L),
                p("e128", lastPlayedAt = 900L, positionMs = 0L),
            ),
            inOrder("e126", "e127", "e128"),
        )
        assertEquals("e126", r?.episodeId)
    }

    @Test
    fun `with no playback at all there's nothing to continue`() {
        assertNull(ContinueWatchingRule.choose(emptyList(), inOrder("e1")))
    }

    @Test
    fun `if the last finished one is the only one and has no next, nothing is made up`() {
        val r = ContinueWatchingRule.choose(
            listOf(p("suelto", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true)),
            { null },
        )
        assertNull(r)
    }

    // --- The home's full row (one card per item, ordered) ---------------------------------------

    private fun forItem(
        itemId: String,
        progress: ChapterProgress,
        nextEpisodeId: String? = null,
    ) = ItemProgress(itemId, progress, nextEpisodeId)

    @Test
    fun `a single card per series, even with ten chapters in progress`() {
        val rows = (1..10).map { n ->
            forItem("db", p("e$n", lastPlayedAt = n * 100L, positionMs = 1_400_000L, watched = true), "e${n + 1}")
        }
        val r = ContinueWatchingRule.byItem(rows, minPositionMs = 0L)
        assertEquals(1, r.size)
        assertEquals("e11", r.first().episodeId)
    }

    @Test
    fun `the series you watched most recently goes first`() {
        // The reported case: Dragon Ball finished last night has to go BEFORE the Evangelion left
        // halfway earlier.
        val rows = listOf(
            forItem("eva", p("eva-e3", lastPlayedAt = 500L, positionMs = 213_000L)),
            forItem("db", p("db-e136", lastPlayedAt = 900L, positionMs = 1_393_000L, watched = true), "db-e137"),
        )
        val r = ContinueWatchingRule.byItem(rows, minPositionMs = 2 * minute)
        assertEquals(listOf("db-e137", "eva-e3"), r.map { it.episodeId })
    }

    @Test
    fun `items with nothing to continue don't take a spot in the row`() {
        val rows = listOf(
            forItem("terminada", p("fin", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true), null),
            forItem("viva", p("e1", lastPlayedAt = 100L, positionMs = 300_000L)),
        )
        val r = ContinueWatchingRule.byItem(rows, minPositionMs = 2 * minute)
        assertEquals(listOf("e1"), r.map { it.episodeId })
    }

    @Test
    fun `the row gets cut at the limit`() {
        val rows = (1..30).map { n ->
            forItem("item$n", p("e$n", lastPlayedAt = n * 100L, positionMs = 300_000L))
        }
        assertEquals(20, ContinueWatchingRule.byItem(rows, minPositionMs = 0L).size)
    }

    @Test
    fun `a movie you opened for a few seconds doesn't take a spot in the row`() {
        val rows = listOf(forItem("peli", p("peli", lastPlayedAt = 900L, positionMs = 3_000L)))
        assertEquals(emptyList<String>(), ContinueWatchingRule.byItem(rows, minPositionMs = 2 * minute).map { it.episodeId })
    }

    @Test
    fun `a series you're watching stays even if the current chapter is at 30 seconds`() {
        // You finished e136 and hit play for 30s on e137: you're still on e137. The floor is there
        // for things you touched and abandoned, not to kick out of the row the series you're
        // actually watching.
        val rows = listOf(
            forItem("db", p("e136", lastPlayedAt = 100L, positionMs = 1_393_000L, watched = true), "e137"),
            forItem("db", p("e137", lastPlayedAt = 900L, positionMs = 30_000L), "e138"),
        )
        val r = ContinueWatchingRule.byItem(rows, minPositionMs = 2 * minute)
        assertEquals(listOf("e137"), r.map { it.episodeId })
    }

    @Test
    fun `one item's next chapter doesn't cross over into another's`() {
        val rows = listOf(
            forItem("a", p("a1", lastPlayedAt = 900L, positionMs = 1_400_000L, watched = true), "a2"),
            forItem("b", p("b1", lastPlayedAt = 800L, positionMs = 1_400_000L, watched = true), "b2"),
        )
        val r = ContinueWatchingRule.byItem(rows, minPositionMs = 0L)
        assertEquals(listOf("a2", "b2"), r.map { it.episodeId })
    }
}
