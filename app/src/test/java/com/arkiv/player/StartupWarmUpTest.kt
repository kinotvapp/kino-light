package com.arkiv.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StartupWarmUpTest {

    // --- startupContent: the gate that keeps the root from composing on a half-built graph ---------

    @Test
    fun `the root never composes before the warm-up finished, however long it takes`() {
        for (read in listOf(false, true)) for (creds in listOf(false, true)) {
            assertEquals(StartupContent.PREPARING, startupContent(introDone = true, warmedUp = false, credentialsRead = read, hasCredentials = creds))
        }
    }

    @Test
    fun `nothing composes behind the intro while it still plays`() {
        assertEquals(StartupContent.NONE, startupContent(introDone = false, warmedUp = true, credentialsRead = true, hasCredentials = true))
    }

    @Test
    fun `after the warm-up the credentials decide between activation and the app`() {
        assertEquals(StartupContent.PREPARING, startupContent(introDone = true, warmedUp = true, credentialsRead = false, hasCredentials = false))
        assertEquals(StartupContent.ACTIVATION, startupContent(introDone = true, warmedUp = true, credentialsRead = true, hasCredentials = false))
        assertEquals(StartupContent.APP, startupContent(introDone = true, warmedUp = true, credentialsRead = true, hasCredentials = true))
    }

    // --- WarmUpTimeline -----------------------------------------------------------------------------

    @Test
    fun `each phase is timed, a failing one too, and reported as extras in order`() {
        var now = 0L
        val t = WarmUpTimeline { now }
        t.phase("magis_portal") { now += 4200 }
        try {
            t.phase("plugin_registry") { now += 300; error("boom") }
            fail("the phase's exception must reach the caller")
        } catch (_: IllegalStateException) {
        }
        assertEquals(mapOf("magis_portal" to 4200L, "plugin_registry" to 300L), t.durations())
        assertEquals(mapOf("phase_magis_portal_ms" to "4200", "phase_plugin_registry_ms" to "300"), t.extras())
        assertEquals("magis_portal=4200 plugin_registry=300", t.summary())
        assertEquals(4500L, t.elapsed())
    }

    @Test
    fun `phases timed from several threads all land`() {
        val t = WarmUpTimeline { System.nanoTime() / 1_000_000 }
        val threads = (1..8).map { i -> Thread { t.phase("p$i") { Thread.sleep(5) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals((1..8).map { "p$it" }.toSet(), t.durations().keys)
    }

    // --- MainThreadInitGuard ------------------------------------------------------------------------

    @Test
    fun `a heavy lazy built on the main thread is reported once, never off it`() {
        var main = false
        val seen = mutableListOf<String>()
        val guard = MainThreadInitGuard(isMainThread = { main }, onViolation = { seen += it })
        guard.check("magisPortal")
        assertTrue(seen.isEmpty())
        main = true
        guard.check("magisPortal")
        guard.check("magisPortal")
        guard.check("magisSession")
        assertEquals(listOf("magisPortal", "magisSession"), seen)
        assertEquals(setOf("magisPortal", "magisSession"), guard.violations())
    }

    // --- Static audit: what the UI reads from the graph is built by the warm-up ---------------------

    /**
     * The graph members whose first access is heavy (Keystore, native 3DES, Room, plugin files) or
     * forces one that is. The root may read them in composition ONLY because the warm-up built them
     * first, off the main thread: a screen that starts reading one the warm-up doesn't build would
     * bring the startup ANR back.
     */
    private val heavy = listOf(
        "magisSession", "magisAccount", "magisLive", "liveCatalog", "magisHomeCatalog", "seedsExhausted",
        "contentSource", "repository", "tmdbApi", "liveModule", "xuperLive", "xuperLiveBlocked",
        "hasLiveSources", "genreTiles", "pluginRegistry", "pluginHomeRows", "pluginsChanged",
    )

    @Test
    fun `every heavy graph member the UI reads is pre-built by the warm-up`() {
        val graphSrc = File("src/main/java/com/arkiv/player/AppGraph.kt").readText()
        val body = graphSrc.substringAfter("private suspend fun warmUpLocked()").substringBefore("val gateMs")
        val uiFiles = File("src/main/java/com/arkiv/player/ui").walkTopDown().filter { it.extension == "kt" } +
            File("src/main/java/com/arkiv/player/MainActivity.kt")
        val read = uiFiles.flatMap { f -> heavy.filter { name -> Regex("""graph\.$name\b""").containsMatchIn(f.readText()) } }.toSet()
        assertTrue("the audit found nothing: the paths moved?", read.isNotEmpty())
        // pluginsChanged is a getter over pluginRegistry (built by the registry phase).
        val missing = read.filterNot { name -> Regex("""\b${if (name == "pluginsChanged") "pluginRegistry" else name}\b""").containsMatchIn(body) }
        assertTrue("read by the UI but not built by the warm-up: $missing", missing.isEmpty())
    }

    @Test
    fun `the root waits for the warm-up with no timeout, and credentials are read off the main thread`() {
        val src = File("src/main/java/com/arkiv/player/MainActivity.kt").readText()
        assertFalse("a timeout that composes the root anyway is what froze the UI thread", src.contains("withTimeoutOrNull"))
        assertTrue(src.contains("startupContent("))
        src.lines().filter { it.contains("credentialsStore.read()") }.forEach { line ->
            assertTrue("Keystore read on the main thread: $line", line.contains("withContext(Dispatchers.IO)"))
        }
    }

    @Test
    fun `the warm-up's gate never waits on a network step`() {
        val src = File("src/main/java/com/arkiv/player/AppGraph.kt").readText()
        val beforeGate = src.substringAfter("private suspend fun warmUpLocked()").substringBefore("_warmedUp.value = true")
        assertFalse(beforeGate.contains("autoInstallXuperPluginIfNeeded"))
        assertFalse(beforeGate.contains("checkForUpdate"))
    }
}
