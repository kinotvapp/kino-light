package com.arkiv.player.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ranking by hash, release-name similarity, language and downloads; and the OpenSubtitles hash params. */
class SubtitleRankingTest {

    private fun s(ref: String, release: String = "", lang: String = "es", dl: Int = 0, hash: Boolean = false) =
        OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, ref, lang, release, dl, hashMatch = hash)

    private val langs = listOf("es", "en")

    @Test fun `a hash match goes first whatever its language and downloads`() {
        val out = OnlineSubtitleRules.sort(listOf(s("a", lang = "es", dl = 9000), s("h", lang = "fr", dl = 1, hash = true)), langs)
        assertEquals(listOf("h", "a"), out.map { it.ref })
    }

    @Test fun `the same cut beats another cut of the same title`() {
        val hint = FileHint(fileName = "Nosferatu.1922.720p.BluRay.x264", title = "Nosferatu", year = 1922)
        val out = OnlineSubtitleRules.sort(
            listOf(s("other", "Nosferatu.1979.1080p.BluRay.x264", dl = 5000), s("same", "Nosferatu.1922.720p.BluRay.x264-YTS", dl = 10)),
            langs, file = hint,
        )
        assertEquals(listOf("same", "other"), out.map { it.ref })
    }

    @Test fun `the year alone separates cuts when the title is all there is`() {
        val hint = FileHint(title = "Nosferatu", year = 1922)
        val out = OnlineSubtitleRules.sort(listOf(s("old", "Nosferatu 1979", dl = 100), s("ok", "Nosferatu.1922", dl = 1)), langs, file = hint)
        assertEquals("ok", out.first().ref)
    }

    @Test fun `resolution and source agreement break a tie`() {
        val hint = FileHint(fileName = "Movie.2010.1080p.WEBRip.x264")
        val out = OnlineSubtitleRules.sort(listOf(s("a", "Movie.2010.720p.HDTV.x264", dl = 50), s("b", "Movie.2010.1080p.WEBRip.x264", dl = 1)), langs, file = hint)
        assertEquals("b", out.first().ref)
    }

    @Test fun `release tokens drop codec, resolution and punctuation`() {
        val p = ReleaseMatch.profile("The_Matrix-1999.1080p.BluRay.x264.YTS")
        assertEquals(setOf("the", "matrix"), p.words)
        assertEquals(1999, p.year)
        assertEquals("1080p", p.resolution)
        assertEquals("bluray", p.source)
    }

    @Test fun `with no information the order is language then downloads, as before`() {
        val list = listOf(s("en900", lang = "en", dl = 900), s("es10", dl = 10), s("fr5000", lang = "fr", dl = 5000), s("es300", dl = 300))
        val plain = OnlineSubtitleRules.sort(list, langs, cap = 3)
        assertEquals(listOf("es300", "es10", "en900"), plain.map { it.ref })
        assertEquals(plain, OnlineSubtitleRules.sort(list, langs, cap = 3, file = FileHint()))
        assertEquals(plain, OnlineSubtitleRules.sort(list, langs, cap = 3, file = FileHint(fileName = "x264.1080p")))
    }

    @Test fun `the cap never drops a hash match`() {
        val many = (1..30).map { s("n$it", dl = it) } + (1..3).map { s("h$it", lang = "fr", hash = true) }
        val out = OnlineSubtitleRules.sort(many, langs, cap = 15)
        assertEquals(15, out.size)
        assertEquals(listOf("h1", "h2", "h3"), out.take(3).map { it.ref }.sorted())
        val flood = (1..20).map { s("h$it", hash = true) }
        assertEquals(20, OnlineSubtitleRules.sort(flood, langs, cap = 15).size)
    }

    @Test fun `a provider group with a hash match goes first`() {
        val a = ProviderResults(SubtitleProviderId.SUBDL, listOf(s("x")))
        val b = ProviderResults(SubtitleProviderId.OPENSUBTITLES, listOf(s("h", hash = true)))
        val c = ProviderResults(SubtitleProviderId.SUBDL, emptyList())
        assertEquals(listOf(b, a, c), OnlineSubtitleRules.orderGroups(listOf(a, b, c)))
        assertEquals(listOf(a, c), OnlineSubtitleRules.orderGroups(listOf(a, c)))
    }

    @Test fun `the search url carries the hash and include, params sorted`() {
        val q = SubtitleQuery(false, title = "Nosferatu", year = 1922, languages = listOf("es", "en"), movieHash = "8E245D9679D31E12")
        assertEquals(
            "b/subtitles?languages=en,es&moviehash=8e245d9679d31e12&moviehash_match=include&query=nosferatu&type=movie&year=1922",
            OpenSubtitlesProvider.searchUrl("b", q),
        )
    }

    @Test fun `no hash or a malformed one adds nothing`() {
        val q = SubtitleQuery(false, imdbId = "tt0133093", languages = listOf("es"))
        assertEquals("b/subtitles?imdb_id=133093&languages=es&type=movie", OpenSubtitlesProvider.searchUrl("b", q))
        assertEquals("b/subtitles?imdb_id=133093&languages=es&type=movie", OpenSubtitlesProvider.searchUrl("b", q.copy(movieHash = "zz")))
    }

    @Test fun `moviehash_match is parsed`() {
        val json = """{"data":[
            {"attributes":{"language":"es","release":"A","download_count":3,"moviehash_match":true,"files":[{"file_id":11}]}},
            {"attributes":{"language":"es","release":"B","download_count":9,"files":[{"file_id":12}]}}]}"""
        val r = OpenSubtitlesProvider.parseSearch(json)
        assertEquals(listOf(true, false), r.map { it.hashMatch })
        assertTrue(r.size == 2)
    }
}
