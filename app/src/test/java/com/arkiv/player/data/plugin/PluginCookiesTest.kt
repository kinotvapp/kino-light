package com.arkiv.player.data.plugin

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginCookiesTest {
    @get:Rule val tmp = TemporaryFolder()
    // OkHttp's Cookie.parse stamps Max-Age against the real clock: start there.
    private var now = System.currentTimeMillis()
    private val hosts = EffectiveHosts(listOf("example.com", "*.example.com"), listOf(UserHost("http", "192.168.1.10", 8096)))
    private fun file() = tmp.root.resolve("cookies.json")
    private fun jar() = PluginCookies(file(), hosts, clock = { now })

    private fun set(jar: PluginCookies, url: String, vararg headers: String) {
        val u = url.toHttpUrl()
        jar.saveFromResponse(u, headers.mapNotNull { Cookie.parse(u, it) })
    }

    private fun names(jar: PluginCookies, url: String) = jar.loadForRequest(url.toHttpUrl()).map { "${it.name}=${it.value}" }

    @Test fun `domain, path and secure are honored`() {
        val j = jar()
        set(j, "https://www.example.com/app/login", "a=1; Domain=example.com; Path=/", "b=2; Path=/app", "c=3; Secure", "d=4")
        assertEquals(listOf("a=1", "b=2", "c=3", "d=4"), names(j, "https://www.example.com/app/x"))
        assertEquals(listOf("a=1"), names(j, "https://api.example.com/"))
        // Over http and outside /app: only a. c is Secure; b and d live under /app (d's default path).
        assertEquals(listOf("a=1"), names(j, "http://www.example.com/"))
        assertEquals(listOf("a=1", "b=2", "d=4"), names(j, "http://www.example.com/app/y"))
    }

    @Test fun `expires and max-age are honored, a past date deletes`() {
        val j = jar()
        set(j, "https://example.com/", "s=1; Max-Age=60", "t=2")
        assertEquals(listOf("s=1", "t=2"), names(j, "https://example.com/"))
        set(j, "https://example.com/", "t=gone; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        assertEquals(listOf("s=1"), names(j, "https://example.com/"))
    }

    @Test fun `a response from a host the plugin may not reach stores nothing`() {
        val j = jar()
        set(j, "https://evil.example/", "x=1")
        set(j, "http://192.168.1.10:9000/", "y=2")
        assertEquals(0, j.size())
        set(j, "http://192.168.1.10:8096/", "z=3")
        assertEquals(listOf("z=3"), names(j, "http://192.168.1.10:8096/Items"))
        assertEquals(emptyList<String>(), names(j, "https://evil.example/"))
    }

    @Test fun `at most 50 per domain and 64 KB in total, oldest first out`() {
        val j = jar()
        repeat(60) { i -> set(j, "https://example.com/", "c$i=v") }
        assertEquals(50, j.size())
        assertFalse(names(j, "https://example.com/").contains("c0=v"))
        assertTrue(names(j, "https://example.com/").contains("c59=v"))
        val big = "v".repeat(4000)
        repeat(20) { i -> set(j, "https://s$i.example.com/", "big=$big") }
        assertTrue(j.loadForRequest("https://s19.example.com/".toHttpUrl()).isNotEmpty())
        val total = (0 until 20).sumOf { i -> j.loadForRequest("https://s$i.example.com/".toHttpUrl()).sumOf { it.name.length + it.value.length + it.domain.length + it.path.length } }
        assertTrue("total $total", total <= PluginCookies.MAX_TOTAL_BYTES)
        assertNull(j.get("https://s0.example.com/".toHttpUrl(), "big"))
    }

    @Test fun `the jar survives a new instance, clear empties it and the file`() {
        val j = jar()
        set(j, "https://example.com/", "sid=abc; HttpOnly", "sec=1; Secure; Path=/p")
        j.saveIfChanged()
        assertTrue(file().exists())
        val again = jar()
        assertEquals("abc", again.get("https://example.com/".toHttpUrl(), "sid"))
        assertEquals("1", again.get("https://example.com/p/x".toHttpUrl(), "sec"))
        again.clear()
        assertFalse(file().exists())
        assertNull(jar().get("https://example.com/".toHttpUrl(), "sid"))
    }

    @Test fun `expired cookies are not persisted and not loaded back`() {
        val j = jar()
        set(j, "https://example.com/", "s=1; Max-Age=10")
        j.saveIfChanged()
        now += 11_000
        assertNull(jar().get("https://example.com/".toHttpUrl(), "s"))
    }

    @Test fun `a retired jar keeps nothing, even a response that lands after`() {
        val j = jar()
        set(j, "https://example.com/", "old=1")
        j.saveIfChanged()
        j.retire()
        set(j, "https://example.com/", "late=2")
        j.saveIfChanged()
        assertFalse(file().exists())
        assertEquals(0, j.size())
    }

    /**
     * Two real [PluginCookies] on the SAME file, as AppGraph.openPluginRuntime produces when a new
     * runtime replaces one whose close is deferred behind an in-flight call (`PluginRuntime.close`
     * KDoc): the old jar's `saveIfChanged` can still fire after the new one is already live. Once
     * AppGraph calls `stopWriting()` on the superseded jar, its late write must not land over the
     * new jar's -- but, unlike `retire`, the FILE stays: a plain reopen must not lose the session.
     */
    @Test fun `stopWriting keeps an old jar's late write from clobbering a newer jar's file, but leaves it in place`() {
        val old = jar()
        set(old, "https://example.com/", "sid=old")
        old.saveIfChanged()
        assertTrue(file().exists())

        // A fresh runtime opens: a NEW PluginCookies on the same file (AppGraph.openPluginRuntime),
        // loading the session the old jar already persisted.
        val fresh = jar()
        assertEquals("old", fresh.get("https://example.com/".toHttpUrl(), "sid"))
        // AppGraph stops the superseded jar -- not retires it, the session itself hasn't changed.
        old.stopWriting()

        // The fresh jar does its own work (e.g. a login response) and persists it.
        set(fresh, "https://example.com/", "sid=new")
        fresh.saveIfChanged()

        // The OLD jar's in-flight call -- started before it was superseded -- finally lands its own,
        // now-stale write. It must not resurrect "old" over what the fresh jar already wrote.
        set(old, "https://example.com/", "sid=stale")
        old.saveIfChanged()

        // The file still holds the live jar's state; a plain reopen still finds a session (the file
        // was never deleted -- this isn't a settings-change forget).
        assertTrue(file().exists())
        assertEquals("new", jar().get("https://example.com/".toHttpUrl(), "sid"))
    }

    @Test fun `a corrupt or oversized file is ignored`() {
        file().writeText("not json")
        assertEquals(0, jar().size())
        file().writeText("[" + "{\"name\":\"a\"},".repeat(30_000) + "{}]")
        assertEquals(0, jar().size())
    }
}
