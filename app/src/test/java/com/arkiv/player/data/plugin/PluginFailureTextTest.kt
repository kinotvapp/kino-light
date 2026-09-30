package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed plugin call says WHY, from what Kino saw during it (its [PluginCallTrace]) and what a
 * converted Nuvio scraper's adapter reported, instead of "<fuente> no está disponible ahora".
 */
class PluginFailureTextTest {
    private fun trace(block: PluginCallTrace.() -> Unit) = PluginCallTrace().apply(block)

    private fun <E : PluginException> E.with(t: PluginCallTrace) = also { it.trace = t }

    /** What the screen shows for [e], thrown by a `resolve` of the plugin called "Fuente". */
    private fun shown(e: Exception, logs: MutableList<String> = mutableListOf()): String {
        val caller = PluginCaller { _, _, _, _ -> throw e }
        val failure = runCatching { runBlocking { PluginCalls.callOrThrow(caller, "p", "Fuente", "resolve", "null", 20_000, log = { logs += it }) } }
        return failure.exceptionOrNull()!!.message!!
    }

    @Test fun `a host the person rejected is named`() {
        val e = PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.NO_STREAMS)
            .with(trace { refused("gamerxyt.com", PluginCallTrace.Refusal.REJECTED_NOW) })
        assertEquals("Fuente necesita gamerxyt.com, que rechazaste", shown(e))
        val before = PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS)
            .with(trace { refused("greenmotors.cc", PluginCallTrace.Refusal.REJECTED_BEFORE) })
        assertEquals("Fuente necesita greenmotors.cc, que rechazaste", shown(before))
    }

    @Test fun `a site that did not answer is named, with how it failed`() {
        fun failed(kind: PluginCallTrace.Failure) =
            shown(PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS).with(trace { failed("4khdhub.click", kind) }))
        assertEquals("Fuente: no se encontró el sitio 4khdhub.click", failed(PluginCallTrace.Failure.DNS))
        assertEquals("Fuente: 4khdhub.click no respondió a tiempo", failed(PluginCallTrace.Failure.TIMEOUT))
        assertEquals("Fuente: no se pudo conectar con 4khdhub.click", failed(PluginCallTrace.Failure.NETWORK))
    }

    @Test fun `a server error from the site is named`() {
        val e = PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS)
            .with(trace { answered("api.hlowb.com", 503); answered("api.themoviedb.org", 200) })
        assertEquals("Fuente: api.hlowb.com respondió con error 503", shown(e))
    }

    // A 404 is a normal "not here" while a scraper probes mirrors: never blamed.
    @Test fun `a 404 is not a failure cause`() {
        val e = PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS).with(trace { answered("mirror.example", 404) })
        assertEquals("Fuente no encontró este título", shown(e))
    }

    @Test fun `a refused host outranks a site that failed`() {
        val e = PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.NO_STREAMS).with(
            trace {
                failed("a.example", PluginCallTrace.Failure.DNS)
                refused("b.example", PluginCallTrace.Refusal.REJECTED_NOW)
            },
        )
        assertEquals("Fuente necesita b.example, que rechazaste", shown(e))
    }

    @Test fun `a host a search could not ask about is named as not approved`() {
        val e = PluginErrorException(PluginErrors.UNAVAILABLE, "x").with(trace { refused("cdn.example", PluginCallTrace.Refusal.NOT_ASKED) })
        assertEquals("Fuente necesita cdn.example, que no está aprobado", shown(e))
    }

    @Test fun `the Nuvio adapter's own reasons`() {
        assertEquals("Fuente no encontró este título", shown(PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS)))
        assertEquals("Fuente solo tiene torrents de este título", shown(PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.ONLY_TORRENTS)))
        assertEquals(
            "Fuente no pudo obtener el video",
            shown(PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.SCRAPER_ERROR_PREFIX + "unexpected token: ''")),
        )
    }

    @Test fun `a timeout names the site it was still waiting for`() {
        val waiting = PluginTimeoutException("resolve", 20_000).with(trace { started("hubcloud.ist") })
        assertEquals("Fuente no respondió a tiempo: esperaba a hubcloud.ist", shown(waiting))
        val done = PluginTimeoutException("resolve", 20_000).with(trace { started("a.example"); finished("a.example") })
        assertEquals("Fuente no respondió a tiempo", shown(done))
        assertEquals("Fuente no respondió a tiempo", shown(PluginTimeoutException("resolve", 20_000)))
    }

    @Test fun `typed errors a plugin chose keep their meaning`() {
        val caller = PluginCaller { _, _, _, _ -> throw PluginErrorException(PluginErrors.GEO_BLOCKED, "x").with(trace { failed("a.example", PluginCallTrace.Failure.DNS) }) }
        assertTrue(runCatching { runBlocking { PluginCalls.callOrThrow(caller, "p", "Fuente", "resolve", "null", 1) } }.exceptionOrNull() is GatewayBlockedException)
        val auth = PluginCaller { _, _, _, _ -> throw PluginErrorException(PluginErrors.AUTH_REQUIRED, "x") }
        assertTrue(runCatching { runBlocking { PluginCalls.callOrThrow(auth, "p", "Fuente", "resolve", "null", 1) } }.exceptionOrNull() is PluginSetupRequiredException)
        // Without anything better, the old sentences stay.
        assertEquals("Fuente no está disponible ahora", shown(PluginErrorException(PluginErrors.UNAVAILABLE, "cualquier cosa")))
        assertEquals("Fuente: boom", shown(PluginScriptException("boom")))
    }

    @Test fun `every attempt is logged with function, time and cause`() {
        val logs = mutableListOf<String>()
        shown(PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS).with(trace { failed("x.example", PluginCallTrace.Failure.DNS) }), logs)
        val line = logs.single()
        assertTrue(line, line.startsWith("[p] resolve failed after ") && " ms: " in line && "no se encontró el sitio x.example" in line)
        assertTrue(line, "x.example" in line && "DNS" in line)
        val ok = mutableListOf<String>()
        runBlocking { PluginCalls.callOrThrow({ _, _, _, _ -> "{}" }, "p", "Fuente", "resolve", "null", 1, log = { ok += it }) }
        assertTrue(ok.toString(), ok.single().startsWith("[p] resolve ok after "))
    }

    @Test fun `the failure is still a GatewayException carrying the plugin's`() {
        val e = PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS)
        val caller = PluginCaller { _, _, _, _ -> throw e }
        val thrown = runCatching { runBlocking { PluginCalls.callOrThrow(caller, "p", "Fuente", "resolve", "null", 1) } }.exceptionOrNull()
        assertTrue(thrown is GatewayException)
        assertTrue(thrown!!.cause === e)
    }
}
