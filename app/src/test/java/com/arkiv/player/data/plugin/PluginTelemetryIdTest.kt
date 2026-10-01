package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginTelemetryIdTest {
    private fun telemetry() = PluginTelemetry(facts = { null }, sink = { })

    /** ERRORES-9Q9/9PQ: the `owner/repo` an install reports is case-blind on GitHub, so it is lower-cased. */
    @Test fun `an install id typed in another case is the same issue`() {
        val upper = PluginFailure("YxhelZvl/lacartoons-plugin", "install:manifest", PluginFailureKind.INSTALL)
        val lower = PluginFailure("yxhelzvl/lacartoons-plugin", "install:manifest", PluginFailureKind.INSTALL)
        assertEquals(PluginTelemetry.fingerprintOf(lower), PluginTelemetry.fingerprintOf(upper))
        assertEquals(telemetry().eventOf(lower).message, telemetry().eventOf(upper).message)
        assertEquals("yxhelzvl/lacartoons-plugin", telemetry().eventOf(upper).tags["plugin_id"])
    }
}
