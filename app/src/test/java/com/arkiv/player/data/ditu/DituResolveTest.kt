package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituResolveTest {

    private val LIC = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL/CONTENT/LICENSE"

    private fun readyFake(): FakeDituClient {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.respond("CONTENT/USERDATA/VOD/42", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.respond("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{"src":"https://cdn/movie.mpd"}}""")
        fake.token = "tok999"
        return fake
    }

    @Test fun `a movie resolves in three steps and in order`() = runTest {
        val fake = readyFake()

        val play = DituResolve(fake).vod(DituRef("42", "VOD"))

        assertEquals(
            listOf("CONTENT/DETAIL/VOD/42", "CONTENT/USERDATA/VOD/42", "CONTENT/VIDEOURL/VOD/42/7"),
            fake.calls.map { it.first },
        )
        assertEquals("https://cdn/movie.mpd", play.url)
        assertEquals("application/dash+xml", play.mime)
        assertEquals("ditu", play.kind)
    }

    /** The license with no cookie answers 500: the token HAS TO reach it as a license header. */
    @Test fun `the token cookie travels as the license's header`() = runTest {
        val play = DituResolve(readyFake()).vod(DituRef("42", "VOD"))

        assertEquals(LIC, play.drmLicenseUrl)
        assertEquals(mapOf("Cookie" to "playback_token=tok999"), play.drmLicenseHeaders)
    }

    /** With no token something playable is still returned: what fails afterward is the license
     *  server, and its 500 is diagnosed better than an error we made up beforehand. */
    @Test fun `with no token, no cookie header is sent`() = runTest {
        val fake = readyFake()
        fake.token = ""

        val play = DituResolve(fake).vod(DituRef("42", "VOD"))

        assertEquals(LIC, play.drmLicenseUrl)
        assertTrue(play.drmLicenseHeaders.isEmpty())
    }

    @Test fun `an entitlement block cuts off before asking for the URL`() = runTest {
        val fake = readyFake()
        fake.respond("CONTENT/USERDATA/VOD/42", """
        {"resultObj":{"containers":[{"entitlement":{"isGeoBlocked":true}}]}}
        """)

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()

        assertTrue(e is DituException)
        assertTrue(e!!.message!!.contains("solo disponible en Colombia"))
        assertTrue("shouldn't have asked for the URL", fake.calls.none { it.first.startsWith("CONTENT/VIDEOURL") })
    }

    @Test fun `with no assetId it can't be resolved`() = runTest {
        val fake = readyFake()
        fake.respond("CONTENT/DETAIL/VOD/42", """{"resultObj":{"containers":[{"assets":[]}]}}""")

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()
        assertTrue(e is DituException)
    }

    @Test fun `with no src it can't be played`() = runTest {
        val fake = readyFake()
        fake.respond("CONTENT/VIDEOURL/VOD/42/7", """{"resultObj":{}}""")

        val e = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()
        assertTrue(e is DituException)
    }

    /** A BUNDLE isn't playable: its first chapter with an assetId gets resolved. */
    @Test fun `a BUNDLE resolves its first chapter`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"noasset","metadata":{}},
          {"id":"e2","metadata":{},"assets":[{"assetType":"MASTER","assetId":5}]}
        ]}]}}
        """)
        fake.respond("CONTENT/USERDATA/VOD/e2", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.respond("CONTENT/VIDEOURL/VOD/e2/5", """{"resultObj":{"src":"https://cdn/e2.mpd"}}""")

        val play = DituResolve(fake).vod(DituRef("99", "BUNDLE"))

        assertEquals("https://cdn/e2.mpd", play.url)
    }

    /** A GROUP_OF_BUNDLES resolves the first chapter of its first child bundle. */
    @Test fun `a GROUP_OF_BUNDLES resolves the first chapter of its first bundle`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        fake.respond("CONTENT/DETAIL/BUNDLE/b1", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"ep1","metadata":{},"assets":[{"assetType":"MASTER","assetId":11}]}
        ]}]}}
        """)
        fake.respond("CONTENT/USERDATA/VOD/ep1", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.respond("CONTENT/VIDEOURL/VOD/ep1/11", """{"resultObj":{"src":"https://cdn/group.mpd"}}""")
        fake.token = "tokgroup"

        val play = DituResolve(fake).vod(DituRef("999", "GROUP_OF_BUNDLES"))

        assertEquals("https://cdn/group.mpd", play.url)
        // Verify the child bundles' list was requested with the right parameters
        val trayCalls = fake.calls.filter { it.first == "TRAY/SEARCH/VOD" }
        assertTrue(trayCalls.isNotEmpty())
        val params = trayCalls.first().second
        assertEquals("999", params["filter_parentId"])
        assertEquals("BUNDLE", params["filter_contentType"])
    }

    /** If the first bundle has no playable chapters, it uses the second. */
    @Test fun `a GROUP_OF_BUNDLES skips bundles with no playable chapters`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        // First bundle with no playable chapters
        fake.respond("CONTENT/DETAIL/BUNDLE/b1", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"ep1","metadata":{}}
        ]}]}}
        """)
        // Second bundle does have one
        fake.respond("CONTENT/DETAIL/BUNDLE/b2", """
        {"resultObj":{"containers":[{"containers":[
          {"id":"ep2","metadata":{},"assets":[{"assetType":"MASTER","assetId":22}]}
        ]}]}}
        """)
        fake.respond("CONTENT/USERDATA/VOD/ep2", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.respond("CONTENT/VIDEOURL/VOD/ep2/22", """{"resultObj":{"src":"https://cdn/second.mpd"}}""")

        val play = DituResolve(fake).vod(DituRef("999", "GROUP_OF_BUNDLES"))

        assertEquals("https://cdn/second.mpd", play.url)
    }

    // --- live ---------------------------------------------------------------

    /** Live is TWO steps: the assetId already came with the channel, so there's no DETAIL. */
    @Test fun `a live channel resolves in two steps`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/USERDATA/LIVE/7", """{"resultObj":{"containers":[{"entitlement":{}}]}}""")
        fake.respond("CONTENT/VIDEOURL/LIVE/7/22", """{"resultObj":{"src":"https://cdn/live.mpd"}}""")
        fake.token = "toklive"

        val play = DituResolve(fake).live(DituChannel(7, "Caracol", "l.png", 22))

        assertEquals(listOf("CONTENT/USERDATA/LIVE/7", "CONTENT/VIDEOURL/LIVE/7/22"), fake.calls.map { it.first })
        assertEquals("https://cdn/live.mpd", play.url)
        assertEquals(mapOf("Cookie" to "playback_token=toklive"), play.drmLicenseHeaders)
    }

    @Test fun `a blocked channel says why`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/USERDATA/LIVE/7", """
        {"resultObj":{"containers":[{"entitlement":{"isChannelNotSubscribed":true}}]}}
        """)

        val e = runCatching { DituResolve(fake).live(DituChannel(7, "C", "", 22)) }.exceptionOrNull()

        assertTrue(e!!.message!!.contains("requiere suscripción"))
    }
}
