package com.arkiv.player.companion

import com.arkiv.player.MainThreadInitGuard
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ERRORES-AHE: the companion sync's plugin-secret table (pluginRegistry) is never built on the main thread. */
class CompanionSyncStarterTest {
    private val mainExec = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
    private val ioExec = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }
    private val main = mainExec.asCoroutineDispatcher()
    private val io = ioExec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val guard = MainThreadInitGuard(isMainThread = { Thread.currentThread().name.startsWith("fake-main") }, onViolation = { })

    @After
    fun tearDown() {
        mainExec.shutdownNow()
        ioExec.shutdownNow()
    }

    private fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }

    @Test
    fun `the dependencies are built off the main thread and the engine starts on it`() {
        val started = CountDownLatch(1)
        var startThread = ""
        val starter = CompanionSyncStarter(
            scope, main, io,
            resolve = { guard.check("pluginRegistry"); "deps" },
            fallback = { "none" },
            start = { d -> assertEquals("deps", d); startThread = Thread.currentThread().name; started.countDown() },
            stopSync = { },
            onError = { throw AssertionError(it) },
        )
        onMain { starter.start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(startThread, startThread.startsWith("fake-main"))
        assertTrue("built on the main thread: ${guard.violations()}", guard.violations().isEmpty())
    }

    @Test
    fun `a stop while the dependencies are still building never starts the engine`() {
        val release = CountDownLatch(1)
        val resolved = CountDownLatch(1)
        var starts = 0
        var stops = 0
        val starter = CompanionSyncStarter(
            scope, main, io,
            resolve = { release.await(5, TimeUnit.SECONDS); resolved.countDown(); "deps" },
            fallback = { "none" },
            start = { starts++ },
            stopSync = { stops++ },
            onError = { },
        )
        onMain { starter.start() }
        onMain { starter.stop() }
        release.countDown()
        assertTrue(resolved.await(5, TimeUnit.SECONDS))
        onMain { } // drain the main queue: a resumed start would have run by now
        Thread.sleep(50)
        onMain { }
        assertEquals(0, starts)
        assertEquals(1, stops)
    }

    @Test
    fun `a failing plugin table is reported and the rest of the sync still starts`() {
        val started = CountDownLatch(1)
        var got = ""
        var error: Throwable? = null
        val starter = CompanionSyncStarter(
            scope, main, io,
            resolve = { error("keystore gone") },
            fallback = { "without-secrets" },
            start = { d -> got = d; started.countDown() },
            stopSync = { },
            onError = { error = it },
        )
        onMain { starter.start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals("without-secrets", got)
        assertEquals("keystore gone", error?.message)
    }

    @Test
    fun `the app's lifecycle observers never read the plugin sync lazies themselves`() {
        val src = File("src/main/java/com/arkiv/player/ArkivApp.kt").readText()
            .substringAfter("private fun wireCompanionLifecycle()").substringBefore("private class SyncDeps")
        val observers = src.substringAfter("val observer =")
        assertFalse("pluginSecretSync read in a lifecycle callback (main thread)", observers.contains("graph.pluginSecretSync"))
        assertFalse(observers.contains("startSync("))
    }

    @Test
    fun `the update prompt behind the splash reads the cast session only after the warm-up`() {
        val src = File("src/main/java/com/arkiv/player/ui/player/CastTroubleDialog.kt").readText()
            .substringAfter("fun rememberUpdatePromptWaitsForCast").substringBefore("val s = session")
        src.lines().filter { it.contains("graph.castSession") }.also { assertTrue(it.isNotEmpty()) }.forEach { line ->
            assertTrue("castSession (repository) read before the warm-up: $line", line.contains("graph.warmedUp.value"))
        }
    }
}
