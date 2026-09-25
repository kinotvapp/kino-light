package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginErrorsTest {
    @Test fun `typed errors read in Bogota Spanish with the plugin name`() {
        assertEquals("Configura Jellyfin en Ajustes ▸ Plugins", PluginErrors.userMessage("auth_required", "Jellyfin"))
        assertEquals("No se encontró en Jellyfin", PluginErrors.userMessage("not_found", "Jellyfin"))
        assertEquals("Este contenido no está disponible en tu región", PluginErrors.userMessage("geo_blocked", "Jellyfin"))
        assertEquals("Jellyfin está limitando las peticiones; intenta en unos minutos", PluginErrors.userMessage("rate_limited", "Jellyfin"))
        assertEquals("Jellyfin no está disponible ahora", PluginErrors.userMessage("unavailable", "Jellyfin"))
        listOf("timeout", "network", "crypto_error", "unknown").forEach { assertEquals(null, PluginErrors.userMessage(it, "X")) }
    }

    @Test fun `the engine message of a typed error is parsed, anything else is not`() {
        with(PluginErrors.fromEngineMessage("KinoError_rate_limited: espera\n    at <anonymous> (main.js)")!!) {
            assertEquals("rate_limited", code)
            assertEquals("espera", message)
        }
        assertEquals(null, PluginErrors.fromEngineMessage("Error: KinoError_rate_limited: x"))
        assertEquals(null, PluginErrors.fromEngineMessage("KinoError_Bad-Code: x"))
        assertEquals(null, PluginErrors.fromEngineMessage(null))
        assertEquals(200, PluginErrors.fromEngineMessage("KinoError_not_found: " + "m".repeat(10_000))!!.message!!.length)
        assertEquals(null, PluginErrors.fromEngineMessage("x".repeat(3_000) + "KinoError_not_found: m"))
    }
}
