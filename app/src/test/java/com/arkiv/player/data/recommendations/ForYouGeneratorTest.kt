package com.arkiv.player.data.recommendations

import com.arkiv.player.data.db.RecommendationEntity
import com.arkiv.player.data.ai.AiResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouGeneratorTest {

    private val HOUR = 60 * 60 * 1000L

    @Test fun `the gate opens the first time`() {
        assertTrue(ForYouGate.isDue(lastAttemptMs = 0, lastWasModelFailure = false, nowMs = 1))
    }

    @Test fun `the gate waits 24 hours`() {
        assertFalse(ForYouGate.isDue(1_000, false, 1_000 + 23 * HOUR))
        assertTrue(ForYouGate.isDue(1_000, false, 1_000 + 24 * HOUR))
    }

    /** A down model doesn't spend the whole window: it's retried after 15 minutes. */
    @Test fun `after a model failure it waits 15 minutes`() {
        assertFalse(ForYouGate.isDue(1_000, true, 1_000 + 14 * 60 * 1000L))
        assertTrue(ForYouGate.isDue(1_000, true, 1_000 + 15 * 60 * 1000L))
    }

    @Test fun `the prompt is the gateway's with the history underneath`() {
        val i = ForYouPrompt.prompt("- Coco (movie): terminado")
        assertTrue(i.startsWith("Eres un recomendador de películas y series para una persona de Colombia."))
        assertTrue(i.contains("Propón 20 títulos que NO estén en la lista."))
        assertTrue(i.endsWith("\n\n- Coco (movie): terminado"))
    }

    @Test fun `valid candidates are read and broken ones are dropped`() {
        val text = """```json
            [{"titulo":"Coco","anio":"2017","tipo":"movie","porque":"porque viste Encanto"},
             {"titulo":"","tipo":"movie","porque":"x"},
             "no soy un objeto",
             {"titulo":"Naruto","anio":2002,"tipo":"serie","porque":"porque viste Bleach"},
             {"titulo":"Dark","anio":"dos mil","tipo":"tv","porque":"x"}]```"""
        val c = ForYouPrompt.candidates(text)
        assertEquals(listOf("Coco", "Naruto", "Dark"), c.map { it.title })
        assertEquals("2002", c[1].year)
        // An unknown kind falls back to movie: TMDB confirms the real one in the cascade.
        assertEquals("movie", c[1].kind)
        assertEquals("tv", c[2].kind)
        // A year that is not a number does not take down the candidate: it is left empty.
        assertEquals("", c[2].year)
    }

    // --- the generator ---------------------------------------------------------

    private class Saved { var last: List<RecommendationEntity>? = null }

    private fun generator(
        ia: (String) -> AiResponse,
        watched: List<Watched> = listOf(Watched("Encanto", "movie", "terminado")),
        verify: (List<Candidate>) -> List<Verified> = { candidates ->
            candidates.mapIndexed { i, c -> Verified(c, i + 1, c.kind, c.title, "p$i", "magis1:${c.kind}:0:C$i") }
        },
        saved: Saved = Saved(),
        marks: MutableMap<String, Any> = mutableMapOf(),
        showingSome: Boolean = true,
    ) = ForYouGenerator(
        ia = { ia(it) },
        history = { watched },
        alreadySeen = { emptySet() },
        verify = { candidates, _ -> verify(candidates) },
        save = { saved.last = it },
        hasActive = { showingSome },
        readMarks = { (marks["t"] as? Long ?: 0L) to (marks["f"] as? Boolean ?: false) },
        writeMarks = { t, f -> marks["t"] = t; marks["f"] = f },
        nowMs = { 10 * HOUR },
    )

    private val goodAnswer = AiResponse.Text(
        """[{"titulo":"Coco","anio":"2017","tipo":"movie","porque":"porque viste Encanto"}]""", "m",
    )

    @Test fun `a success saves with order, the source's id and why`() = runTest {
        val g = Saved()
        generator(ia = { goodAnswer }, saved = g).generateIfDue()
        val r = g.last!!.single()
        assertEquals(com.arkiv.player.data.MagisEntities.itemIdFor("C0"), r.id)
        assertEquals(0, r.orden)
        assertEquals("porque viste Encanto", r.porque)
        assertEquals("magis1:movie:0:C0", r.ref)
        assertEquals(10 * HOUR, r.generadoAt)
    }

    @Test fun `the same work twice only counts once`() = runTest {
        val g = Saved()
        generator(
            ia = { goodAnswer },
            verify = { c -> List(2) { Verified(c.first(), 1, "movie", "Coco", "p", "magis1:movie:0:C7") } },
            saved = g,
        ).generateIfDue()
        assertEquals(1, g.last!!.size)
    }

    @Test fun `if the model does not answer nothing is deleted and the failure is marked`() = runTest {
        val g = Saved()
        val marks = mutableMapOf<String, Any>()
        generator(ia = { AiResponse.Unable }, saved = g, marks = marks).generateIfDue()
        assertNull(g.last)
        assertEquals(true, marks["f"])
    }

    @Test fun `if the model answers something unreadable it is a model failure`() = runTest {
        val marks = mutableMapOf<String, Any>()
        generator(ia = { AiResponse.Text("no sé", "m") }, marks = marks).generateIfDue()
        assertEquals(true, marks["f"])
    }

    @Test fun `if none end up verified nothing is deleted`() = runTest {
        val g = Saved()
        generator(ia = { goodAnswer }, verify = { emptyList() }, saved = g).generateIfDue()
        assertNull(g.last)
    }

    /** Measured 2026-09-19 on the TV: after a data wipe, one run with nothing verified left the
     *  row hidden for a whole day. With nothing showing, it gets the short window instead. */
    @Test fun `none verified with nothing showing retries in 15 minutes`() = runTest {
        val marks = mutableMapOf<String, Any>()
        generator(ia = { goodAnswer }, verify = { emptyList() }, marks = marks, showingSome = false)
            .generateIfDue()
        assertEquals(true, marks["f"])
        assertEquals(10 * HOUR, marks["t"])
    }

    @Test fun `none verified while older ones still show keeps the 24 hour window`() = runTest {
        val marks = mutableMapOf<String, Any>()
        generator(ia = { goodAnswer }, verify = { emptyList() }, marks = marks, showingSome = true)
            .generateIfDue()
        assertEquals(false, marks["f"])
    }

    /** `generateIfDue` runs on `applicationScope`: a loose exception would bring the app down. */
    @Test fun `an exception does not escape or delete anything`() = runTest {
        val g = Saved()
        val marks = mutableMapOf<String, Any>()
        generator(ia = { goodAnswer }, verify = { error("se cayó la red") }, saved = g, marks = marks)
            .generateIfDue()
        assertNull(g.last)
        assertEquals(true, marks["f"])
    }

    @Test fun `with no history the model is not asked`() = runTest {
        var asks = 0
        generator(ia = { asks++; goodAnswer }, watched = emptyList()).generateIfDue()
        assertEquals(0, asks)
    }

    @Test fun `with the gate closed it does nothing`() = runTest {
        var asks = 0
        val marks = mutableMapOf<String, Any>("t" to 10 * HOUR - 1, "f" to false)
        generator(ia = { asks++; goodAnswer }, marks = marks).generateIfDue()
        assertEquals(0, asks)
    }

    @Test fun `a success marks the attempt with no failure`() = runTest {
        val marks = mutableMapOf<String, Any>()
        generator(ia = { goodAnswer }, marks = marks).generateIfDue()
        assertEquals(10 * HOUR, marks["t"])
        assertEquals(false, marks["f"])
    }
}
