package com.arkiv.player.data.recommendations

import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.ai.AiResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouVerificationTest {

    private fun tmdb(id: Int, kind: String, title: String, year: String = "2017") =
        TmdbItem(id = id, type = kind, title = title, originalTitle = title, posterUrl = "p$id", year = year)

    private fun result(title: String, ref: String = "magis1:movie:0:$title") =
        GatewayResult(source = "magis", title = title, ref = ref)

    private val coco = Candidate("Coco", "2017", "movie", "porque viste Encanto")

    private fun verification(
        inTmdb: (String, String) -> TmdbItem? = { t, title -> tmdb(1, t, title) },
        inSources: (String) -> List<GatewayResult> = { listOf(result(it)) },
        referee: Referee = Referee { _, _, _, _ -> listOf(0) },
    ) = ForYouVerification(
        tmdb = TmdbSearcher { kind, title -> inTmdb(kind, title) },
        sources = SourceSearcher { title, _, _, _ -> inSources(title) },
        referee = referee,
    )

    @Test fun `a candidate that passes everything keeps its ref and TMDB's data`() = runTest {
        val v = verification().verify(listOf(coco), emptySet()).single()
        assertEquals(1, v.tmdbId)
        assertEquals("magis1:movie:0:Coco", v.ref)
        assertEquals("p1", v.posterUrl)
        assertEquals("porque viste Encanto", v.candidate.why)
    }

    @Test fun `what TMDB does not know was a hallucination`() = runTest {
        assertTrue(verification(inTmdb = { _, _ -> null }).verify(listOf(coco), emptySet()).isEmpty())
    }

    /** The kind that counts from then on is the one TMDB confirmed, not the one the model proposed. */
    @Test fun `if it does not show up with its kind the other one is tried`() = runTest {
        val v = verification(inTmdb = { t, title -> if (t == "tv") tmdb(9, "tv", title) else null })
            .verify(listOf(coco), emptySet()).single()
        assertEquals("tv", v.kind)
    }

    /** The wrong-kind namesake is like a movie that ended up opening the season screen. */
    @Test fun `an exact match of the other kind wins over a namesake`() = runTest {
        val v = verification(inTmdb = { t, _ ->
            if (t == "movie") tmdb(1, "movie", "Coco Chanel") else tmdb(2, "tv", "Coco")
        }).verify(listOf(coco), emptySet()).single()
        assertEquals(2, v.tmdbId)
        assertEquals("tv", v.kind)
    }

    @Test fun `with no exact match the first one that showed up wins`() = runTest {
        val v = verification(inTmdb = { t, _ ->
            if (t == "movie") tmdb(1, "movie", "Coco Chanel") else tmdb(2, "tv", "Coco y sus amigos")
        }).verify(listOf(coco), emptySet()).single()
        assertEquals(1, v.tmdbId)
    }

    @Test fun `something already seen by id is discarded`() = runTest {
        assertTrue(verification().verify(listOf(coco), setOf("tmdb:1")).isEmpty())
    }

    @Test fun `something already seen by title is discarded`() = runTest {
        assertTrue(verification().verify(listOf(coco), setOf(NormalizeTitle.of("COCO!"))).isEmpty())
    }

    @Test fun `an empty title in already-seen discards nothing`() = runTest {
        assertEquals(1, verification().verify(listOf(coco), setOf("")).size)
    }

    @Test fun `with no source that has it it is discarded`() = runTest {
        assertTrue(verification(inSources = { emptyList() }).verify(listOf(coco), emptySet()).isEmpty())
    }

    @Test fun `the referee picks which result is the work`() = runTest {
        val v = verification(
            inSources = { listOf(result("Coco podcast", "ref-podcast"), result("Coco", "ref-bueno")) },
            referee = Referee { _, _, _, _ -> listOf(1) },
        ).verify(listOf(coco), emptySet()).single()
        assertEquals("ref-bueno", v.ref)
    }

    /** The gateway (`router/search.py`) picks whichever approved one goes FIRST in `results`, not
     *  the first index the model happened to write (the JSON doesn't force ascending order). */
    @Test fun `with several approved the first of the results list wins`() = runTest {
        val v = verification(
            inSources = { listOf(result("A", "ref-a"), result("B", "ref-b"), result("C", "ref-c")) },
            referee = Referee { _, _, _, _ -> listOf(2, 0) },
        ).verify(listOf(coco), emptySet()).single()
        assertEquals("ref-a", v.ref)
    }

    @Test fun `a total rejection from the referee discards the candidate`() = runTest {
        assertTrue(verification(referee = Referee { _, _, _, _ -> emptyList() }).verify(listOf(coco), emptySet()).isEmpty())
    }

    /** Referee down = first result, as the gateway did. */
    @Test fun `if the referee does not answer the first one is taken`() = runTest {
        val v = verification(
            inSources = { listOf(result("A", "ref-a"), result("B", "ref-b")) },
            referee = Referee { _, _, _, _ -> null },
        ).verify(listOf(coco), emptySet()).single()
        assertEquals("ref-a", v.ref)
    }

    /**
     * With no referee, if there's a result of the kind TMDB confirmed, that one wins over the
     * first of the list: not a hard filter (with the referee answering this changes nothing),
     * just a better bet than "whatever arrived first" when there's nothing else to decide by.
     */
    @Test fun `if the referee does not answer but there is a result of the right kind that one is preferred`() = runTest {
        val v = verification(
            inSources = {
                listOf(
                    result("Coco serie", "ref-tv").copy(kind = "tv"),
                    result("Coco pelicula", "ref-movie").copy(kind = "movie"),
                )
            },
            referee = Referee { _, _, _, _ -> null },
        ).verify(listOf(coco), emptySet()).single() // coco.kind = "movie", and TMDB confirms it
        assertEquals("ref-movie", v.ref)
    }

    @Test fun `stops at the cap`() = runTest {
        val many = (1..15).map { Candidate("Peli $it", "2020", "movie", "x") }
        assertEquals(10, verification().verify(many, emptySet(), cap = 10).size)
    }

    @Test fun `normalizing strips accents, case and punctuation`() {
        assertEquals("el nino y la garza", NormalizeTitle.of("¡El  Niño y la Garza!"))
    }

    @Test fun `normalizing does not erase other alphabets`() {
        assertEquals("千と千尋の神隠し", NormalizeTitle.of("千と千尋の神隠し"))
    }

    @Test fun `the referee reads the numbers and discards ones that do not exist`() = runTest {
        val a = AiReferee { AiResponse.Text("[0, 5, true, 1]", "m") }
        assertEquals(listOf(0, 1), a.which("Coco", "2017", "movie", listOf(result("x"), result("y"))))
    }

    @Test fun `a referee that does not answer is null`() = runTest {
        assertNull(AiReferee { AiResponse.Unable }.which("Coco", "", "movie", listOf(result("x"))))
    }

    @Test fun `an unreadable referee is null`() = runTest {
        assertNull(AiReferee { AiResponse.Text("no sé", "m") }.which("Coco", "", "movie", listOf(result("x"))))
    }

    @Test fun `the referee sends the gateway's prompt with the numbered list`() = runTest {
        var prompt = ""
        AiReferee { prompt = it; AiResponse.Text("[]", "m") }
            .which("Coco", "2017", "movie", listOf(GatewayResult(source = "magis", title = "Coco.2017.1080p", ref = "r", year = "2017")))
        assertTrue(prompt.startsWith("Busco: Coco (2017) (película)."))
        assertTrue(prompt.endsWith("\n\n0. [magis] Coco.2017.1080p (2017, movie)"))
    }

    /** The results only ever come from Xuper and Caracol now; describing torrent releases and
     *  archive items the app no longer has just misleads the model. */
    @Test fun `the referee describes only the sources this app has`() = runTest {
        var prompt = ""
        AiReferee { prompt = it; AiResponse.Text("[]", "m") }
            .which("Futurama", "", "tv", listOf(GatewayResult(source = "magis", title = "Futurama T14", ref = "r")))
        assertFalse(prompt.contains("torrent", ignoreCase = true))
        assertFalse(prompt.contains("1080p"))
        assertTrue(prompt.contains("Xuper"))
        assertTrue(prompt.contains("Caracol"))
    }
}
