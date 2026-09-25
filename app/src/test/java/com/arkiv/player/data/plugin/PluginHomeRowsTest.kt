package com.arkiv.player.data.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginHomeRowsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun plugin(id: String, caps: Set<String> = setOf("home", "resolve")) = InstalledPlugin(
        PluginManifest(id, id.uppercase(), "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), caps, null, null),
        InstalledRecord("o/$id", "1.0.0", "x", listOf("example.com"), 0L),
        null,
    )

    private val rowJson = """[{"id":"top","title":"Lo más visto","items":[{"id":"m1","ref":"R1","title":"Uno","kind":"movie"}]}]"""

    private class CountingCaller(val answer: (String) -> String) : PluginCaller {
        var calls = 0
        override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
            calls++
            return answer(pluginId)
        }
    }

    private var now = 0L
    private fun home(plugins: List<InstalledPlugin>, caller: PluginCaller) =
        PluginHomeRows({ plugins }, caller, cacheFileFor = { File(tmp.root, "$it/home.json") }, clock = { now }, log = {})

    @Test fun `rows come per plugin, in registry order, as plugin results`() = runTest {
        val rows = home(listOf(plugin("a"), plugin("b")), CountingCaller { rowJson }).rows().toList().last()
        assertEquals(listOf("a", "b"), rows.map { it.pluginId })
        assertEquals("Lo más visto", rows[0].title)
        assertEquals("A", rows[0].pluginName)
        assertEquals("plugin:a", rows[0].items.single().source)
    }

    @Test fun `plugins without the home capability are not asked`() = runTest {
        val caller = CountingCaller { rowJson }
        assertEquals(emptyList<PluginHomeRow>(), home(listOf(plugin("c", setOf("search", "resolve"))), caller).rows().toList().last())
        assertEquals(0, caller.calls)
    }

    @Test fun `a failing plugin contributes no rows and does not block the others`() = runTest {
        val caller = CountingCaller { id -> if (id == "a") throw PluginTimeoutException("home", 20_000) else rowJson }
        assertEquals(listOf("b"), home(listOf(plugin("a"), plugin("b")), caller).rows().toList().last().map { it.pluginId })
    }

    @Test fun `cached rows show first and are reused for six hours`() = runTest {
        val caller = CountingCaller { rowJson }
        home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(1, caller.calls)
        now += 5 * 3_600_000L
        val emissions = home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(1, emissions.first().size)
        assertEquals(1, caller.calls)
        now += 2 * 3_600_000L
        home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(2, caller.calls)
    }

    @Test fun `an oversized cache file is ignored and removed, never parsed`() = runTest {
        val file = File(tmp.root, "a/home.json").apply { parentFile!!.mkdirs() }
        // Fresh (fetchedAt = now) and valid, just too big: it must not be read at all.
        file.writeText("{\"fetchedAt\":0,\"json\":" + org.json.JSONObject.quote(rowJson + " ".repeat(PluginHomeRows.MAX_CACHE_BYTES)) + "}")
        val caller = CountingCaller { rowJson }
        val emissions = home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(emptyList<PluginHomeRow>(), emissions.first())
        assertEquals(1, caller.calls)
        assertEquals(listOf("top"), emissions.last().map { it.id })
        assertTrue(file.length() <= PluginHomeRows.MAX_CACHE_BYTES)
    }

    @Test fun `an answer too big to cache is shown but not written`() = runTest {
        val big = rowJson.replace("\"title\":\"Lo más visto\"", "\"title\":\"Lo más visto\",\"pad\":\"" + "x".repeat(PluginHomeRows.MAX_CACHE_BYTES) + "\"")
        val emissions = home(listOf(plugin("a")), CountingCaller { big }).rows().toList()
        assertEquals(listOf("top"), emissions.last().map { it.id })
        assertFalse(File(tmp.root, "a/home.json").exists())
    }

    @Test fun `an answer with no valid rows is not cached, so the next Home asks again`() = runTest {
        val caller = CountingCaller { "not json" }
        home(listOf(plugin("a")), caller).rows().toList()
        assertFalse(File(tmp.root, "a/home.json").exists())
        home(listOf(plugin("a")), caller).rows().toList()
        assertEquals(2, caller.calls)
    }

    /**
     * Fix round 1, finding 3: a settings change bumps the session revision (AppGraph's
     * `forgetPluginSession`). A `home()` call that was already in flight for the OLD session must
     * not have its answer shown or cached once it lands — otherwise the old account's rows could be
     * written to `home.json` right after a settings change deleted it, and served as "fresh" cache
     * to the NEW account for up to six hours.
     */
    @Test fun `a home answer is discarded, not shown or cached, when the session changed mid-flight`() = runTest {
        var revision = 0
        val caller = object : PluginCaller {
            override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
                // Simulates a settings change landing while this very call is still running.
                revision++
                return rowJson
            }
        }
        val rows = PluginHomeRows(
            { listOf(plugin("a")) }, caller,
            cacheFileFor = { File(tmp.root, "$it/home.json") },
            clock = { now }, sessionRevision = { revision }, log = {},
        ).rows().toList().last()
        assertEquals(emptyList<PluginHomeRow>(), rows)
        assertFalse("the old session's rows must never reach the cache file", File(tmp.root, "a/home.json").exists())
    }

    @Test fun `an unchanged session revision caches and shows the answer normally`() = runTest {
        val caller = CountingCaller { rowJson }
        val rows = PluginHomeRows(
            { listOf(plugin("a")) }, caller,
            cacheFileFor = { File(tmp.root, "$it/home.json") },
            clock = { now }, sessionRevision = { 7 }, log = {},
        ).rows().toList().last()
        assertEquals(listOf("top"), rows.map { it.id })
        assertTrue(File(tmp.root, "a/home.json").exists())
    }

    /**
     * Fix round 2, finding 3 (still open after round 1): the SAME leak, exercised end to end with a
     * call that genuinely STRADDLES a settings change — started before the forget sequence begins
     * and returning only once it has fully finished (both revision bumps AND the home.json delete,
     * mirroring AppGraph's `forgetPluginSession` + `DefaultPluginAdmin.saveSettings`'s
     * `afterSessionClosed`) — not just a check that call order or a key string changed. This is
     * exactly what a Home refresh in flight during a real settings save can hit.
     */
    @Test fun `a home call that straddles a full forget never leaves the old account's rows in home json`() = runTest {
        var revision = 0
        val cacheFile = File(tmp.root, "a/home.json")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val caller = object : PluginCaller {
            override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
                started.complete(Unit)
                release.await()
                return rowJson
            }
        }
        val homeRows = PluginHomeRows(
            { listOf(plugin("a")) }, caller, cacheFileFor = { cacheFile }, clock = { now }, sessionRevision = { revision }, log = {},
        )
        val collected = mutableListOf<List<PluginHomeRow>>()
        val job = launch { homeRows.rows().collect { collected += it } }
        started.await()

        // The FULL forget sequence, entirely WHILE the call above is still suspended:
        revision++            // forgetPluginSession's own bump
        cacheFile.delete()    // forgetPluginSession's delete (nothing there yet the first time -- fine)
        revision++            // afterSessionClosed's second bump, guaranteed after runtimes.close()

        release.complete(Unit) // only now does the call return -- entirely AFTER the forget completed
        job.join()

        assertFalse("A's rows must never land in home.json once a forget has fully completed", cacheFile.exists())
    }

    /**
     * Fix round 2, finding 3b, isolated precisely: a call whose OWN revision capture already sees
     * the FIRST forget bump (it started after `forgetPluginSession` ran) but returns before the
     * SECOND bump (`afterSessionClosed`, after `runtimes.close()`) has a revision that never moves
     * DURING the call — so the in-flight check in [refresh] alone (round 1's whole mechanism) sees
     * no change and would wrongly cache it as current. This is exactly the "still executes on the
     * OLD runtime R1" scenario the round 2 review traced. Proves the cache-stamp backstop (finding
     * 3a) is what actually catches it, on the very next read, once the second bump has happened.
     */
    @Test fun `a call whose revision never moves during it (window b) is still not trusted on the next read`() = runTest {
        var revision = 1 // the first forget bump already happened before this call is even made
        val cacheFile = File(tmp.root, "a/home.json")
        val firstCall = CountingCaller { rowJson }
        PluginHomeRows({ listOf(plugin("a")) }, firstCall, cacheFileFor = { cacheFile }, clock = { now }, sessionRevision = { revision }, log = {})
            .rows().toList()
        // Round 1's in-flight check alone would NOT have caught this: captured revision (1) never
        // changed during the call, so it wrote home.json stamped with revision=1.
        assertTrue(cacheFile.exists())

        revision = 2 // the second bump (afterSessionClosed), after runtimes.close() completed

        val secondCall = CountingCaller { rowJson }
        val emissions = PluginHomeRows({ listOf(plugin("a")) }, secondCall, cacheFileFor = { cacheFile }, clock = { now }, sessionRevision = { revision }, log = {})
            .rows().toList()
        assertEquals("the stamped-stale cache must not be shown even as the instant paint", emptyList<PluginHomeRow>(), emissions.first())
        assertEquals("a fresh call must have been made instead of trusting the stale cache", 1, secondCall.calls)
    }

    /**
     * Fix round 2, finding 3a's actual backstop: even if a stale write somehow lands (any timing),
     * the file's OWN stamped revision must make it self-detected as not-fresh on the very next
     * read — independent of the in-flight check above, and independent of the TTL (this cache is
     * timestamped "now", which the plain TTL check alone would treat as fresh).
     */
    @Test fun `a cache file stamped with an old revision is never trusted as fresh, even within the TTL`() = runTest {
        val cacheFile = File(tmp.root, "a/home.json").apply { parentFile!!.mkdirs() }
        cacheFile.writeText(JSONObject().put("fetchedAt", now).put("revision", 0).put("json", rowJson).toString())
        val revision = 2 // the session has moved on twice since that cache was written
        val caller = CountingCaller { rowJson }
        val emissions = PluginHomeRows(
            { listOf(plugin("a")) }, caller, cacheFileFor = { cacheFile }, clock = { now }, sessionRevision = { revision }, log = {},
        ).rows().toList()
        // Not even the instant-paint pass may show it: a revision mismatch isn't "old", it's a
        // DIFFERENT session's data.
        assertEquals(emptyList<PluginHomeRow>(), emissions.first())
        assertEquals(1, caller.calls)
    }
}
