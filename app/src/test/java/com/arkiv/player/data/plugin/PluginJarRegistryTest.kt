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

/**
 * Round 1, finding 1 (the highest-stakes leak this plan has found): a runtime's own `close()` must
 * never remove a plugin's jar from the shared map — only [PluginJarRegistry.put] (superseded by a
 * fresh runtime) or [PluginJarRegistry.forget] (a settings change) may. Real temp files, real
 * [PluginCookies], no fakes — the same standard `PluginCookiesTest`'s two-instance test already set.
 */
class PluginJarRegistryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hosts = EffectiveHosts(listOf("example.com"))
    private fun file() = tmp.root.resolve("cookies.json")
    private fun jar() = PluginCookies(file(), hosts)
    private val url = "https://example.com/".toHttpUrl()
    private fun set(jar: PluginCookies, cookie: String) {
        jar.saveFromResponse(url, listOfNotNull(Cookie.parse(url, cookie)))
    }

    @Test fun `forget retires the current jar, so a late write from it never resurrects the file`() {
        val registry = PluginJarRegistry()
        val j1 = jar()
        registry.put("demo", j1)
        set(j1, "sid=A")
        j1.saveIfChanged()
        assertTrue(file().exists())

        registry.forget("demo")
        assertFalse(file().exists())
        assertNull(registry.current("demo"))

        // The orphaned evaluation that owned j1 is still finishing; its late write must not land.
        set(j1, "sid=A-late")
        j1.saveIfChanged()
        assertFalse("a forgotten jar's late write must not resurrect the file", file().exists())
    }

    @Test fun `put stops whatever jar it replaces, so its late write never clobbers the new one's`() {
        val registry = PluginJarRegistry()
        val j1 = jar()
        registry.put("demo", j1)
        set(j1, "sid=A")
        j1.saveIfChanged()

        // A fresh runtime opens (idle reopen, or j1's evaluation lost the race to finish first).
        val j2 = jar()
        registry.put("demo", j2)
        set(j2, "sid=B")
        j2.saveIfChanged()

        // j1's own late write, from before it was superseded, must not overwrite j2's.
        set(j1, "sid=A-late")
        j1.saveIfChanged()
        assertEquals("B", jar().get(url, "sid"))
    }

    /**
     * The actual bug (round 1 finding 1): the version this class replaces had a runtime's `close()`
     * ALSO remove its jar from the shared map (`pluginJars.remove(id, cookies)`, no `retire()`),
     * racing `forget()`. This reproduces that exact removal directly against the map [put]/[forget]
     * already use, proving why it can't be reintroduced: an entry removed this way is gone from the
     * map (so `forget()` finds nothing to retire) but the jar itself is never stopped.
     */
    @Test fun `removing a jar from the map without retiring it -- the old close() bug -- leaves it free to leak later`() {
        val jars = java.util.concurrent.ConcurrentHashMap<String, PluginCookies>()
        val j1 = jar()
        jars["demo"] = j1
        set(j1, "sid=A")
        j1.saveIfChanged()
        assertTrue(file().exists())

        // The runtime's own close(), as it used to: removes the map entry, but does NOT retire it.
        jars.remove("demo", j1)

        // A settings change now tries to forget the session: nothing left in the map to retire.
        val retired = jars.remove("demo")?.also { it.retire() }
        assertNull("demonstrates the bug: forget() has nothing left to retire", retired)
        file().delete() // forgetPluginSession's own unconditional delete still runs
        assertFalse(file().exists())

        // The orphaned evaluation, still holding j1 (never retired), lands its late write.
        set(j1, "sid=A-late")
        j1.saveIfChanged()
        assertTrue("the bug: the old session's cookie is back on disk after forget", file().exists())
        assertEquals("A-late", jar().get(url, "sid"))
    }
}
