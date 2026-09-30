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

    @Test fun `the reporting caller never replaces the plugin's own exception, whatever telemetry does`() = runBlocking {
        val original = PluginThrownException("Error: falló el sitio de verdad")
        // Every seam of the telemetry throws: the facts, the setting values, the sink, the dispatch.
        PluginTelemetry.current = PluginTelemetry(
            facts = { error("facts boom") }, sink = { error("sink boom") }, privateValues = { error("values boom") },
            dispatch = { error("dispatch boom") },
        )
        val caller = ReportingPluginCaller { _, _, _, _ -> throw original }
        val thrown = runCatching { caller.call("p", "search", "{not json", 1000) }.exceptionOrNull()
        assertTrue(thrown === original)
        // And a failure whose own text can't even be read (failureOf itself throws).
        val unreadable = object : PluginException("x") { override val message: String get() = error("message boom") }
        PluginTelemetry.current = telemetry()
        val caller2 = ReportingPluginCaller { _, _, _, _ -> throw unreadable }
        assertTrue(runCatching { caller2.call("p", "home", "null", 1000) }.exceptionOrNull() === unreadable)
    }

    @Test fun `the call's argument is removed from the reason, and only parsed for an admitted event`() {
        var parsedFor = 0
        val t = PluginTelemetry(facts = { facts }, sink = { events += it }, clock = { now }, dispatch = { parsedFor++; it() })
        repeat(3) { t.reportCall("p", "search", thrown("Error: no hay resultados para casa de papel hoy"), argJson = """{"q":"casa de papel"}""") }
        assertEquals(1, parsedFor)
        assertEquals(1, events.size)
        val reason = events[0].extras["reason"].orEmpty()
        assertFalse(reason, "casa" in reason || "papel" in reason)
    }

    @Test fun `IPv6 literals, LAN IPs and URLs never survive cleaning`() {
        val r = PluginTelemetry.cleanReason("Error: No se pudo conectar a 2800:484:1a2b::5 ni a fe80::1 ni a 10.0.0.7 esta vez")!!
        assertFalse(r, "2800" in r || "fe80" in r || "10.0.0.7" in r)
        assertTrue(r, "[ip]" in r)
        // A clock time is not an address.
        assertEquals("el sitio cerró a las 12:30:45 hoy", PluginTelemetry.cleanReason("el sitio cerró a las 12:30:45 hoy"))
    }

    @Test fun `a host typed into a text setting or approved by the person is never named`() {
        assertNull(PluginTelemetry.publicHost("jelly.casa-perez.net", facts, privateValues = listOf("https://jelly.casa-perez.net:8096")))
        assertNull(PluginTelemetry.publicHost("cdn.aprobado.com", facts.copy(privateHosts = facts.privateHosts + "cdn.aprobado.com")))
        val trace = PluginCallTrace().apply { refused("jelly.casa-perez.net", PluginCallTrace.Refusal.NOT_ASKED) }
        val e = PluginScriptException("x").also { it.trace = trace }
        val event = telemetry(values = listOf("jelly.casa-perez.net")).eventOf(PluginTelemetry.failureOf("p", "search", e)!!)
        assertNull(event.extras["host"])
    }

    @Test fun `a Nuvio repo is named only when it is a well-known public one`() {
        val t = telemetry()
        fun repoOf(repo: String) = t.eventOf(
            PluginFailure("nuvio-x", "resolve", PluginFailureKind.THROWN, facts = PluginFacts("1", 4, "nuvio", repo, "fakesrc")),
        ).extras
        assertEquals("yoruix/nuvio-providers", repoOf("yoruix/nuvio-providers@main")["nuvio_repo"])
        val mine = repoOf("pepito-perez/mis-scrapers")
        assertNull(mine["nuvio_repo"])
        assertEquals("fakesrc", mine["nuvio_scraper"])
        assertEquals("nuvio", mine["plugin_origin"])
    }

    @Test fun `NONE reports nothing`() {
        PluginTelemetry.NONE.reportCall("p", "search", thrown("Error: algo falló aquí"))
        assertTrue(events.isEmpty())
    }
}
