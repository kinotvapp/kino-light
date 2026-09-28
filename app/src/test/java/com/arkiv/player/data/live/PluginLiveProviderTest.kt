package com.arkiv.player.data.live

import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginCaller
import com.arkiv.player.data.plugin.PluginErrorException
import com.arkiv.player.data.plugin.PluginErrors
import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.data.plugin.PluginSetupRequiredException
import com.arkiv.player.data.plugin.PluginRuntimePool
import com.arkiv.player.data.plugin.PluginTimeoutException
import com.arkiv.player.data.plugin.ScriptRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLiveProviderTest {
    private val plugin = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 3, "plugin.js", "", "", "", listOf("cdn.example.com"), setOf("home", "resolve", "channels"), "#112233", null),
        InstalledRecord("o/demo", "1.0.0", "x", listOf("cdn.example.com"), 0L),
        null,
    )
    private val provider = LiveChannelKeys.pluginProvider("demo")

    private class Caller(
        /** When set, every call waits for it: lets a test hold a call in flight. */
        val gate: CompletableDeferred<Unit>? = null,
        val answer: (function: String, arg: String) -> String,
    ) : PluginCaller {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls += function to argJson
            gate?.await()
            return answer(function, argJson)
        }
    }

    private var now = 1_000_000L
    private fun live(caller: PluginCaller, cached: suspend (String) -> LiveChannelCacheEntity? = { null }) =
        PluginLiveProvider(plugin, caller, cached, clock = { now }, log = {})

    @Test fun `identity comes from the plugin`() {
        val p = live(Caller { _, _ -> "[]" })
        assertEquals("plugin:demo", p.id)
        assertEquals("Demo", p.name)
        assertEquals(0xFF112233, p.color)
    }

    @Test fun `categories are cached for an hour, then asked again`() = runBlocking {
        val caller = Caller { _, _ -> """[{"id":"news","title":"Noticias"},{"id":"kids","title":"Infantil"}]""" }
        val p = live(caller)
        assertEquals(null, p.initialCategory())
        assertEquals(listOf(ProviderCategory("news", "Noticias"), ProviderCategory("kids", "Infantil")), p.categories(false))
        assertEquals("news", p.initialCategory())
        p.categories(false)
        assertEquals(1, caller.calls.size)
        now += PluginLiveProvider.LIST_TTL_MS
        p.categories(false)
        assertEquals(2, caller.calls.size)
    }

    @Test fun `channels page through cursors, dedupe across pages and carry a wrapped live ref`() = runBlocking {
        val caller = Caller { _, arg ->
            when (JSONObject(arg).opt("cursor")) {
                JSONObject.NULL -> """{"items":[{"id":"c1","title":"Uno","ref":"r1","number":1}],"next":"p2"}"""
                "p2" -> """{"items":[{"id":"c1","title":"Uno otra vez","ref":"r1"},{"id":"c2","title":"Dos","ref":"r2"}]}"""
                else -> error("unexpected $arg")
            }
        }
        val list = live(caller).channels("news")
        assertEquals(listOf("plugin:demo:c1", "plugin:demo:c2"), list.map { it.liveCode })
        assertEquals(listOf("news", "news"), caller.calls.map { JSONObject(it.second).getString("categoryId") })
        val ref = PluginRef.decode(list[0].ref!!)!!
        assertEquals(PluginRef("demo", "c1", PluginRef.LIVE, "r1"), ref)
    }

    @Test fun `paging stops on a repeated cursor and never passes the page cap`() = runBlocking {
        val loop = Caller { _, arg ->
            val n = (JSONObject(arg).opt("cursor") as? String)?.toInt() ?: 0
            """{"items":[{"id":"c$n","title":"C$n","ref":"r$n"}],"next":"${if (n == 2) 1 else n + 1}"}"""
        }
        assertEquals(3, live(loop).channels("a").size)
        val endless = Caller { _, arg ->
            val n = (JSONObject(arg).opt("cursor") as? String)?.toInt() ?: 0
            """{"items":[{"id":"c$n","title":"C$n","ref":"r$n"}],"next":"${n + 1}"}"""
        }
        live(endless).channels("a")
        assertEquals(PluginLiveContract.MAX_PAGES_PER_CATEGORY, endless.calls.size)
    }

    @Test fun `the guide is asked in chunks of 50, converted to seconds, keyed by live code and cached`() = runBlocking {
        val caller = Caller { function, arg ->
            check(function == "guide")
            val o = JSONObject(arg)
            val ids = o.getJSONArray("channelIds")
            (0 until ids.length()).joinToString(",", "[", "]") {
                """{"channelId":"${ids.getString(it)}","title":"Noticiero","start":${o.getLong("from") + 60_000},"end":${o.getLong("from") + 120_000}}"""
            }
        }
        val p = live(caller)
        val channels = (1..60).map { LiveChannel("c$it", "C$it", it, null, provider = provider) } +
            LiveChannel("x1", "Xuper", 1, null)
        val (guide, missing) = p.guide(channels)
        assertEquals(2, caller.calls.size)
        assertEquals(50, JSONObject(caller.calls[0].second).getJSONArray("channelIds").length())
        assertEquals(PluginLiveContract.MAX_GUIDE_WINDOW_MS, JSONObject(caller.calls[0].second).let { it.getLong("to") - it.getLong("from") })
        assertEquals(60, guide.size)
        assertTrue("x1" !in guide)
        val program = guide.getValue("plugin:demo:c1").single()
        assertEquals((now - PluginLiveProvider.GUIDE_BEHIND_MS + 60_000) / 1000, program.start)
        assertEquals(emptyList<String>(), missing)
        p.guide(channels)
        assertEquals(2, caller.calls.size)
    }

    @Test fun `a failing guide is not asked again until its TTL, and channels still list`() = runBlocking {
        val caller = Caller { function, _ ->
            if (function == "guide") throw IllegalStateException("el plugin no exporta guide")
            """{"items":[{"id":"c1","title":"Uno","ref":"r1"}]}"""
        }
        val p = live(caller)
        val c = LiveChannel("c1", "Uno", 1, null, provider = provider)
        assertEquals(emptyMap<String, Any>(), p.guide(listOf(c)).first)
        p.guide(listOf(c))
        assertEquals(1, caller.calls.count { it.first == "guide" })
        assertEquals(1, p.channels("news").size)
        now += PluginLiveProvider.GUIDE_TTL_MS
        p.guide(listOf(c))
        assertEquals(2, caller.calls.count { it.first == "guide" })
    }

    @Test fun `open uses the channel's ref, else the channel cache, else a rescan`() = runBlocking {
        val wrapped = PluginRef("demo", "c1", PluginRef.LIVE, "r1").encode()
        val none = Caller { _, _ -> error("no call expected") }
        val direct = live(none).open(LiveChannel("c1", "Uno", 1, "https://cdn.example.com/1.png", provider = provider, ref = wrapped)) as LiveOpening.Plugin
        assertEquals("plugin:demo:c1::live", direct.channel.episodeId)
        assertEquals(wrapped, direct.channel.ref)
        assertEquals("Uno", direct.channel.title)

        val fromCache = live(none) { code -> LiveChannelCacheEntity(code, "news", "Canal Uno", 1, null, 0L, provider, wrapped) }
            .open(LiveChannel("c1", "c1", 0, null, provider = provider)) as LiveOpening.Plugin
        assertEquals("Canal Uno", fromCache.channel.title)

        val rescan = Caller { function, _ ->
            if (function == "liveCategories") """[{"id":"news","title":"Noticias"}]""" else """{"items":[{"id":"c1","title":"Uno","ref":"r1"}]}"""
        }
        val found = live(rescan).open(LiveChannel("c1", "c1", 0, null, provider = provider)) as LiveOpening.Plugin
        assertEquals(wrapped, found.channel.ref)

        val gone = Caller { function, _ -> if (function == "liveCategories") """[{"id":"news","title":"N"}]""" else """{"items":[]}""" }
        val e = assertThrows(GatewayException::class.java) { runBlocking { live(gone).open(LiveChannel("zz", "zz", 0, null, provider = provider)) } }
        assertEquals("No se encontró el canal en Demo", e.message)
    }

    @Test fun `auth_required asks the person to configure the plugin`() {
        val p = live(Caller { _, _ -> throw PluginErrorException(PluginErrors.AUTH_REQUIRED, "") })
        assertThrows(PluginSetupRequiredException::class.java) { runBlocking { p.categories(false) } }
    }

    @Test fun `an inline-stream channel opens with no plugin call, checked against the live hosts`() = runBlocking {
        val caller = Caller { _, _ ->
            """{"items":[{"id":"a","title":"Directo","stream":{"url":"https://cdn.example.com/a.m3u8","headers":{"Referer":"https://cdn.example.com/"}}}]}"""
        }
        val p = live(caller)
        val a = p.channels("news").single()
        val opening = p.open(a) as LiveOpening.Plugin
        assertEquals("https://cdn.example.com/a.m3u8", opening.channel.direct!!.url)
        assertEquals(mapOf("Referer" to "https://cdn.example.com/"), opening.channel.direct!!.headers)
        assertEquals(0L, opening.channel.direct!!.durationMs)
        assertEquals(1, caller.calls.size) // only liveChannels, never resolve
    }

    @Test fun `hasGuide follows the recorded exports`() {
        val withGuide = plugin.copy(record = plugin.record.copy(exports = listOf("guide", "home", "liveCategories", "liveChannels", "resolve")))
        assertEquals(true, PluginLiveProvider(withGuide, Caller { _, _ -> "[]" }, clock = { now }, log = {}).hasGuide())
        assertEquals(false, live(Caller { _, _ -> "[]" }).hasGuide())
    }

    @Test fun `an inline stream on an undeclared host plays only when liveStreamHosts any was approved`() = runBlocking {
        val page = """{"items":[{"id":"a","title":"Directo","stream":{"url":"https://tv.elsewhere.org/a.m3u8"}},{"id":"b","title":"Ref","ref":"rb"}]}"""
        val strict = live(Caller { _, _ -> page }).channels("news")
        assertEquals(listOf("b"), strict.map { it.code })
        val any = plugin.copy(record = plugin.record.copy(liveStreamHostsAny = true))
        val relaxed = PluginLiveProvider(any, Caller { _, _ -> page }, clock = { now }, log = {})
        val a = relaxed.channels("news").first { it.code == "a" }
        assertEquals("https://tv.elsewhere.org/a.m3u8", (relaxed.open(a) as LiveOpening.Plugin).channel.direct!!.url)
    }

    @Test fun `concurrent cold listings share one plugin call and one result`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val caller = Caller(gate) { function, _ ->
            if (function == "liveCategories") """[{"id":"news","title":"Noticias"}]""" else """{"items":[{"id":"c1","title":"Uno","ref":"r1"}]}"""
        }
        val p = live(caller)
        val lists = listOf(async { p.channels("news") }, async { p.channels("news") })
        val cats = listOf(async { p.categories(false) }, async { p.categories(false) })
        repeat(3) { yield() }
        assertEquals(listOf("liveChannels", "liveCategories"), caller.calls.map { it.first })
        gate.complete(Unit)
        val (a, b) = lists.awaitAll()
        assertEquals(a, b)
        assertEquals(1, a.size)
        val (x, y) = cats.awaitAll()
        assertEquals(listOf(ProviderCategory("news", "Noticias")), x)
        assertEquals(x, y)
        assertEquals(2, caller.calls.size)
    }

    @Test fun `a failing shared call fails every waiter, and the next call asks again`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var fail = true
        val caller = Caller(gate) { _, _ ->
            if (fail) throw IllegalStateException("caído")
            """{"items":[{"id":"c1","title":"Uno","ref":"r1"}]}"""
        }
        val p = live(caller)
        val both = listOf(async { runCatching { p.channels("news") } }, async { runCatching { p.channels("news") } })
        repeat(3) { yield() }
        gate.complete(Unit)
        val results = both.awaitAll()
        assertEquals(1, caller.calls.size)
        results.forEach { assertEquals("Demo: caído", it.exceptionOrNull()?.message) }
        fail = false
        assertEquals(1, p.channels("news").size)
        assertEquals(2, caller.calls.size)
    }

    @Test fun `concurrent guide passes ask each channel once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val caller = Caller(gate) { _, arg ->
            val o = JSONObject(arg)
            val ids = o.getJSONArray("channelIds")
            (0 until ids.length()).joinToString(",", "[", "]") {
                """{"channelId":"${ids.getString(it)}","title":"T","start":${o.getLong("from") + 60_000},"end":${o.getLong("from") + 120_000}}"""
            }
        }
        val p = live(caller)
        val c = listOf(LiveChannel("c1", "Uno", 1, null, provider = provider))
        val both = listOf(async { p.guide(c) }, async { p.guide(c) })
        repeat(3) { yield() }
        gate.complete(Unit)
        val (a, b) = both.awaitAll()
        assertEquals(1, caller.calls.size)
        assertEquals(a.first, b.first)
        assertEquals(1, a.first.getValue("plugin:demo:c1").size)
    }

    private class TimingOut : ScriptRuntime {
        override val exports = setOf("guide", "liveCategories", "liveChannels", "resolve")
        override var isDiscarded = false
        override suspend fun call(function: String, argJson: String, timeoutMs: Long): String {
            isDiscarded = true
            throw PluginTimeoutException(function, timeoutMs)
        }
        override fun close() { isDiscarded = true }
    }

    @Test fun `guide timeouts never mark the plugin unresponsive`() = runTest {
        val flagged = mutableListOf<String>()
        val pool = PluginRuntimePool(open = { TimingOut() }, onUnresponsive = { flagged += it }, scope = backgroundScope)
        val p = PluginLiveProvider(plugin, pool, clock = { now }, log = {})
        val c = listOf(LiveChannel("c1", "Uno", 1, null, provider = provider))
        repeat(5) {
            assertEquals(emptyMap<String, Any>(), p.guide(c).first)
            now += PluginLiveProvider.GUIDE_TTL_MS
        }
        assertEquals(emptyList<String>(), flagged)
    }

    @Test fun `one guide pass asks at most four chunks, and reports the rest for the next pass`() = runBlocking {
        val caller = Caller { _, arg ->
            val o = JSONObject(arg)
            val ids = o.getJSONArray("channelIds")
            (0 until ids.length()).joinToString(",", "[", "]") {
                """{"channelId":"${ids.getString(it)}","title":"T","start":${o.getLong("from") + 60_000},"end":${o.getLong("from") + 120_000}}"""
            }
        }
        val p = live(caller)
        val channels = (1..250).map { LiveChannel("c$it", "C$it", it, null, provider = provider) }
        val (guide, later) = p.guide(channels)
        assertEquals(PluginLiveProvider.MAX_GUIDE_CHUNKS_PER_PASS, caller.calls.size)
        assertEquals(200, guide.size)
        assertEquals((201..250).map { "plugin:demo:c$it" }, later)
        val (next, none) = p.guide(channels)
        assertEquals(5, caller.calls.size)
        assertEquals(250, next.size)
        assertEquals(emptyList<String>(), none)
    }

    @Test fun `an inline stream lives only as long as its channel list`() = runBlocking {
        var withStream = true
        val caller = Caller { function, _ ->
            when {
                function == "liveCategories" -> """[{"id":"news","title":"N"}]"""
                withStream -> """{"items":[{"id":"a","title":"A","ref":"ra","stream":{"url":"https://cdn.example.com/a.m3u8"}}]}"""
                else -> """{"items":[{"id":"a","title":"A","ref":"ra"}]}"""
            }
        }
        val p = live(caller)
        val a = p.channels("news").single()
        assertEquals("https://cdn.example.com/a.m3u8", (p.open(a) as LiveOpening.Plugin).channel.direct!!.url)
        withStream = false
        now += PluginLiveProvider.LIST_TTL_MS
        // The list expired: its stream is gone with it, and a new listing no longer has one.
        val opened = p.open(a) as LiveOpening.Plugin
        assertEquals(null, opened.channel.direct)
        assertEquals(PluginRef("demo", "a", PluginRef.LIVE, "ra").encode(), opened.channel.ref)
    }

    @Test fun `a channel with both a ref and a stream plays the stream, and a stream-only one carries no ref`() = runBlocking {
        val caller = Caller { _, _ ->
            """{"items":[{"id":"a","title":"A","ref":"ra","stream":{"url":"https://cdn.example.com/a.m3u8"}},{"id":"s","title":"S","stream":{"url":"https://cdn.example.com/s.m3u8"}}]}"""
        }
        val p = live(caller)
        val (a, s) = p.channels("news")
        assertEquals(null, s.ref)
        assertEquals("https://cdn.example.com/a.m3u8", (p.open(a) as LiveOpening.Plugin).channel.direct!!.url)
        // Known only by its code (a recent, the companion): found again by a rescan, played directly.
        val fresh = live(Caller { function, _ -> if (function == "liveCategories") """[{"id":"news","title":"N"}]""" else caller.answer(function, "") })
        val opened = fresh.open(LiveChannel("s", "s", 0, null, provider = provider)) as LiveOpening.Plugin
        assertEquals("https://cdn.example.com/s.m3u8", opened.channel.direct!!.url)
        assertEquals("S", opened.channel.title)
    }

    @Test fun `closing cancels the work in flight and refuses new work, without the plugin being asked`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val caller = Caller(gate) { function, _ ->
            if (function == "liveCategories") """[{"id":"news","title":"Noticias"}]""" else """{"items":[{"id":"c1","title":"Uno","ref":"r1"}]}"""
        }
        val p = live(caller)
        val list = async { runCatching { p.channels("news") } }
        val cats = async { runCatching { p.categories(false) } }
        repeat(3) { yield() }
        assertEquals(2, caller.calls.size)
        p.close()
        // Bounded: without close() the held calls would wait for the gate forever.
        withTimeout(5_000) {
            assertTrue(list.await().exceptionOrNull() is CancellationException)
            assertTrue(cats.await().exceptionOrNull() is CancellationException)
        }
        // The asker itself was not cancelled: only the closed provider's work.
        assertTrue(isActive)
        assertTrue(runCatching { p.channels("news") }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { p.open(LiveChannel("c1", "Uno", 1, null, provider = provider)) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { p.guide(listOf(LiveChannel("c1", "Uno", 1, null, provider = provider))) }.exceptionOrNull() is CancellationException)
        assertEquals(2, caller.calls.size)
    }

    @Test fun `known channels are the listed categories' channels, and unlisted categories are reported, with no extra call`() = runBlocking {
        val caller = Caller { fn, arg ->
            when (fn) {
                "liveCategories" -> """[{"id":"news","title":"Noticias"},{"id":"kids","title":"Infantil"}]"""
                else -> if (JSONObject(arg).getString("categoryId") == "news") """{"items":[{"id":"c1","title":"Uno","ref":"r1"}]}""" else """{"items":[]}"""
            }
        }
        val p = live(caller)
        assertEquals(emptyList<LiveChannel>(), p.knownChannels())
        assertEquals(true, p.hasUnloadedCategories())
        p.categories(false)
        assertEquals(true, p.hasUnloadedCategories())
        p.channels("news")
        assertEquals(listOf("plugin:demo:c1"), p.knownChannels().map { it.liveCode })
        assertEquals(true, p.hasUnloadedCategories())
        p.channels("kids")
        assertEquals(false, p.hasUnloadedCategories())
        val asked = caller.calls.size
        p.knownChannels()
        p.hasUnloadedCategories()
        assertEquals(asked, caller.calls.size)
    }
}
