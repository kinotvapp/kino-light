package com.arkiv.player.data.recommendations

import com.arkiv.player.data.db.HistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySignalsTest {

    private var clock = 1_000L
    private fun row(
        item: String,
        pos: Long,
        dur: Long,
        watched: Boolean = false,
        titulo: String = item,
        tipo: String? = "movie",
        episodio: Int? = null,
    ) = HistoryRow(
        episodeId = "$item::$clock", positionMs = pos, durationMs = dur, watched = watched,
        lastPlayedAt = clock--, episodio = episodio, itemId = item, titulo = titulo,
        tituloCanonico = null, tipo = tipo, categoryOverride = null, tmdbId = null,
    )

    @Test fun `watched is terminado`() {
        assertEquals("terminado", HistorySignals.of(listOf(row("a", 100, 100, watched = true))).single().status)
    }

    @Test fun `less than ten percent is abandonado`() {
        assertEquals("abandonado", HistorySignals.of(listOf(row("a", 5, 100))).single().status)
    }

    /** Halfway through says nothing: it's being watched right now. */
    @Test fun `halfway through does not count`() {
        assertTrue(HistorySignals.of(listOf(row("a", 50, 100))).isEmpty())
    }

    @Test fun `halfway through lets an earlier row of the same item decide`() {
        val v = HistorySignals.of(listOf(row("a", 50, 100), row("a", 100, 100, watched = true)))
        assertEquals(listOf("terminado"), v.map { it.status })
    }

    @Test fun `an item counts only once`() {
        val v = HistorySignals.of(listOf(row("a", 100, 100, watched = true), row("a", 5, 100)))
        assertEquals(1, v.size)
    }

    @Test fun `with no duration it is not known whether it was abandoned`() {
        assertTrue(HistorySignals.of(listOf(row("a", 0, 0))).isEmpty())
    }

    @Test fun `keeps the most recent ones up to the cap`() {
        val rows = (1..40).map { row("i$it", 100, 100, watched = true) }
        val v = HistorySignals.of(rows)
        assertEquals(HistorySignals.CAP, v.size)
        assertEquals("i1", v.first().title)
    }

    @Test fun `the canonical title wins`() {
        val f = row("a", 100, 100, watched = true, titulo = "Shin seiki Temp.1").copy(tituloCanonico = "Neon Genesis Evangelion")
        assertEquals("Neon Genesis Evangelion", HistorySignals.of(listOf(f)).single().title)
    }

    @Test fun `the kind comes from the same rule as the trivia fact`() {
        val f = row("a", 100, 100, watched = true, tipo = null, episodio = 3)
        assertEquals("tv", HistorySignals.of(listOf(f)).single().kind)
    }

    @Test fun `the lines have the gateway's shape`() {
        val r = HistorySignals.lines(listOf(Watched("Coco", "movie", "terminado"), Watched("Naruto", "tv", "abandonado")))
        assertEquals("- Coco (movie): terminado\n- Naruto (tv): abandonado", r)
    }
}
