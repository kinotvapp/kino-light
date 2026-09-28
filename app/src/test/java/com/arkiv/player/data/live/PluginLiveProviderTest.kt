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
import kotlinx.coroutines.runBlocking
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

    private class Caller(val answer: (function: String, arg: String) -> String) : PluginCaller {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls += function to argJson
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
}
