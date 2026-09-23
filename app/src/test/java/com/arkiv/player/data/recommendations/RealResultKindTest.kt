package com.arkiv.player.data.recommendations

import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MagisSource` builds every result with `kind = ctx.type` (the SEARCHED type, not the item's): when
 * searching a series, movies from Magis's pool arrive labeled "tv". [realKindOfRef] reads the real
 * `kind` from the ref itself, before the referee sees the list.
 */
class RealResultKindTest {

    @Test fun `a magis movie ref gives movie`() {
        assertEquals("movie", realKindOfRef("magis1:movie:0:abc"))
    }

    @Test fun `a magis series ref gives tv`() {
        assertEquals("tv", realKindOfRef("magis1:teleplay:3:abc"))
    }

    @Test fun `a caracol movie ref gives movie`() {
        assertEquals("movie", realKindOfRef("ditu1:VOD:abc"))
    }

    @Test fun `a caracol series ref gives tv`() {
        assertEquals("tv", realKindOfRef("ditu1:GROUP_OF_BUNDLES:abc"))
    }

    @Test fun `a ref that is not understood gives null`() {
        assertNull(realKindOfRef("torrent:magnet-cualquiera"))
    }

    @Test fun `withRealKind fixes the result's kind`() {
        val r = GatewayResult(source = "magis", title = "X", ref = "magis1:teleplay:1:abc", kind = "movie")
        assertEquals("tv", withRealKind(r).kind)
    }

    @Test fun `withRealKind leaves the kind unchanged if the ref is not understood`() {
        val r = GatewayResult(source = "raro", title = "X", ref = "no-se-entiende", kind = "movie")
        assertEquals("movie", withRealKind(r).kind)
    }
}
