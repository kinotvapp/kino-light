package com.arkiv.player.ui.catalog

import androidx.compose.ui.graphics.Color
import com.arkiv.player.data.gateway.GatewayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySourcePluginTest {
    private fun plugin(kind: String = "movie") = PlaySource.Plugin(
        "demo", "Demo", 0xFFE0A030,
        GatewayResult(source = "plugin:demo", title = "Uno", ref = "plg1:demo:x", kind = kind, extra = mapOf("poster" to "https://p")),
    )

    @Test fun `accent is the manifest color`() = assertEquals(Color(0xFFE0A030), accentOf(plugin()))
    @Test fun `poster comes from extra`() = assertEquals("https://p", posterFor(plugin()))
    @Test fun `series by kind`() {
        assertTrue(plugin("series").isSeries())
        assertFalse(plugin().isSeries())
    }
}
