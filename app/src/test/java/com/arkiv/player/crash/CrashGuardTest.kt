package com.arkiv.player.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The glue: catches the exception, builds the report and leaves it in the queue.
 *
 * The rule governing this whole file: **nothing that happens in here can stop the stacktrace from
 * being saved, or change how the app dies**. If reading the identity fails, or the logcat, or the
 * disk, the report still goes out (with whatever's available) and the crash follows its normal
 * course.
 */
class CrashGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val data = DeviceData(
        kind = "tv",
        appVersion = "1.4.2 (142) release",
        system = "Android 14 (SDK 34) · samsung SM-S926B",
    )

    private fun guard(
        store: CrashStore,
        data: () -> DeviceData = { this.data },
        logcat: () -> String = { "linea de logcat" },
    ) = CrashGuard(store = store, data = data, logcat = logcat, now = { "2026-08-19T21:00:00Z" })

    private var folders = 0

    private fun store() = CrashStore(dir = tmp.newFolder("cola-${folders++}"), maxPending = 20)

    private fun onlyReport(store: CrashStore) = JSONObject(store.pending().single().readText())

    @Test
    fun `report leaves a report with the device's data in the queue`() {
        val store = store()

        guard(store).report(IllegalStateException("no habia stream"), "resolviendo el capitulo")

        val json = onlyReport(store)
        assertEquals("tv", json.getString("kind"))
        assertEquals("1.4.2 (142) release", json.getString("app_version"))
        assertEquals("resolviendo el capitulo", json.getString("contexto"))
        assertEquals("linea de logcat", json.getString("logcat"))
        assertEquals("2026-08-19T21:00:00Z", json.getString("ocurrido_en"))
    }

    @Test
    fun `what's reported by hand isn't fatal and the handler's is`() {
        val byHand = store()
        guard(byHand).report(RuntimeException("atrapada"), "un runCatching")
        assertFalse(onlyReport(byHand).getBoolean("fatal"))

        val fatal = store()
        guard(fatal).catch(Thread.currentThread(), RuntimeException("no atrapada"))
        assertTrue(onlyReport(fatal).getBoolean("fatal"))
    }

    @Test
    fun `the fatal report says which thread it crashed on`() {
        val store = store()
        val thread = Thread(null, {}, "hilo-del-player")

        guard(store).catch(thread, RuntimeException("revento"))

        assertEquals("hilo-del-player", onlyReport(store).getString("contexto"))
    }

    @Test
    fun `the saved stacktrace carries the chained cause`() {
        val store = store()
        val wrapped = RuntimeException("se cayo el player", IllegalStateException("no habia stream"))

        guard(store).catch(Thread.currentThread(), wrapped)

        val stacktrace = onlyReport(store).getString("stacktrace")
        assertTrue(stacktrace, stacktrace.contains("Caused by: java.lang.IllegalStateException: no habia stream"))
    }

    @Test
    fun `if the device's data can't be read, the stacktrace is still saved`() {
        val store = store()

        guard(store, data = { error("no se pudo leer DeviceType") })
            .catch(Thread.currentThread(), IllegalStateException("lo que de verdad importa"))

        val json = onlyReport(store)
        assertTrue(json.getString("stacktrace").contains("lo que de verdad importa"))
        assertEquals("", json.getString("kind"))
    }

    @Test
    fun `if the logcat can't be read, the stacktrace is still saved`() {
        val store = store()

        guard(store, logcat = { error("logcat no disponible") })
            .catch(Thread.currentThread(), IllegalStateException("lo que de verdad importa"))

        val json = onlyReport(store)
        assertTrue(json.getString("stacktrace").contains("lo que de verdad importa"))
        assertEquals("", json.getString("logcat"))
    }

    /**
     * The most important thing in the file: installing this can NOT change how the app dies. If
     * the handler ate the exception, a crash would turn into a silent hang.
     */
    @Test
    fun `the handler saves and passes the ball to the previous handler`() {
        val store = store()
        var received: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> received = e }
        val explosion = RuntimeException("boom")

        CrashHandler(previous = previous, guard = guard(store)).uncaughtException(Thread.currentThread(), explosion)

        assertTrue(onlyReport(store).getString("stacktrace").contains("boom"))
        assertEquals(explosion, received)
    }

    @Test
    fun `if saving blows up, it still passes the ball to the previous handler`() {
        var received: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> received = e }
        val brokenDisk = CrashStore(dir = tmp.newFile("no-es-carpeta"), maxPending = 20)
        val explosion = RuntimeException("boom")

        CrashHandler(previous = previous, guard = guard(brokenDisk)).uncaughtException(Thread.currentThread(), explosion)

        assertEquals(explosion, received)
    }

    @Test
    fun `with no previous handler it doesn't blow up`() {
        val store = store()

        CrashHandler(previous = null, guard = guard(store)).uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(onlyReport(store).getString("stacktrace").contains("boom"))
    }

    /**
     * `report` is called from `runCatching`s scattered around the app. If it blew up, it would
     * turn an already-caught error into a new crash -- the reporter killing the app it came to
     * diagnose.
     */
    @Test
    fun `report doesn't blow up even if the disk fails`() {
        val brokenDisk = CrashStore(dir = tmp.newFile("tampoco-es-carpeta"), maxPending = 20)

        guard(brokenDisk).report(RuntimeException("atrapada"), "un runCatching")
    }

    /** The file it left in the queue, for whoever wants to do something with it after saving. */
    @Test
    fun `catch returns the file it left in the queue`() {
        val store = store()

        val file = store.let { guard(it).catch(Thread.currentThread(), RuntimeException("boom")) }

        assertEquals(store.pending().single(), file)
    }
}
