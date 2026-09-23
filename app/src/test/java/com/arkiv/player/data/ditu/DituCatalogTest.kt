package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituCatalogTest {

    private val TRAY = "TRAY/SEARCH/VOD"

    @Test fun `the catalog is requested with an empty query`() = runTest {
        val fake = FakeDituClient()
        DituCatalog(fake).catalog()

        val (path, params) = fake.calls.first()
        assertEquals(TRAY, path)
        assertEquals("", params["query"])
    }

    @Test fun `search sends the text`() = runTest {
        val fake = FakeDituClient()
        DituCatalog(fake).search("rigo")

        assertEquals("rigo", fake.calls.first().second["query"])
    }

    @Test fun `series and movies stay, and nothing else`() = runTest {
        val fake = FakeDituClient()
        fake.respond(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"Serie A","contentType":"BUNDLE","pictureUrl":"pa"}},
          {"id":"2","metadata":{"title":"Grupo B","contentType":"GROUP_OF_BUNDLES","pictureUrl":"pb"}},
          {"id":"3","metadata":{"title":"Peli C","contentType":"VOD","contentSubtype":"MOVIE","pictureUrl":"pc"}},
          {"id":"4","metadata":{"title":"Clip D","contentType":"VOD","contentSubtype":"CLIP"}},
          {"id":"5","metadata":{"title":"Vivo E","contentType":"LIVE"}}
        ]}}
        """)

        val items = DituCatalog(fake).catalog()

        assertEquals(listOf("1", "2", "3"), items.map { it.contentId })
        assertEquals(listOf("BUNDLE", "GROUP_OF_BUNDLES", "VOD"), items.map { it.contentType })
        assertEquals(listOf(false, false, true), items.map { it.isMovie })
    }

    @Test fun `with no id or no title the item gets discarded`() = runTest {
        val fake = FakeDituClient()
        fake.respond(TRAY, """
        {"resultObj":{"containers":[
          {"metadata":{"title":"Sin id","contentType":"BUNDLE"}},
          {"id":"9","metadata":{"title":"","contentType":"BUNDLE"}},
          {"id":"10","metadata":{"title":"Buena","contentType":"BUNDLE"}}
        ]}}
        """)

        assertEquals(listOf("10"), DituCatalog(fake).catalog().map { it.contentId })
    }

    /** The poster is built by hand against Caracol's own CDN from `pictureUrl`. */
    @Test fun `the poster comes from Caracol's CDN`() = runTest {
        val fake = FakeDituClient()
        fake.respond(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE","pictureUrl":"carpeta/img"}}
        ]}}
        """)

        assertEquals(
            "https://image-registry.ditu.caracoltv.com/carpeta/img/portrait-thin-promotional-tablet.jpg",
            DituCatalog(fake).catalog().first().posterUrl,
        )
    }

    /** With no `pictureUrl` it falls back to the container's own posterList before going with no image. */
    @Test fun `with no pictureUrl it uses the posterList`() = runTest {
        val fake = FakeDituClient()
        fake.respond(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE"},
           "posterList":[{"fileType":"otro","fileUrl":"https://x/no.jpg"},
                         {"fileType":"icon","fileUrl":"https://x/si.jpg"}]}
        ]}}
        """)

        assertEquals("https://x/si.jpg", DituCatalog(fake).catalog().first().posterUrl)
    }

    @Test fun `the year comes from the first date field with four digits`() = runTest {
        val fake = FakeDituClient()
        fake.respond(TRAY, """
        {"resultObj":{"containers":[
          {"id":"1","metadata":{"title":"A","contentType":"BUNDLE","releaseDate":"2019-04-02"}},
          {"id":"2","metadata":{"title":"B","contentType":"BUNDLE","releaseYear":"2021"}},
          {"id":"3","metadata":{"title":"C","contentType":"BUNDLE","releaseDate":"nada"}}
        ]}}
        """)

        assertEquals(listOf("2019", "2021", ""), DituCatalog(fake).catalog().map { it.year })
    }

    // --- live channels -------------------------------------------------------

    @Test fun `channels are requested ordered by orderId`() = runTest {
        val fake = FakeDituClient()
        DituCatalog(fake).channels()

        val (path, params) = fake.calls.first()
        assertEquals("TRAY/LIVECHANNELS", path)
        assertEquals("orderId", params["orderBy"])
        assertEquals("asc", params["sortOrder"])
    }

    /**
     * The assetId comes from THIS response and not the EPG: the EPG returns an empty `assets` for
     * the current program, so requesting it there is a trip that comes back with nothing.
     */
    @Test fun `each channel's id, name, logo and assetId come from the MASTER`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":7,"channelName":"Caracol","isActive":true,"orderId":1},
           "assets":[{"assetType":"OTRO","assetId":11,"logoSmall":"s.png"},
                     {"assetType":"MASTER","assetId":22,"logoMedium":"m.png"}]}
        ]}}
        """)

        val channel = DituCatalog(fake).channels().single()
        assertEquals(7, channel.channelId)
        assertEquals("Caracol", channel.name)
        assertEquals(22, channel.assetId)
        assertEquals("m.png", channel.logoUrl)
    }

    @Test fun `with no MASTER, the first asset with an id is used`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":7,"channelName":"Caracol","isActive":true},
           "assets":[{"assetType":"OTRO","assetId":11}]}
        ]}}
        """)

        assertEquals(11, DituCatalog(fake).channels().single().assetId)
    }

    @Test fun `inactive or nameless channels don't get in`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":1,"channelName":"Apagado","isActive":false},"assets":[{"assetId":9}]},
          {"metadata":{"channelId":2,"channelName":"","isActive":true},"assets":[{"assetId":9}]},
          {"metadata":{"channelId":3,"channelName":"Bueno","isActive":true},"assets":[{"assetId":9}]}
        ]}}
        """)

        assertEquals(listOf(3), DituCatalog(fake).channels().map { it.channelId })
    }

    /** A channel with no assetId at all can't be played: there's no point offering it. */
    @Test fun `a channel with no assetId doesn't get in`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/LIVECHANNELS", """
        {"resultObj":{"containers":[
          {"metadata":{"channelId":4,"channelName":"Sin asset","isActive":true},"assets":[]}
        ]}}
        """)

        assertTrue(DituCatalog(fake).channels().isEmpty())
    }
}
