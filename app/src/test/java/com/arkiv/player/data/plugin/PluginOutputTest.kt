package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOutputTest {
    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs += it }
    private val hosts = EffectiveHosts(listOf("archive.org", "*.archive.org"))

    private fun items(json: String, allowSeries: Boolean = true, max: Int = PluginOutput.MAX_SEARCH_ITEMS) =
        PluginOutput.page(json, max, allowSeries, allowNext = false, log = log).items

    @Test fun `valid items keep their fields, invalid ones are dropped with a log line`() {
        val json = """[
          {"id":"a1","ref":"r1","title":" Metrópolis ","kind":"movie","year":1927,"poster":"https://x/p.jpg","backdrop":"http://x/b.jpg"},
          {"id":"bad id","ref":"r","title":"t","kind":"movie"},
          {"id":"a2","ref":"","title":"t","kind":"movie"},
          {"id":"a3","ref":"r","title":"   ","kind":"movie"},
          {"id":"a4","ref":"r","title":"t","kind":"episode"},
          {"id":"a1","ref":"dup","title":"dup","kind":"movie"},
          42, null
        ]"""
        val items = items(json)
        assertEquals(1, items.size)
        with(items.single()) {
            assertEquals("Metrópolis", title)
            assertEquals("1927", year)
            assertEquals("https://x/p.jpg", poster)
            assertEquals("", backdrop) // http images are dropped
        }
        assertTrue(logs.isNotEmpty())
    }

    @Test fun `caps and garbage`() {
        val many = (1..150).joinToString(",", "[", "]") { """{"id":"i$it","ref":"r","title":"t","kind":"movie"}""" }
        assertEquals(PluginOutput.MAX_SEARCH_ITEMS, items(many).size)
        assertEquals(100, PluginOutput.MAX_SEARCH_ITEMS)
        assertEquals(emptyList<PluginItem>(), items("not json"))
        assertEquals(emptyList<PluginItem>(), items("{}"))
        assertEquals(emptyList<PluginItem>(), items("42"))
        val hugeRef = """[{"id":"x","ref":"${"r".repeat(5000)}","title":"t","kind":"movie"}]"""
        assertEquals(0, items(hugeRef).size)
    }

    @Test fun `series items are dropped when the plugin cannot list episodes`() {
        val json = """[{"id":"s","ref":"r","title":"t","kind":"series"},{"id":"m","ref":"r","title":"t","kind":"movie"}]"""
        assertEquals(listOf("m"), items(json, allowSeries = false).map { it.id })
    }

    // --- live channels (apiVersion 2) ---

    private val liveJson = """[
      {"id":"c1","ref":"ch-1","title":"Canal Uno","kind":"live","poster":"https://x/c1.png","runtimeMinutes":120},
      {"id":"m","ref":"r","title":"t","kind":"movie","runtimeMinutes":90}
    ]"""

    @Test fun `a live item is kept only when the plugin may offer live channels`() {
        val kept = PluginOutput.page(liveJson, 100, allowSeries = true, allowNext = false, allowLive = true, log = log).items
        assertEquals(listOf("c1", "m"), kept.map { it.id })
        with(kept.first()) {
            assertEquals(PluginOutput.KIND_LIVE, kind)
            assertEquals("ch-1", ref)
            assertEquals("https://x/c1.png", poster)
        }
    }

    @Test fun `a v1 plugin's live item is dropped silently like any invalid item, with a log line`() {
        // The default is off: every caller that never heard of live keeps dropping it.
        assertEquals(listOf("m"), items(liveJson).map { it.id })
        assertTrue(logs.any { "c1" in it && "live" in it })
    }

    @Test fun `a live item carries no duration even if the plugin sent one`() {
        val kept = PluginOutput.page(liveJson, 100, allowSeries = true, allowNext = false, allowLive = true, log = log).items
        assertEquals(0, kept.first { it.kind == PluginOutput.KIND_LIVE }.runtimeMinutes)
        assertEquals(90, kept.first { it.kind == "movie" }.runtimeMinutes)
    }

    @Test fun `home rows gate live items the same way`() {
        val rows = """[{"id":"vivo","title":"En vivo","items":$liveJson}]"""
        assertEquals(listOf("c1", "m"), PluginOutput.rows(rows, allowSeries = true, allowBrowse = false, allowLive = true, log = log).single().items.map { it.id })
        assertEquals(listOf("m"), PluginOutput.rows(rows, allowSeries = true, allowBrowse = false, log = log).single().items.map { it.id })
        // A row of live channels alone vanishes for a v1 plugin, like any row left without items.
        val onlyLive = """[{"id":"vivo","title":"En vivo","items":[{"id":"c1","ref":"ch-1","title":"Canal","kind":"live"}]}]"""
        assertEquals(emptyList<PluginRow>(), PluginOutput.rows(onlyLive, allowSeries = true, allowBrowse = false, log = log))
    }

    @Test fun `the live gate is apiVersion 2 and the kinds are pinned`() {
        assertTrue(PluginOutput.allowsLive(2))
        assertTrue(PluginOutput.allowsLive(3))
        assertEquals(false, PluginOutput.allowsLive(1))
        assertEquals(listOf("movie", "series", "live"), PluginOutput.ITEM_KINDS)
    }

    @Test fun `a page carries its cursor only when the plugin declares browse`() {
        val json = """{"items":[{"id":"a","ref":"r","title":"A","kind":"movie"}],"next":"page=2"}"""
        with(PluginOutput.page(json, 100, allowSeries = true, allowNext = true, log = log)) {
            assertEquals(listOf("a"), items.map { it.id })
            assertEquals("page=2", next)
        }
        assertNull(PluginOutput.page(json, 100, allowSeries = true, allowNext = false, log = log).next)
        assertTrue(logs.any { "browse" in it })
        val long = """{"items":[],"next":"${"c".repeat(2049)}"}"""
        assertNull(PluginOutput.page(long, 100, true, true, log = log).next)
        assertNull(PluginOutput.page("""{"items":[],"next":42}""", 100, true, true, log = log).next)
        assertNull(PluginOutput.page("""{"items":[],"next":""}""", 100, true, true, log = log).next)
        assertEquals(emptyList<PluginItem>(), PluginOutput.page("""{"next":"x"}""", 100, true, true, log = log).items)
    }

    @Test fun `SDK v1 item fields are read and bounded`() {
        val json = """[{"id":"a","ref":"r","title":"A","kind":"movie","originalTitle":" Metropolis ",
          "genres":["Drama"," ","Ciencia ficción","${"g".repeat(40)}","Drama","Cine mudo","Clásico","Extra"],
          "rating":8.3,"runtimeMinutes":153,"ids":{"tmdb":19,"imdb":"tt0017136"},
          "badges":["HD","Latino","Subtitulada","4K"],"lang":"es","quality":"1080p"}]"""
        with(items(json).single()) {
            assertEquals("Metropolis", originalTitle)
            assertEquals(listOf("Drama", "Ciencia ficción", "g".repeat(30), "Cine mudo", "Clásico"), genres)
            assertEquals(8.3, rating!!, 0.0)
            assertEquals(153, runtimeMinutes)
            assertEquals(19, tmdbId)
            assertEquals("tt0017136", imdbId)
            assertEquals(listOf("HD", "Latino", "Subtitulada"), badges)
        }
    }

    @Test fun `out-of-range optional fields are dropped, the item stays`() {
        val json = """[{"id":"a","ref":"r","title":"A","kind":"movie","rating":11,"runtimeMinutes":0,
          "ids":{"tmdb":-4,"imdb":"nm0000001"},"genres":"Drama","badges":[1,2]},
          {"id":"b","ref":"r","title":"B","kind":"movie","rating":"9","runtimeMinutes":1001,"ids":{"tmdb":"19","imdb":"tt123"}}]"""
        items(json).forEach {
            assertNull(it.rating)
            assertEquals(0, it.runtimeMinutes)
            assertEquals(0, it.tmdbId)
            assertEquals("", it.imdbId)
            assertEquals(emptyList<String>(), it.genres)
            assertEquals(emptyList<String>(), it.badges)
        }
    }

    @Test fun `adult items never reach a screen`() {
        val json = """[{"id":"a","ref":"r","title":"A","kind":"movie","adult":true},{"id":"b","ref":"r","title":"B","kind":"movie","adult":false}]"""
        assertEquals(listOf("b"), items(json).map { it.id })
        assertTrue(logs.any { "adult" in it })
    }

    @Test fun `rows are capped at 20 and 60 items, empty rows vanish`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val rows = (1..25).joinToString(",", "[", "]") { """{"id":"row$it","title":"Fila $it","items":[$item]}""" }
        assertEquals(PluginOutput.MAX_ROWS, PluginOutput.rows(rows, true, allowBrowse = false, log = log).size)
        val wide = (1..70).joinToString(",", "[", "]") { """{"id":"i$it","ref":"r","title":"t","kind":"movie"}""" }
        assertEquals(PluginOutput.MAX_ROW_ITEMS, PluginOutput.rows("""[{"id":"w","title":"W","items":$wide}]""", true, false, log = log).single().items.size)
        assertEquals(0, PluginOutput.rows("""[{"id":"r","title":"x","items":[]}]""", true, false, log = log).size)
        assertEquals(0, PluginOutput.rows("""[{"id":"r","title":"","items":[$item]}]""", true, false, log = log).size)
    }

    @Test fun `a row keeps its ref only when the plugin declares browse`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val json = """[{"id":"a","title":"A","items":[$item],"ref":"films"},{"id":"b","title":"B","items":[$item],"ref":"${"x".repeat(4097)}"}]"""
        assertEquals(listOf("films", null), PluginOutput.rows(json, true, allowBrowse = true, log = log).map { it.ref })
        assertEquals(listOf(null, null), PluginOutput.rows(json, true, allowBrowse = false, log = log).map { it.ref })
    }

    @Test fun `duplicate row ids are dropped, first wins, with a log line`() {
        val item = """{"id":"i","ref":"r","title":"t","kind":"movie"}"""
        val json = """[
          {"id":"top","title":"Primera","items":[$item]},
          {"id":"mid","title":"Otra","items":[$item]},
          {"id":"top","title":"Duplicada","items":[$item]}
        ]"""
        val rows = PluginOutput.rows(json, true, false, log = log)
        // Home keys each Lazy row by the plugin's row id: a repeat id would crash the whole screen.
        assertEquals(listOf("top", "mid"), rows.map { it.id })
        assertEquals("Primera", rows[0].title)
        assertTrue(logs.any { "duplicate" in it && "top" in it })
    }

    @Test fun `episodes default season 1, drop bad ones and duplicates, read SDK v1 fields`() {
        val json = """{"series":{"title":"Dragnet","tmdbId":123,"poster":"https://x/p.jpg","genres":["Policial"],"year":1951},
          "episodes":[{"number":1,"ref":"a","airDate":"1951-12-16","runtimeMinutes":30},{"season":2,"number":1,"ref":"b","title":"B","airDate":"16/12/1951"},
                      {"number":0,"ref":"c"},{"number":2,"ref":""},{"season":2,"number":1,"ref":"dup"}]}"""
        val out = PluginOutput.episodes(json, log)
        assertEquals(listOf(1 to 1, 2 to 1), out.episodes.map { it.season to it.number })
        assertEquals(listOf("1951-12-16", ""), out.episodes.map { it.airDate })
        assertEquals(30, out.episodes[0].runtimeMinutes)
        assertEquals(123, out.series!!.tmdbId)
        assertEquals(listOf("Policial"), out.series!!.genres)
        assertEquals("1951", out.series!!.year)
        assertThrows(PluginContractException::class.java) { PluginOutput.episodes("[]", log) }
    }

    @Test fun `series ids use the SDK v1 shape and fall back to the flat fields`() {
        val v1 = PluginOutput.episodes("""{"series":{"ids":{"tmdb":7,"imdb":"tt0043208"}},"episodes":[]}""", log).series!!
        assertEquals(7, v1.tmdbId)
        assertEquals("tt0043208", v1.imdbId)
        val flat = PluginOutput.episodes("""{"series":{"tmdbId":9,"imdbId":"tt0000009"},"episodes":[]}""", log).series!!
        assertEquals(9, flat.tmdbId)
        assertEquals("tt0000009", flat.imdbId)
    }

    @Test fun `episodes are capped at 5000`() {
        val many = (1..5200).joinToString(",", "{\"episodes\":[", "]}") { """{"number":$it,"ref":"r$it"}""" }
        assertEquals(PluginOutput.MAX_EPISODES, PluginOutput.episodes(many, log).episodes.size)
    }

    // ---- sibling seasons (an optional part of the episodes answer, apiVersion 1) ----

    @Test fun `sibling seasons are read with their id, ref, title, number and current flag`() {
        val json = """{"episodes":[{"number":1,"ref":"a"}],
          "seasons":[{"id":"s1","ref":"S1","title":"Temporada 1","number":1},
                     {"id":"s2","ref":"S2","title":"Segunda parte","number":2,"current":true},
                     {"id":"s3","ref":"S3","title":"Especiales"}]}"""
        val out = PluginOutput.episodes(json, log).seasons
        assertEquals(listOf("s1", "s2", "s3"), out.map { it.id })
        assertEquals(listOf("S1", "S2", "S3"), out.map { it.ref })
        assertEquals(listOf("Temporada 1", "Segunda parte", "Especiales"), out.map { it.title })
        // No number reads as 0 ("the plugin did not say"), never as season 1.
        assertEquals(listOf(1, 2, 0), out.map { it.number })
        assertEquals(listOf(false, true, false), out.map { it.current })
        assertTrue(logs.isEmpty())
    }

    @Test fun `an answer without seasons, or with something that is not a list, has none`() {
        assertEquals(emptyList<PluginSeason>(), PluginOutput.episodes("""{"episodes":[{"number":1,"ref":"a"}]}""", log).seasons)
        assertEquals(emptyList<PluginSeason>(), PluginOutput.episodes("""{"episodes":[],"seasons":"T1, T2"}""", log).seasons)
        assertEquals(emptyList<PluginSeason>(), PluginOutput.episodes("""{"episodes":[],"seasons":{"id":"s1"}}""", log).seasons)
        assertTrue(logs.any { "seasons" in it && "not a list" in it })
    }

    @Test fun `a malformed season is dropped with a log line and the rest survive`() {
        val longRef = "r".repeat(PluginOutput.MAX_REF_CHARS + 1)
        val json = """{"episodes":[],
          "seasons":[{"id":"ok","ref":"R","title":"Temporada 1","number":1},
                     {"id":"bad id!","ref":"R","title":"T"},
                     {"id":"noref","ref":"","title":"T"},
                     {"id":"longref","ref":"$longRef","title":"T"},
                     {"id":"notitle","ref":"R","title":"  "},
                     {"id":"nottext","ref":"R","title":["T"]},
                     {"id":"ok","ref":"R2","title":"Duplicada"},
                     "s9", 7, null,
                     {"id":"range","ref":"R","title":"Fuera de rango","number":1000},
                     {"id":"text","ref":"R","title":"Número en texto","number":"2","current":"yes"}]}"""
        val out = PluginOutput.episodes(json, log).seasons
        assertEquals(listOf("ok", "range", "text"), out.map { it.id })
        // An out-of-range or non-numeric number, or a non-boolean current, drop the FIELD, not the season.
        assertEquals(listOf(1, 0, 0), out.map { it.number })
        assertEquals(listOf(false, false, false), out.map { it.current })
        assertTrue(logs.any { "bad id!" !in it && "invalid id" in it })
        assertTrue(logs.any { "noref" in it })
        assertTrue(logs.any { "longref" in it })
        assertTrue(logs.any { "notitle" in it })
        assertTrue(logs.any { "duplicate" in it && "ok" in it })
    }

    @Test fun `seasons are capped at 50 and never fail the chapters`() {
        val many = (1..60).joinToString(",") { """{"id":"s$it","ref":"S$it","title":"Temporada $it","number":$it}""" }
        val out = PluginOutput.episodes("""{"episodes":[{"number":1,"ref":"a"}],"seasons":[$many]}""", log)
        assertEquals(PluginOutput.MAX_SEASONS, out.seasons.size)
        assertEquals(50, PluginOutput.MAX_SEASONS)
        assertEquals(1, out.episodes.size)
        assertTrue(logs.any { "seasons" in it && "beyond 50" in it })
    }

    @Test fun `stream must be https on a declared host`() {
        val ok = PluginOutput.stream(
            """{"url":"https://archive.org/download/x/y.mp4","mime":"video/mp4","durationMs":5000,"expiresInSeconds":600,
               "headers":{"Referer":"https://archive.org/","Host":"evil","X-Bad":"a\nb","N":5},
               "subtitles":[{"lang":"en","url":"https://ia8.us.archive.org/s.vtt","format":"vtt"},
                            {"lang":"es","url":"https://evil.example/s.vtt"}]}""",
            hosts,
        )
        assertEquals("https://archive.org/download/x/y.mp4", ok.url)
        assertEquals(mapOf("Referer" to "https://archive.org/"), ok.headers)
        assertEquals(listOf("en"), ok.subtitles.map { it.lang })
        assertEquals(5000L, ok.durationMs)
        assertEquals(600, ok.expiresInSeconds)

        listOf(
            """{"url":"http://archive.org/x.mp4"}""",
            """{"url":"https://evil.example/x.mp4"}""",
            """{"url":"not a url"}""",
            """{"url":"https://archive.org/x.mpd","drmLicenseUrl":"https://archive.org/lic"}""",
            """{"url":"https://archive.org/x.mp4","mime":"video mp4"}""",
            "[]",
        ).forEach { json -> assertThrows(json, PluginContractException::class.java) { PluginOutput.stream(json, hosts) } }
    }

    // --- Widevine (apiVersion 2, the `drm` capability) ---

    private val widevine = """{"url":"https://archive.org/x.mpd","mime":"application/dash+xml",
        "drm":{"type":"widevine","licenseUrl":"https://ia8.us.archive.org/lic",
               "licenseHeaders":{"Authorization":"Bearer t","Host":"evil","X-Bad":"a\nb","N":5}}}"""

    @Test fun `a drm block is refused exactly as before unless the plugin declares drm`() {
        val e = assertThrows(PluginContractException::class.java) { PluginOutput.stream(widevine, hosts) }
        assertEquals("El video tiene DRM y los plugins no lo soportan", e.message)
        assertThrows(PluginContractException::class.java) { PluginOutput.stream(widevine, hosts, allowDrm = false) }
    }

    @Test fun `a plugin that declares drm gets the widevine stream with its license url and headers`() {
        val s = PluginOutput.stream(widevine, hosts, allowDrm = true)
        assertEquals("https://archive.org/x.mpd", s.url)
        assertEquals(PluginDrm("https://ia8.us.archive.org/lic", mapOf("Authorization" to "Bearer t")), s.drm)
    }

    @Test fun `a stream without a drm block has none, capability or not`() {
        assertNull(PluginOutput.stream("""{"url":"https://archive.org/x.mp4"}""", hosts, allowDrm = true).drm)
        assertNull(PluginOutput.stream("""{"url":"https://archive.org/x.mp4"}""", hosts).drm)
    }

    @Test fun `the license url is checked exactly like the stream url`() {
        listOf(
            """{"type":"widevine","licenseUrl":"http://archive.org/lic"}""" to "La licencia del video debe usar https",
            """{"type":"widevine","licenseUrl":"https://evil.example/lic"}""" to "La licencia del video apunta a evil.example, que el plugin no declaró",
            """{"type":"widevine","licenseUrl":"not a url"}""" to "La licencia del video tiene una dirección inválida",
            """{"type":"widevine"}""" to "La licencia del video tiene una dirección inválida",
        ).forEach { (drm, message) ->
            val e = assertThrows(drm, PluginContractException::class.java) {
                PluginOutput.stream("""{"url":"https://archive.org/x.mpd","drm":$drm}""", hosts, allowDrm = true)
            }
            assertEquals(message, e.message)
        }
    }

    @Test fun `only a widevine object is a drm block`() {
        listOf(""""drm":{"type":"playready","licenseUrl":"https://archive.org/lic"}""", """"drm":"widevine"""", """"drm":{"licenseUrl":"https://archive.org/lic"}""", """"drm":null""", """"drm":[]""")
            .forEach { drm -> assertThrows(drm, PluginContractException::class.java) { PluginOutput.stream("""{"url":"https://archive.org/x.mpd",$drm}""", hosts, allowDrm = true) } }
    }

    @Test fun `every other DRM-shaped key is still refused, with or without the capability, even next to a valid drm block`() {
        listOf("license", "licenseUrl", "drmLicenseUrl", "keySystem", "widevine").forEach { key ->
            val bare = """{"url":"https://archive.org/x.mpd","$key":"https://archive.org/lic"}"""
            assertThrows(key, PluginContractException::class.java) { PluginOutput.stream(bare, hosts, allowDrm = true) }
            assertThrows(key, PluginContractException::class.java) { PluginOutput.stream(bare, hosts) }
            val both = """{"url":"https://archive.org/x.mpd","$key":"x","drm":{"type":"widevine","licenseUrl":"https://archive.org/lic"}}"""
            val e = assertThrows(key, PluginContractException::class.java) { PluginOutput.stream(both, hosts, allowDrm = true) }
            assertEquals("El video tiene DRM y los plugins no lo soportan", e.message)
        }
    }

    @Test fun `a protected stream keeps its side audio tracks, which the player then plays clear`() {
        val s = PluginOutput.stream(
            """{"url":"https://archive.org/x.mpd","drm":{"type":"widevine","licenseUrl":"https://archive.org/lic"},
               "audioTracks":[{"lang":"es-419","url":"https://archive.org/a-es.aac","label":"Latino"},{"lang":"en","url":"https://evil.example/a-en.aac"}],
               "subtitles":[{"lang":"es","url":"https://archive.org/s.vtt"}]}""",
            hosts, allowDrm = true,
        )
        assertEquals("https://archive.org/lic", s.drm!!.licenseUrl)
        assertEquals(listOf("es-419"), s.audioTracks.map { it.lang })
        assertEquals(listOf("es"), s.subtitles.map { it.lang })
    }

    @Test fun `license headers are capped and filtered like the stream headers`() {
        val many = (1..25).joinToString(",") { """"H$it":"v"""" }
        val s = PluginOutput.stream(
            """{"url":"https://archive.org/x.mpd","drm":{"type":"widevine","licenseUrl":"https://archive.org/lic","licenseHeaders":{$many}}}""",
            hosts, allowDrm = true,
        )
        assertEquals(PluginOutput.MAX_HEADERS, s.drm!!.licenseHeaders.size)
        assertEquals(20, PluginOutput.MAX_HEADERS)
        val none = PluginOutput.stream("""{"url":"https://archive.org/x.mpd","drm":{"type":"widevine","licenseUrl":"https://archive.org/lic"}}""", hosts, allowDrm = true)
        assertEquals(emptyMap<String, String>(), none.drm!!.licenseHeaders)
    }

    // --- declared insecure hosts (apiVersion 2, `{ host, insecureHttp: true }`) ---

    private val insecure = EffectiveHosts(listOf("api.example.com", "cdn.example.com", "lic.example.com"), insecure = setOf("cdn.example.com"))

    @Test fun `a stream, its subtitles, audio and license may use http on a host approved as insecureHttp, and on no other`() {
        val s = PluginOutput.stream(
            """{"url":"http://cdn.example.com/v.mpd",
               "subtitles":[{"lang":"es","url":"http://cdn.example.com/s.vtt"},{"lang":"en","url":"http://api.example.com/s.vtt"},{"lang":"fr","url":"https://api.example.com/s.vtt"}],
               "audioTracks":[{"lang":"es","url":"http://cdn.example.com/a.aac"},{"lang":"en","url":"http://lic.example.com/a.aac"},{"lang":"fr","url":"http://sub.cdn.example.com/a.aac"}],
               "drm":{"type":"widevine","licenseUrl":"http://cdn.example.com/lic"}}""",
            insecure, allowDrm = true,
        )
        assertEquals("http://cdn.example.com/v.mpd", s.url)
        assertEquals(listOf("es", "fr"), s.subtitles.map { it.lang })
        assertEquals(listOf("es"), s.audioTracks.map { it.lang })
        assertEquals("http://cdn.example.com/lic", s.drm!!.licenseUrl)
        // https keeps working on the insecure host.
        assertEquals("https://cdn.example.com/v.mp4", PluginOutput.stream("""{"url":"https://cdn.example.com/v.mp4"}""", insecure).url)
        listOf("http://api.example.com/v.mp4", "http://sub.cdn.example.com/v.mp4", "http://lic.example.com/v.mp4").forEach { u ->
            val e = assertThrows(u, PluginContractException::class.java) { PluginOutput.stream("""{"url":"$u"}""", insecure) }
            assertEquals("El video debe usar https", e.message)
        }
        val lic = assertThrows(PluginContractException::class.java) {
            PluginOutput.stream("""{"url":"https://cdn.example.com/v.mpd","drm":{"type":"widevine","licenseUrl":"http://lic.example.com/lic"}}""", insecure, allowDrm = true)
        }
        assertEquals("La licencia del video debe usar https", lic.message)
        // A v1 plugin's hosts (never an insecure one) refuse http exactly as before.
        assertThrows(PluginContractException::class.java) { PluginOutput.stream("""{"url":"http://archive.org/x.mp4"}""", hosts) }
    }

    @Test fun `a stream with no audioTracks field has none, exactly as before this feature`() {
        val s = PluginOutput.stream("""{"url":"https://archive.org/x.mp4"}""", hosts)
        assertTrue(s.audioTracks.isEmpty())
    }

    @Test fun `audioTracks are validated like subtitles, host and https checked, invalid ones dropped`() {
        val ok = PluginOutput.stream(
            """{"url":"https://archive.org/download/x/y.mp4",
               "audioTracks":[{"lang":"en","url":"https://ia8.us.archive.org/a-en.aac","label":"English"},
                              {"lang":"es-419","url":"https://evil.example/a-es.aac"},
                              {"lang":"fr","url":"http://archive.org/a-fr.aac"},
                              {"lang":"ja","url":"not a url"}]}""",
            hosts,
        )
        assertEquals(listOf("en"), ok.audioTracks.map { it.lang })
        assertEquals("English", ok.audioTracks.single().label)
        assertEquals("https://ia8.us.archive.org/a-en.aac", ok.audioTracks.single().url)
    }

    /** Two children for one URL would be two menu rows, and one failure would blame (and drop) both. */
    @Test fun `an audio track URL given twice is kept once, the first wins`() {
        val s = PluginOutput.stream(
            """{"url":"https://archive.org/x.mp4",
               "audioTracks":[{"lang":"es","url":"https://archive.org/a.aac","label":"Latino"},
                              {"lang":"en","url":"https://archive.org/a.aac","label":"English"},
                              {"lang":"fr","url":"https://archive.org/b.aac"}]}""",
            hosts,
        )
        assertEquals(listOf("es", "fr"), s.audioTracks.map { it.lang })
        assertEquals("Latino", s.audioTracks.first().label)
    }

    @Test fun `audioTracks are capped at 8, lang defaults to und and long fields are cut`() {
        val many = (1..10).joinToString(",") { """{"lang":"en","url":"https://archive.org/a$it.aac"}""" }
        val s = PluginOutput.stream("""{"url":"https://archive.org/x.mp4","audioTracks":[$many]}""", hosts)
        assertEquals(PluginOutput.MAX_AUDIO_TRACKS, s.audioTracks.size)

        val cut = PluginOutput.stream(
            """{"url":"https://archive.org/x.mp4",
               "audioTracks":[{"lang":"${"x".repeat(50)}","url":"https://archive.org/a.aac","label":"${"y".repeat(80)}"}]}""",
            hosts,
        ).audioTracks.single()
        assertEquals(16, cut.lang.length)
        assertEquals(40, cut.label.length)

        val blank = PluginOutput.stream(
            """{"url":"https://archive.org/x.mp4","audioTracks":[{"lang":"","url":"https://archive.org/a.aac"}]}""",
            hosts,
        ).audioTracks.single()
        assertEquals("und", blank.lang)
        assertEquals("", blank.label)
    }

    @Test fun `an audio track may be on a server the person typed, exactly that server only`() {
        val lan = EffectiveHosts(listOf("api.example.com"), listOf(UserHost("http", "192.168.1.10", 8096)))
        val s = PluginOutput.stream(
            """{"url":"http://192.168.1.10:8096/v.mp4",
               "audioTracks":[{"lang":"es","url":"http://192.168.1.10:8096/a-es.aac"},
                              {"lang":"en","url":"http://192.168.1.10:8097/a-en.aac"}]}""",
            lan,
        )
        assertEquals(listOf("es"), s.audioTracks.map { it.lang })
    }

    @Test fun `expiresInSeconds outside 30 to 86400 is ignored`() {
        listOf(29, 86_401, -1).forEach { v ->
            assertEquals(0, PluginOutput.stream("""{"url":"https://archive.org/x.mp4","expiresInSeconds":$v}""", hosts).expiresInSeconds)
        }
        assertEquals(30, PluginOutput.stream("""{"url":"https://archive.org/x.mp4","expiresInSeconds":30}""", hosts).expiresInSeconds)
    }

    @Test fun `a stream and its subtitles may be on a server the person typed, exactly`() {
        val lan = EffectiveHosts(listOf("api.example.com"), listOf(UserHost("http", "192.168.1.10", 8096)))
        val s = PluginOutput.stream(
            """{"url":"http://192.168.1.10:8096/Videos/1/stream.mp4","subtitles":[{"lang":"es","url":"http://192.168.1.10:8096/s.vtt"},{"lang":"en","url":"http://192.168.1.10:8097/s.vtt"}]}""",
            lan,
        )
        assertEquals("http://192.168.1.10:8096/Videos/1/stream.mp4", s.url)
        assertEquals(listOf("es"), s.subtitles.map { it.lang })
        listOf("http://192.168.1.10:9999/x.mp4", "https://192.168.1.10:8096/x.mp4", "http://192.168.1.11:8096/x.mp4", "http://api.example.com/x.mp4").forEach { u ->
            assertThrows(u, PluginContractException::class.java) { PluginOutput.stream("""{"url":"$u"}""", lan) }
        }
    }

    @Test fun `images on IP literals or local names are dropped, so a poster is never a LAN probe`() {
        val items = items(
            """[{"id":"a","ref":"r","title":"A","kind":"movie","poster":"https://192.168.1.1/cgi-bin/reboot","backdrop":"https://cdn.example.com/b.jpg"},
                {"id":"b","ref":"r","title":"B","kind":"movie","poster":"https://localhost:8080/p.jpg","backdrop":"https://[fd00::1]/b.jpg"},
                {"id":"c","ref":"r","title":"C","kind":"movie","poster":"https://nas.local/p.jpg","backdrop":"https://2130706433/b.jpg"}]""",
            allowSeries = false,
        )
        assertEquals(listOf("", "", ""), items.map { it.poster })
        assertEquals(listOf("https://cdn.example.com/b.jpg", "", ""), items.map { it.backdrop })
    }

    @Test fun `an image on a server the person typed is kept, exactly that server only`() {
        val lan = EffectiveHosts(emptyList(), listOf(UserHost("http", "192.168.1.10", 8096)))
        val page = PluginOutput.page(
            """[{"id":"a","ref":"r","title":"A","kind":"movie","poster":"http://192.168.1.10:8096/Items/1/Images/Primary","backdrop":"http://192.168.1.10:80/b.jpg"}]""",
            100, allowSeries = false, allowNext = false, hosts = lan,
        )
        assertEquals("http://192.168.1.10:8096/Items/1/Images/Primary", page.items.single().poster)
        assertEquals("", page.items.single().backdrop)
    }

    @Test fun `live categories keep valid, unique, non-adult entries up to the cap`() {
        val json = """[
            {"id":"news","title":"Noticias","country":"co"},
            {"id":"news","title":"Otra vez"},
            {"id":"bad id","title":"X"},
            {"id":"x18","title":"Adultos","adult":true},
            {"id":"sports","title":"  "},
            {"id":"kids","title":"Infantil","country":"Colombia"}
        ]"""
        assertEquals(
            listOf(PluginLiveCategory("news", "Noticias", "CO"), PluginLiveCategory("kids", "Infantil", "")),
            PluginOutput.liveCategories(json).categories,
        )
        val many = (1..250).joinToString(",", "[", "]") { """{"id":"c$it","title":"C$it"}""" }
        assertEquals(PluginLiveContract.MAX_CATEGORIES, PluginOutput.liveCategories(many).categories.size)
        assertEquals(PluginLiveCatalog(emptyList(), emptyList()), PluginOutput.liveCategories("""{"items":[]}"""))
    }

    @Test fun `playlists are declared next to categories, strictly host-gated`() {
        val hosts = EffectiveHosts(listOf("lists.example.com"))
        val json = """[
            {"id":"news","title":"Noticias"},
            {"playlist":{"url":"https://lists.example.com/a.m3u","format":"m3u","headers":{"X-Token":"t"},
              "epg":{"url":"https://lists.example.com/g.xml.gz","format":"xmltv"},"refreshHours":6,"hideGroups":["XXX"],"resolve":true}},
            {"playlist":{"url":"https://elsewhere.example.org/b.m3u","format":"m3u"}},
            {"playlist":{"url":"https://lists.example.com/c.pls","format":"pls"}},
            {"playlist":{"url":"https://lists.example.com/d.m3u","format":"m3u","refreshHours":0,"epg":{"url":"https://elsewhere.example.org/g.xml","format":"xmltv"}}}
        ]"""
        val catalog = PluginOutput.liveCategories(json, hosts)
        assertEquals(listOf("news"), catalog.categories.map { it.id })
        assertEquals(
            listOf(
                PluginPlaylist("https://lists.example.com/a.m3u", mapOf("X-Token" to "t"), "https://lists.example.com/g.xml.gz", 6, setOf("xxx"), resolve = true),
                PluginPlaylist("https://lists.example.com/d.m3u", emptyMap(), "", PluginLiveContract.DEFAULT_REFRESH_HOURS, emptySet(), resolve = false),
            ),
            catalog.playlists,
        )
        val single = PluginOutput.liveCategories("""{"playlist":{"url":"https://lists.example.com/a.m3u","format":"m3u"}}""", hosts)
        assertEquals(listOf("https://lists.example.com/a.m3u"), single.playlists.map { it.url })
        val stringy = PluginOutput.liveCategories("""{"playlist":{"url":"https://lists.example.com/a.m3u","format":"m3u","resolve":"true"}}""", hosts)
        assertEquals(false, stringy.playlists.single().resolve)
        val tooMany = (1..15).joinToString(",", "[", "]") { """{"playlist":{"url":"https://lists.example.com/$it.m3u","format":"m3u"}}""" }
        assertEquals(PluginLiveContract.MAX_PLAYLISTS, PluginOutput.liveCategories(tooMany, hosts).playlists.size)
    }

    @Test fun `a live channels page keeps valid channels, gates the logo and keeps the cursor`() {
        val hosts = EffectiveHosts(listOf("cdn.example.com"))
        val page = PluginOutput.liveChannels(
            """{"items":[
                {"id":"c1","title":"Canal Uno","ref":"r1","categoryId":"news","number":7,"logo":"https://cdn.example.com/1.png"},
                {"id":"c1","title":"Repetido","ref":"r1b"},
                {"id":"c2","title":"Canal Dos","ref":"","number":3},
                {"id":"c3","title":"Canal Tres","ref":"r3","number":10000,"logo":"http://192.168.1.2/3.png","categoryId":"no good"},
                {"id":"c4","title":"Adultos","ref":"r4","adult":true}
            ],"next":"p2"}""",
            hosts,
        )
        assertEquals(
            listOf(
                PluginLiveChannelItem("c1", "Canal Uno", "r1", "https://cdn.example.com/1.png", 7, "news"),
                PluginLiveChannelItem("c3", "Canal Tres", "r3", "", 0, ""),
            ),
            page.items,
        )
        assertEquals("p2", page.next)
        val plain = PluginOutput.liveChannels("""[{"id":"a","title":"A","ref":"ra"}]""")
        assertEquals(listOf("a"), plain.items.map { it.id })
        assertNull(plain.next)
        val big = (1..600).joinToString(",", "{\"items\":[", "]}") { """{"id":"c$it","title":"C$it","ref":"r$it"}""" }
        assertEquals(PluginLiveContract.MAX_CHANNELS_PER_PAGE, PluginOutput.liveChannels(big).items.size)
    }

    @Test fun `a channel may carry an inline stream instead of a ref, checked like resolve's`() {
        val hosts = EffectiveHosts(listOf("cdn.example.com"))
        val page = PluginOutput.liveChannels(
            """{"items":[
                {"id":"a","title":"Directo","stream":{"url":"https://cdn.example.com/a.m3u8","mime":"application/vnd.apple.mpegurl","headers":{"Referer":"https://cdn.example.com/"}}},
                {"id":"b","title":"Ajeno","stream":{"url":"https://evil.example.org/b.m3u8"}},
                {"id":"c","title":"Nada"},
                {"id":"~d","title":"Reservado","ref":"r"},
                {"id":"e","title":"Ambos","ref":"re","stream":{"url":"https://cdn.example.com/e.m3u8"}}
            ]}""",
            hosts,
        )
        assertEquals(listOf("a", "e"), page.items.map { it.id })
        assertEquals("https://cdn.example.com/a.m3u8", page.items[0].stream!!.url)
        assertEquals("", page.items[0].ref)
        assertEquals(mapOf("Referer" to "https://cdn.example.com/"), page.items[0].stream!!.headers)
        // Both given: the inline stream plays, the ref is kept only as the fallback resolve() target.
        assertEquals("re", page.items[1].ref)
        assertEquals("https://cdn.example.com/e.m3u8", page.items[1].stream!!.url)
    }

    @Test fun `the guide keeps asked-for channels inside the window, sorted and capped`() {
        val from = 1_000_000L
        val to = from + 3_600_000L
        val json = """[
            {"channelId":"c1","title":"Tarde","start":${from + 1_800_000},"end":${from + 3_600_000}},
            {"channelId":"c1","title":"Mañana","start":$from,"end":${from + 1_800_000},"description":"Noticias"},
            {"channelId":"c1","title":"Duplicado","start":$from,"end":${from + 60_000}},
            {"channelId":"zz","title":"No pedido","start":$from,"end":${from + 60_000}},
            {"channelId":"c1","title":"Antes","start":${from - 7_200_000},"end":${from - 3_600_000}},
            {"channelId":"c1","title":"Al revés","start":${from + 10},"end":${from + 5}},
            {"channelId":"c1","title":" ","start":$from,"end":${from + 60_000}}
        ]"""
        assertEquals(
            listOf(
                PluginGuideEntry("c1", "Mañana", from, from + 1_800_000, "Noticias"),
                PluginGuideEntry("c1", "Tarde", from + 1_800_000, from + 3_600_000),
            ),
            PluginOutput.guide(json, setOf("c1"), from, to),
        )
        val many = (0 until 150).joinToString(",", "[", "]") { """{"channelId":"c1","title":"P$it","start":${from + it},"end":${from + it + 1}}""" }
        assertEquals(PluginLiveContract.MAX_GUIDE_ENTRIES_PER_CHANNEL, PluginOutput.guide(many, setOf("c1"), from, to).size)
        assertEquals(emptyList<PluginGuideEntry>(), PluginOutput.guide("{}", setOf("c1"), from, to))
    }

    @Test fun `with any public live host the stream url relaxes, but not its subtitles nor its license`() {
        val any = EffectiveHosts(listOf("declared.example.com"), anyPublicLiveHost = true)
        val s = PluginOutput.stream(
            """{"url":"http://cdn.iptv-somewhere.net/1.m3u8","subtitles":[{"lang":"es","url":"https://subs.elsewhere.org/a.vtt"}]}""", any,
        )
        assertEquals("http://cdn.iptv-somewhere.net/1.m3u8", s.url)
        assertEquals(emptyList<PluginSubtitle>(), s.subtitles)
        assertThrows(PluginContractException::class.java) {
            PluginOutput.stream("""{"url":"http://192.168.1.4/1.m3u8"}""", any)
        }
        assertThrows(PluginContractException::class.java) {
            PluginOutput.stream("""{"url":"https://cdn.iptv-somewhere.net/1.mpd","drm":{"type":"widevine","licenseUrl":"https://lic.elsewhere.org/"}}""", any, allowDrm = true)
        }
    }

    @Test fun `with any public live host audio tracks and playlist downloads stay strict`() {
        val any = EffectiveHosts(listOf("declared.example.com"), anyPublicLiveHost = true)
        val s = PluginOutput.stream(
            """{"url":"http://203.0.113.7:8080/1.m3u8","audioTracks":[{"lang":"es","url":"https://audio.elsewhere.org/a.m4a"}]}""", any,
        )
        assertEquals("http://203.0.113.7:8080/1.m3u8", s.url)
        assertEquals(emptyList<PluginAudioTrack>(), s.audioTracks)
        listOf("http://[fd00::1]/1.m3u8", "http://10.0.0.2/1.m3u8", "http://tv.lan/1.m3u8", "http://localhost/1.m3u8").forEach { u ->
            assertThrows(u, PluginContractException::class.java) { PluginOutput.stream("""{"url":"$u"}""", any) }
        }
        // The app downloads a playlist and its guide itself: never under "any".
        assertEquals(false, PluginOutput.allowsUrl("https://lists.elsewhere.org/a.m3u", any))
        val catalog = PluginOutput.liveCategories("""[{"playlist":{"url":"https://lists.elsewhere.org/a.m3u","format":"m3u"}}]""", any)
        assertEquals(0, catalog.playlists.size)
    }

    @Test fun `under any, a typed server's name on another port is refused, the server itself plays`() {
        val any = EffectiveHosts(listOf("declared.example.com"), listOfNotNull(PluginHosts.userHostOf("http://nas-name:8096")), anyPublicLiveHost = true)
        assertEquals("http://nas-name:8096/1.m3u8", PluginOutput.stream("""{"url":"http://nas-name:8096/1.m3u8"}""", any).url)
        assertThrows(PluginContractException::class.java) { PluginOutput.stream("""{"url":"http://nas-name:22/x"}""", any) }
    }

    @Test fun `under any, a public IPv6 literal is refused without calling it local`() {
        val any = EffectiveHosts(listOf("declared.example.com"), anyPublicLiveHost = true)
        val e = assertThrows(PluginContractException::class.java) { PluginOutput.stream("""{"url":"http://[2001:4860:4860::8888]/1.m3u8"}""", any) }
        assertEquals("Los canales solo pueden usar direcciones IPv4 públicas o nombres de dominio", e.message)
        val lan = assertThrows(PluginContractException::class.java) { PluginOutput.stream("""{"url":"http://10.0.0.2/1.m3u8"}""", any) }
        assertEquals("El video apunta a 10.0.0.2, una dirección local", lan.message)
    }
}
