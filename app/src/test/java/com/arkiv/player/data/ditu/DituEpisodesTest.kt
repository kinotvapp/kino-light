package com.arkiv.player.data.ditu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DituEpisodesTest {

    private fun bundleWith(vararg eps: String) = """
    {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},
      "containers":[${eps.joinToString(",")}]}]}}
    """

    private fun ep(id: String, num: Int, season: Int?, title: String, asset: Int? = 1): String {
        val seasonField = season?.let { ""","season":$it""" } ?: ""
        val assets = asset?.let { ""","assets":[{"assetType":"MASTER","assetId":$it}]""" } ?: ""
        return """{"id":"$id","metadata":{"episodeNumber":$num,"episodeTitle":"$title"$seasonField}$assets}"""
    }

    @Test fun `a BUNDLE lists its chapters with their season`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", bundleWith(
            ep("e1", 1, 2, "Uno"),
            ep("e2", 2, 2, "Dos"),
        ))

        val t = DituEpisodes(fake).forRef(DituRef("99", "BUNDLE"))

        assertEquals(listOf(1, 2), t.episodes.map { it.number })
        assertEquals(listOf(2, 2), t.episodes.map { it.season })
        assertEquals(listOf("Uno", "Dos"), t.episodes.map { it.title })
        assertEquals("Rigo", t.seriesTitle)
        assertEquals(2, t.season)
    }

    @Test fun `with no season the chapter is season 1`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", bundleWith(ep("e1", 1, null, "Uno")))

        assertEquals(1, DituEpisodes(fake).forRef(DituRef("99", "BUNDLE")).episodes.single().season)
    }

    @Test fun `with no title the chapter is named by its number`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", bundleWith(ep("e1", 7, 1, "")))

        assertEquals("Episodio 7", DituEpisodes(fake).forRef(DituRef("99", "BUNDLE")).episodes.single().title)
    }

    /**
     * A chapter with no `episodeNumber` (or with 0) takes its position within the bundle. With 0
     * it would get saved as a standalone movie, and two with no number would get the same
     * chapter id in the library.
     */
    @Test fun `with no episodeNumber the number is the position in the bundle`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", bundleWith(
            """{"id":"e1","metadata":{"episodeTitle":"Uno"},"assets":[{"assetType":"MASTER","assetId":1}]}""",
            ep("e2", 0, 1, "Dos"),
        ))

        val eps = DituEpisodes(fake).forRef(DituRef("99", "BUNDLE")).episodes

        assertEquals(listOf(1, 2), eps.map { it.number })
        val ids = eps.map { com.arkiv.player.data.DituEntities.chapterEpisodeId("ditu:99", it.season, it.number) }
        assertEquals(2, ids.toSet().size)
    }

    /** With no assetId it can't be played: showing it would offer something that fails on tap. */
    @Test fun `a chapter with no assetId doesn't get in`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", bundleWith(
            ep("e1", 1, 1, "No asset", asset = null),
            ep("e2", 2, 1, "With asset"),
        ))

        assertEquals(listOf("e2"), DituEpisodes(fake).forRef(DituRef("99", "BUNDLE")).episodes.map { it.contentId })
    }

    /**
     * THE TRAP. In a group, each chapter's season is its bundle's POSITION in the children's
     * list, not the `season` the episode carries: a group's bundles usually all come with
     * `season: 1`, and without this the four seasons would overwrite each other.
     */
    @Test fun `in a group the season is the bundle's position`() = runTest {
        val fake = FakeDituClient()
        fake.respond("TRAY/SEARCH/VOD", """
        {"resultObj":{"containers":[{"id":"b1"},{"id":"b2"}]}}
        """)
        fake.respond("CONTENT/DETAIL/BUNDLE/b1", bundleWith(ep("e1", 1, 1, "T1E1")))
        fake.respond("CONTENT/DETAIL/BUNDLE/b2", bundleWith(ep("e2", 1, 1, "T2E1")))

        val t = DituEpisodes(fake).forRef(DituRef("g9", "GROUP_OF_BUNDLES"))

        assertEquals(listOf(1, 2), t.episodes.map { it.season })
        assertEquals(listOf("e1", "e2"), t.episodes.map { it.contentId })
    }

    @Test fun `the group's children are requested filtering by parentId`() = runTest {
        val fake = FakeDituClient()
        DituEpisodes(fake).forRef(DituRef("g9", "GROUP_OF_BUNDLES"))

        val (path, params) = fake.calls.first()
        assertEquals("TRAY/SEARCH/VOD", path)
        assertEquals("g9", params["filter_parentId"])
        assertEquals("BUNDLE", params["filter_contentType"])
    }

    @Test fun `a bundle with no containers doesn't blow up, returns empty`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", """{"resultObj":{"containers":[]}}""")

        val t = DituEpisodes(fake).forRef(DituRef("99", "BUNDLE"))
        assertTrue(t.episodes.isEmpty())
    }

    @Test fun `images come from Caracol's CDN`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", bundleWith(ep("e1", 1, 1, "Uno")))

        val t = DituEpisodes(fake).forRef(DituRef("99", "BUNDLE"))
        assertEquals("https://image-registry.ditu.caracoltv.com/pic/portrait-thin-promotional-tablet.jpg", t.posterUrl)
        assertEquals("https://image-registry.ditu.caracoltv.com/pic/landscape-regular-clean-tablet.jpg", t.backdropUrl)
    }

    /**
     * A chapter with multiple assets gets in the list even if MASTER isn't the first one.
     * The only test in this file exercising the multiple-assets-per-episode case.
     * MASTER's real preference is covered in `DituCatalogTest`, by the channels test, because
     * `channelFrom()` uses `assetMaster()`.
     */
    @Test fun `a chapter with several assets gets in even if MASTER isn't the first`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/BUNDLE/99", """
        {"resultObj":{"containers":[{"metadata":{"title":"Rigo","pictureUrl":"pic"},
          "containers":[
            {"id":"e1","metadata":{"episodeNumber":1,"episodeTitle":"Uno"},
             "assets":[
               {"assetType":"OTRO","assetId":11},
               {"assetType":"MASTER","assetId":22}
             ]
            }
          ]
        }]}}
        """)

        val t = DituEpisodes(fake).forRef(DituRef("99", "BUNDLE"))

        assertEquals(listOf("e1"), t.episodes.map { it.contentId })
    }
}
