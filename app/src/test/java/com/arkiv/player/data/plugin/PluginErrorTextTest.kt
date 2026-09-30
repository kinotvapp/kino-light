package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An error a plugin's script threw reaches the person as one short Spanish sentence: no stack, no
 * "Error:", no "[Tag]", no URL. The raw text stays in the log.
 */
class PluginErrorTextTest {
    /** The Redmi's PelisPlusHD failure, as the engine formatted it. */
    private val streamWish = "Error: [StreamWish2] No se pudo convertir la URL a Hanerix: https://hglink.to/e/hzf2gnqi94cn\n" +
        "    at <anonymous> (plugin.js:1226)\n    at next (native)\n    at <anonymous> (plugin.js:713)\n" +
        "    at resolveStreamwish2 (plugin.js:1324)\n    at fulfilled (plugin.js:700)"

    private fun shown(e: Exception, function: String = "resolve", logs: MutableList<String> = mutableListOf()): String {
        val caller = PluginCaller { _, _, _, _ -> throw e }
        val failure = runCatching { runBlocking { PluginCalls.callOrThrow(caller, "p", "PelisPlusHD", function, "null", 20_000, log = { logs += it }) } }
        return failure.exceptionOrNull()!!.message!!
    }

    @Test fun `the stack trace, the Error prefix, the tag and the URL are gone`() {
        assertEquals("PelisPlusHD no pudo obtener el video: no se pudo convertir la URL a Hanerix", shown(PluginThrownException(streamWish)))
    }

    @Test fun `the rule, piece by piece`() {
        assertEquals("No se pudo convertir la URL a Hanerix", PluginErrorText.reason(streamWish))
        assertEquals("Sin enlaces disponibles", PluginErrorText.reason("TypeError: [A] [B] Sin enlaces disponibles"))
        assertEquals("Sin enlaces disponibles", PluginErrorText.reason("Uncaught Error: Sin enlaces disponibles at f (plugin.js:12)"))
        assertEquals("HLS HTTP 502", PluginErrorText.reason("[StreamWish2] HLS HTTP 502: https://x.example/master.txt"))
        assertEquals("Error al conectar con el servidor", PluginErrorText.reason("Error al conectar con el servidor"))
        assertEquals("server said no", PluginErrorText.reason("RangeError: server said no - https://a.example/b"))
    }

    @Test fun `code-like messages leave no reason`() {
        assertNull(PluginErrorText.reason("TypeError: cannot read property 'length' of undefined"))
        assertNull(PluginErrorText.reason("ReferenceError: foo is not defined"))
        assertNull(PluginErrorText.reason("SyntaxError: unexpected token: ''\n at x (plugin.js:1)"))
        assertNull(PluginErrorText.reason("Error: https://only.a.url/x"))
        assertNull(PluginErrorText.reason("Error: x.y(z) failed"))
        assertEquals("PelisPlusHD no pudo obtener el video", shown(PluginThrownException("TypeError: not a function\n    at a (plugin.js:3)")))
    }

    @Test fun `an empty or blank message is just the lead`() {
        assertNull(PluginErrorText.reason(null))
        assertNull(PluginErrorText.reason(""))
        assertNull(PluginErrorText.reason("Error: \n    at a (plugin.js:1)"))
        assertEquals("PelisPlusHD no pudo obtener el video", shown(PluginThrownException("")))
    }

    @Test fun `a long reason is cut at a word so the sentence stays short`() {
        val long = "Error: [Tag] " + "el servidor de videos devolvió una página distinta a la esperada ".repeat(5)
        val text = shown(PluginThrownException(long))
        assertTrue(text, text.length <= PluginErrorText.MAX_CHARS)
        assertTrue(text, text.startsWith("PelisPlusHD no pudo obtener el video: el servidor de videos"))
        assertTrue(text, text.endsWith("…"))
        assertFalse(text, text.contains(" …"))
    }

    @Test fun `the lead follows the function that failed`() {
        assertEquals("PelisPlusHD no pudo cargar los capítulos: sin temporadas", shown(PluginThrownException("Error: sin temporadas"), "episodes"))
        assertEquals("PelisPlusHD no pudo cargar el contenido: sin temporadas", shown(PluginThrownException("Error: sin temporadas"), "home"))
    }

    @Test fun `typed errors keep their Spanish sentences and an unknown code is cleaned`() {
        assertEquals("PelisPlusHD no está disponible ahora", shown(PluginErrorException(PluginErrors.UNAVAILABLE, "https://x.example boom")))
        assertEquals("No se encontró en PelisPlusHD", shown(PluginErrorException(PluginErrors.NOT_FOUND, "x")))
        assertEquals(
            "PelisPlusHD no pudo obtener el video: cuota agotada",
            shown(PluginErrorException("quota_hit", "[Api] cuota agotada: https://api.example/v1")),
        )
    }

    @Test fun `Kino's own messages are untouched`() {
        assertEquals("PelisPlusHD: El plugin se reinició, vuelve a intentar", shown(PluginScriptException("El plugin se reinició, vuelve a intentar")))
    }

    @Test fun `the raw text and stack stay in the log`() {
        val logs = mutableListOf<String>()
        val thrown = PluginThrownException(streamWish)
        val failure = runCatching {
            runBlocking { PluginCalls.callOrThrow({ _, _, _, _ -> throw thrown }, "p", "PelisPlusHD", "resolve", "null", 1, log = { logs += it }) }
        }.exceptionOrNull()
        val line = logs.single()
        assertTrue(line, "no pudo obtener el video" in line && streamWish in line)
        assertTrue(failure is GatewayException && failure.cause === thrown)
    }

    @Test fun `a Nuvio scraper's reported error is cleaned too`() {
        assertEquals(
            "PelisPlusHD no pudo obtener el video: sin servidores",
            shown(PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.SCRAPER_ERROR_PREFIX + "[X] Sin servidores: https://a.example")),
        )
    }
}
