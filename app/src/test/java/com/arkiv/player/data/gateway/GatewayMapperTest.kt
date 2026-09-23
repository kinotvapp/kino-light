package com.arkiv.player.data.gateway

import com.arkiv.player.ui.catalog.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayMapperTest {

    @Test
    fun `magis maps to its own type`() {
        // Without this branch the mapper used to return null and Magis results never made it to
        // the screen, even though the gateway was delivering them.
        val ps = GatewayResult(
            source = "magis", title = "Duna", ref = "r", year = "2021",
            extra = mapOf("content_id" to "abc", "program_type" to "movie"),
        ).toPlaySource()
        assertTrue(ps is PlaySource.Magis)
        assertEquals("Duna", (ps as PlaySource.Magis).result.title)
        assertEquals("abc", ps.result.extra["content_id"])
        assertEquals("r", ps.result.ref)
    }

    @Test
    fun `ditu maps to its own type`() {
        // Without this branch Caracol's results used to get discarded here: the composite source
        // delivered them and they never made it to the screen.
        val ps = GatewayResult(
            source = "ditu", title = "Rigo", ref = "ditu1:BUNDLE:9", kind = "series",
            extra = mapOf("poster" to "p.jpg", "content_type" to "BUNDLE"),
        ).toPlaySource()
        assertTrue(ps is PlaySource.Ditu)
        assertEquals("Rigo", (ps as PlaySource.Ditu).result.title)
        assertEquals("ditu1:BUNDLE:9", ps.result.ref)
        assertEquals("series", ps.result.kind)
        assertEquals("p.jpg", ps.result.extra["poster"])
    }

    @Test
    fun `the known sources all map -- none falls through to null`() {
        // Guards against the real bug: the composite source delivers these two and the mapper has
        // to know both of them. "archive" is NOT in this list on purpose: it was deleted in the
        // light-magis pruning (see the test below).
        for (source in listOf("magis", "ditu")) {
            val r = GatewayResult(source = source, title = "x", ref = "r")
            assertTrue("source '$source' doesn't map", r.toPlaySource() != null)
        }
    }

    @Test
    fun `archive, torrent and web are ignored on purpose -- deleted from this branch`() {
        // This APK no longer knows what to do with them and discards them just like any other
        // future unknown source. archive.org was deleted in the light-magis pruning; torrent/web
        // had already been deleted in Task 2.
        assertNull(GatewayResult(source = "archive", title = "x", ref = "r").toPlaySource())
        assertNull(GatewayResult(source = "torrent", title = "x", ref = "r").toPlaySource())
        assertNull(GatewayResult(source = "web", title = "x", ref = "r").toPlaySource())
    }

    @Test
    fun `an unknown source is discarded without blowing up`() {
        // If the server adds a source this APK doesn't know about, it's ignored instead of failing.
        assertNull(GatewayResult(source = "fuente_nueva", title = "x", ref = "r").toPlaySource())
    }

    @Test
    fun `the ref survives the mapping`() {
        // Without this there's nothing to resolve against later: /v1/resolve only understands the
        // ref. Magis maps the whole GatewayResult, so this is mostly a regression guard.
        assertEquals(
            "r",
            (GatewayResult(source = "magis", title = "x", ref = "r").toPlaySource()
                as PlaySource.Magis).result.ref,
        )
    }
}
