package com.arkiv.player.data.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTelemetryTest {
    private val events = mutableListOf<PluginTelemetry.Event>()
    private var now = 0L

    private val facts = PluginFacts(
        version = "1.2.0", apiVersion = 4, origin = "catalog",
        privateHosts = setOf("mi-servidor.example.org"),
    )

    private fun telemetry(values: List<String> = emptyList(), cap: Int = PluginTelemetry.SESSION_CAP) = PluginTelemetry(
        facts = { facts },
        sink = { events += it },
        privateValues = { values },
        clock = { now },
        sessionCap = cap,
    )

    @After fun restore() {
        PluginTelemetry.current = PluginTelemetry.NONE
    }

    private fun thrown(message: String) = PluginThrownException(message)

    @Test fun `the fingerprint and message stay the same whatever the plugin said`() {
        val a = PluginTelemetry.failureOf("pelis", "resolve", thrown("Error: [StreamWish] no se pudo abrir la página uno\n    at f (plugin.js:12)"))!!
        val b = PluginTelemetry.failureOf("pelis", "resolve", thrown("TypeError: otra cosa totalmente distinta pasó aquí"))!!
        val t = telemetry()
        val ea = t.eventOf(a)
        val eb = t.eventOf(b)
        assertEquals(listOf("plugin", "pelis", "resolve", "thrown"), ea.fingerprint)
        assertEquals(ea.fingerprint, eb.fingerprint)
        assertEquals("plugin pelis resolve thrown", ea.message)
        assertEquals(ea.message, eb.message)
        // The varying text is an extra, cleaned.
        assertEquals("no se pudo abrir la página uno", ea.extras["reason"])
        assertEquals("1.2.0", ea.extras["plugin_version"])
        assertEquals("4", ea.extras["plugin_api"])
        assertEquals("catalog", ea.tags["plugin_origin"])
    }

    @Test fun `a key is reported at most once per window, and the session has a cap`() {
        val t = telemetry(cap = 3)
        repeat(5) { t.reportCall("p", "search", thrown("Error: algo falló en la búsqueda $it")) }
        assertEquals(1, events.size)
        t.reportCall("p", "resolve", thrown("Error: algo falló al resolver"))
        assertEquals(2, events.size)
        now += PluginTelemetry.WINDOW_MS - 1
        t.reportCall("p", "search", thrown("Error: otra vez falló"))
        assertEquals(2, events.size)
        now += 1
        t.reportCall("p", "search", thrown("Error: otra vez falló"))
        assertEquals(3, events.size)
        // Session cap reached: a brand-new key is not sent either.
        t.reportCall("q", "home", thrown("Error: falló el inicio"))
        assertEquals(3, events.size)
    }

    @Test fun `nothing private reaches the event`() {
        val password = "hunter2clave"
        val t = telemetry(values = listOf(password, "pepito.perez"))
        val message = "Error: fallo al pedir https://api.sitio.com/v1?token=abc123def con clave $password " +
            "para pepito.perez en mi-servidor.example.org y 192.168.1.20 casa de papel"
        val failure = PluginTelemetry.failureOf("p", "search", thrown(message))!!
            .copy(privateText = PluginTelemetry.argTexts("""{"q":"casa de papel","year":2017}"""))
        val event = t.eventOf(failure)
        val all = (event.extras.values + event.tags.values + event.message + event.fingerprint).joinToString(" ")
        listOf("abc123def", "token", password, "pepito", "mi-servidor", "api.sitio.com", "http", "192.168", "casa", "papel").forEach {
            assertFalse("leaked $it in: $all", all.contains(it, ignoreCase = true))
        }
        assertNotNull(event.extras["reason"])
    }

    @Test fun `a URL with a token alone leaves no reason at all`() {
        val t = telemetry()
        val event = t.eventOf(PluginTelemetry.failureOf("p", "resolve", thrown("https://cdn.x.com/v.m3u8?token=s3cr3t"))!!)
        assertNull(event.extras["reason"])
        assertFalse(event.extras.values.any { "s3cr3t" in it })
    }

    @Test fun `a sealed secret marker never survives cleaning`() {
        assertNull(PluginTelemetry.cleanReason("Error: rechazado __kinoSecret_apiKey_abcdef__ por el sitio"))
    }

    @Test fun `hosts are named only when public and not the person's own`() {
        assertEquals("cdn.sitio.com", PluginTelemetry.publicHost("cdn.sitio.com", facts))
        assertNull(PluginTelemetry.publicHost("mi-servidor.example.org", facts))
        assertNull(PluginTelemetry.publicHost("192.168.1.20", facts))
        assertNull(PluginTelemetry.publicHost("8.8.8.8", facts))
        assertNull(PluginTelemetry.publicHost("nas.local", facts))
        assertNull(PluginTelemetry.publicHost("localhost", facts))
        val trace = PluginCallTrace().apply { refused("mi-servidor.example.org", PluginCallTrace.Refusal.NOT_ASKED) }
        val e = PluginScriptException("x").also { it.trace = trace }
        val event = telemetry().eventOf(PluginTelemetry.failureOf("p", "search", e)!!)
        assertEquals("host", event.extras["kind"])
        assertNull(event.extras["host"])
    }

    @Test fun `benign outcomes are not reported`() {
        assertNull(PluginTelemetry.failureOf("p", "search", CancellationException("gone")))
        assertNull(PluginTelemetry.failureOf("p", "resolve", PluginErrorException(PluginErrors.NOT_FOUND, "no está")))
        assertNull(PluginTelemetry.failureOf("p", "resolve", PluginErrorException(PluginErrors.AUTH_REQUIRED, "")))
        assertNull(PluginTelemetry.failureOf("p", "resolve", PluginErrorException(PluginErrors.GEO_BLOCKED, "")))
        val rejected = PluginCallTrace().apply { refused("sitio.com", PluginCallTrace.Refusal.REJECTED_NOW) }
        assertNull(PluginTelemetry.failureOf("p", "resolve", PluginThrownException("Error: host no permitido").also { it.trace = rejected }))
        // An empty search is not a failure; a list whose every entry was invalid is.
        assertNull(PluginTelemetry.droppedList("p", "search", "[]", 0))
        assertNull(PluginTelemetry.droppedList("p", "search", """{"items":[]}""", 0))
        assertEquals("all_dropped", PluginTelemetry.droppedList("p", "search", """[{"id":1}]""", 0)!!.detail["output"])
        assertEquals("not_a_list", PluginTelemetry.droppedList("p", "home", "<html>", 0)!!.detail["output"])
        assertNull(PluginTelemetry.droppedList("p", "search", """[{"id":1}]""", 1))
        // A host the person decides on is not the plugin's bug.
        assertNull(PluginTelemetry.invalidOutput("p", "resolve", PluginContractException("El video apunta a x.com, que el plugin no declaró")))
        assertEquals("drm", PluginTelemetry.invalidOutput("p", "resolve", PluginContractException("El video tiene DRM y los plugins no lo soportan"))!!.detail["output"])
    }

    @Test fun `kinds by failure`() {
        assertEquals(PluginFailureKind.TIMEOUT, PluginTelemetry.failureOf("p", "search", PluginTimeoutException("search", 15_000))!!.kind)
        assertEquals(PluginFailureKind.LOAD, PluginTelemetry.failureOf("p", "search", PluginTimeoutException("La carga del plugin", 10_000).also { it.atLoad = true })!!.kind)
        assertEquals(PluginFailureKind.LOAD, PluginTelemetry.failureOf("p", "search", thrown("SyntaxError: x").also { it.atLoad = true })!!.kind)
        assertEquals(PluginFailureKind.UNAVAILABLE, PluginTelemetry.failureOf("p", "search", PluginErrorException(PluginErrors.UNAVAILABLE, "caído"))!!.kind)
        assertEquals(PluginFailureKind.RATE_LIMITED, PluginTelemetry.failureOf("p", "search", PluginErrorException(PluginErrors.RATE_LIMITED, ""))!!.kind)
        assertEquals(PluginFailureKind.NETWORK, PluginTelemetry.failureOf("p", "search", PluginErrorException("network", "error de red"))!!.kind)
        val none = PluginTelemetry.failureOf("n", "resolve", PluginErrorException(PluginErrors.NOT_FOUND, NuvioPluginConverter.NO_STREAMS))!!
        assertEquals(PluginFailureKind.NO_STREAMS, none.kind)
        val torrents = PluginTelemetry.failureOf("n", "resolve", PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.ONLY_TORRENTS))!!
        assertEquals(PluginFailureKind.NO_STREAMS, torrents.kind)
        val scraper = PluginTelemetry.failureOf("n", "resolve", PluginErrorException(PluginErrors.UNAVAILABLE, NuvioPluginConverter.SCRAPER_ERROR_PREFIX + "falló la página del sitio"))!!
        assertEquals(PluginFailureKind.THROWN, scraper.kind)
        // A plugin's own unknown code is never sent as is.
        val odd = telemetry().eventOf(PluginTelemetry.failureOf("p", "search", PluginErrorException("user_pepito", "algo raro pasó aquí"))!!)
        assertEquals("other", odd.extras["code"])
    }

    @Test fun `the reporting caller reports a failed call once and passes the error through`() = runBlocking {
        PluginTelemetry.current = telemetry()
        val caller = ReportingPluginCaller { _, _, _, _ -> throw PluginTimeoutException("search", 15_000) }
        repeat(3) { runCatching { caller.call("p", "search", """{"q":"hola mundo"}""", 15_000) }.also { assertTrue(it.exceptionOrNull() is PluginTimeoutException) } }
        assertEquals(1, events.size)
        assertEquals("timeout", events[0].extras["kind"])
        val ok = ReportingPluginCaller { _, _, _, _ -> "[]" }
        assertEquals("[]", ok.call("p", "home", "null", 1000))
        assertEquals(1, events.size)
    }

    @Test fun `NONE reports nothing`() {
        PluginTelemetry.NONE.reportCall("p", "search", thrown("Error: algo falló aquí"))
        assertTrue(events.isEmpty())
    }
}
