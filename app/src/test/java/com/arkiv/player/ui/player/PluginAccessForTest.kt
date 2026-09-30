package com.arkiv.player.ui.player

import com.arkiv.player.data.live.OwnLive
import com.arkiv.player.data.plugin.PluginAccess
import com.arkiv.player.data.plugin.PluginPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAccessForTest {
    private class Registry(val answer: PluginAccess) : PluginPlayback {
        var asked: String? = null
        override fun accessFor(pluginId: String?): PluginAccess {
            asked = pluginId
            return answer
        }
        override fun nameOf(pluginId: String?): String? = null
    }

    @Test fun `the own provider is answered without asking the registry`() {
        val r = Registry(PluginAccess.Uninstalled("own"))
        val a = pluginAccessFor(OwnLive.PLUGIN_ID, r) as PluginAccess.Ready
        assertTrue(a.liveHosts.anyPublicLiveHost)
        assertEquals(null, r.asked)
    }

    @Test fun `any other plugin still goes through the registry`() {
        val r = Registry(PluginAccess.Uninstalled("x"))
        assertTrue(pluginAccessFor("archive-org", r) is PluginAccess.Uninstalled)
        assertEquals("archive-org", r.asked)
    }

    @Test fun `no registry means uninstalled, never the own access by accident`() {
        assertTrue(pluginAccessFor("archive-org", null) is PluginAccess.Uninstalled)
        assertTrue(pluginAccessFor(null, null) is PluginAccess.Uninstalled)
    }
}
