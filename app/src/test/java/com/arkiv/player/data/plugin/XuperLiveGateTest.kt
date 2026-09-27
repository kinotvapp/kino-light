package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native Xuper live channels exist only while the recognized Xuper plugin install
 * ([XuperPrivilege.grants]) is switched on and intact. Unresponsive does NOT close the gate: that
 * mark comes from VOD script timeouts, and live never runs the plugin's script.
 */
class XuperLiveGateTest {

    private fun plugin(
        id: String = "xuper",
        address: String = XuperPrivilege.SOURCE_REPO,
        name: String = "Xuper",
        enabled: Boolean = true,
        damaged: Boolean = false,
        unresponsive: Boolean = false,
    ) = InstalledPlugin(
        manifest = PluginManifest(id, name, "1.0.0", 1, "plugin.js", "", "", "", emptyList(), setOf("home"), null, null),
        record = InstalledRecord(address, "1.0.0", "sha", emptyList(), 1L, enabled = enabled, unresponsive = unresponsive, damaged = damaged),
        iconFile = null,
    )

    @Test fun `the recognized install, enabled and intact, turns xuper live on`() {
        assertTrue(xuperLiveAllowed(listOf(plugin())))
        assertTrue(xuperLiveAllowed(listOf(plugin("demo", "someone/kino-plugin-demo"), plugin("mi-xuper"))))
        assertEquals(setOf(LiveProvider.XUPER), liveProviders(listOf(plugin())))
        assertNull(XuperLiveGate.blockedMessage(listOf(plugin())))
    }

    @Test fun `an unresponsive xuper plugin still allows live`() {
        assertTrue(xuperLiveAllowed(listOf(plugin(unresponsive = true))))
    }

    @Test fun `disabled, damaged or uninstalled closes the gate`() {
        assertFalse(xuperLiveAllowed(listOf(plugin(enabled = false))))
        assertFalse(xuperLiveAllowed(listOf(plugin(damaged = true))))
        assertFalse(xuperLiveAllowed(emptyList()))
        assertFalse(xuperLiveAllowed(listOf(plugin("demo", "someone/kino-plugin-demo"))))
        assertEquals(emptySet<LiveProvider>(), liveProviders(emptyList()))
    }

    @Test fun `a copy of the xuper plugin from another repo or ref never opens the gate`() {
        assertFalse(xuperLiveAllowed(listOf(plugin(address = "someone-else/kino-plugin-xuper"))))
        assertFalse(xuperLiveAllowed(listOf(plugin(address = "${XuperPrivilege.SOURCE_REPO}@dev"))))
        assertFalse(xuperLiveAllowed(listOf(plugin(address = "${XuperPrivilege.SOURCE_REPO}/sub"))))
    }

    @Test fun `the blocked message says what to do, by the plugin's state`() {
        assertEquals("Instala el plugin Xuper para ver este canal", XuperLiveGate.blockedMessage(emptyList()))
        assertEquals(
            "Instala el plugin Xuper para ver este canal",
            XuperLiveGate.blockedMessage(listOf(plugin(address = "someone-else/kino-plugin-xuper"))),
        )
        assertEquals("Activa el plugin Xuper para ver este canal", XuperLiveGate.blockedMessage(listOf(plugin(enabled = false))))
        assertEquals(
            "El plugin Xuper tiene archivos dañados, reinstálalo",
            XuperLiveGate.blockedMessage(listOf(plugin(damaged = true))),
        )
    }
}
