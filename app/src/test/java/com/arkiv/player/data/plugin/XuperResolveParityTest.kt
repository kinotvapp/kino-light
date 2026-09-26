package com.arkiv.player.data.plugin

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.magis.FakeCredentialStore
import com.arkiv.player.data.magis.FakePortalClient
import com.arkiv.player.data.magis.MagisCatalog
import com.arkiv.player.data.magis.MagisPluginBridge
import com.arkiv.player.data.magis.MagisResolve
import com.arkiv.player.data.magis.MagisResult
import com.arkiv.player.data.magis.MagisSession
import com.arkiv.player.data.magis.MagisSource
import com.arkiv.player.data.magis.StoredSession
import com.arkiv.player.data.magis.testSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `kino.xuper.resolve` against `MagisSource.resolve`, on inputs recorded from a REAL activated session.
 *
 * Each `src/test/resources/xuper-parity/resolve-<n>.json` (local-only, never committed) was captured
 * on the phone by running the real `MagisSource.resolve` (throwaway instrumented harness, see the
 * Task 6 report): the ref, every portal call it made with its answer, and the playable or the error
 * it produced. The harness ran on an in-memory copy of the session and refused every call that would
 * (re)activate or log in a device; those refused calls are marked `blockedByHarness` and are replayed
 * as the same refusal. The only edit is the redaction each file's `redaction` field describes.
 *
 * Per fixture, both sides are fed the SAME recorded answers:
 *  1. the real [MagisSource] on the replay reproduces the device's playable or error exactly, with the
 *     same portal calls -- proves the replay is faithful, so step 2 compares against real behavior;
 *  2. `xuperResolve` makes the same portal calls, in the same order, with the same beans, and:
 *     - where the device got a playable, rebuilding a `GatewayPlayable` from the stream gives back the
 *       device's JSON, field for field;
 *     - where the device got an error from a portal answer, the envelope carries that answer's code
 *       through Task 4's `toPluginError()`; any other error is `unavailable` with MagisSource's text.
 */
class XuperResolveParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixtures(): List<Pair<String, JSONObject>> =
        File("src/test/resources/xuper-parity").listFiles { f -> f.name.startsWith("resolve-") }!!
            .sortedBy { it.name }
            .map { it.name to JSONObject(it.readText()) }

    /** A portal fake answering exactly what the device's portal (or the harness) answered, in order. */
    private fun replayPortal(fixture: JSONObject): FakePortalClient {
        val fake = FakePortalClient()
        val calls = fixture.getJSONArray("portal")
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            fake.queueResponse(c.getString("path"), answerOf(c))
        }
        return fake
    }

    private fun answerOf(c: JSONObject): MagisResult<JSONObject> = when {
        c.has("ok") -> MagisResult.Ok(c.getJSONObject("ok"))
        c.has("portalError") -> c.getJSONObject("portalError").let {
            MagisResult.PortalError(it.getString("code"), it.optString("msg").takeIf { m -> m.isNotEmpty() })
        }
        else -> MagisResult.RedError(java.io.IOException(c.getString("redError")))
    }

    /** The session the device resolved on: a copy of the live one, or the dead one the harness built. */
    private fun sessionFor(fixture: JSONObject, fake: FakePortalClient): MagisSession {
        val dead = fixture.optJSONObject("deadSession") ?: return testSession(fake)
        val store = FakeCredentialStore()
        store.saveSession(StoredSession(dead.getString("userId"), dead.getString("userToken"), "", dead.getString("sn")))
        return MagisSession(fake, store)
    }

    private fun resolver(fixture: JSONObject, fake: FakePortalClient, session: MagisSession) =
        MagisResolve(fake, session, appId = fixture.getString("appId"), apkVersion = fixture.getString("apkVersion"))

    private fun host(fixture: JSONObject, fake: FakePortalClient): DefaultPrivilegedXuperHost {
        val session = sessionFor(fixture, fake)
        return DefaultPrivilegedXuperHost(
            "xuper",
            PluginHttp(OkHttpClient(), "xuper", EffectiveHosts(emptyList()), "9.9.9"),
            PluginStorage(File(tmp.newFolder(), "storage.json")),
            PluginConfig.EMPTY,
            null,
            EffectiveHosts(emptyList()),
            lazyOf(MagisPluginBridge(MagisCatalog(fake, session), resolver(fixture, fake, session), TmdbApi(), vodStore = null)),
        )
    }

    private fun source(fixture: JSONObject, fake: FakePortalClient): MagisSource {
        val session = sessionFor(fixture, fake)
        return MagisSource(MagisCatalog(fake, session), resolver(fixture, fake, session), TmdbApi())
    }

    /** Same serialization the capture harness used for the device's playable. */
    private fun GatewayPlayable.toJson() = JSONObject()
        .put("kind", kind).put("url", url).put("headers", JSONObject(headers.toSortedMap() as Map<*, *>))
        .put("mime", mime).put("expiresAt", expiresAt).put("fallbackUrl", fallbackUrl ?: JSONObject.NULL)
        .put("durationMs", durationMs).put("videoCodec", videoCodec).put("container", container)
        .put("drmLicenseUrl", drmLicenseUrl)
        .put("subtitles", JSONArray(subtitles.map { JSONObject().put("lang", it.lang).put("url", it.url).put("format", it.format) }))

    /** The `GatewayPlayable` MagisSource would have returned, rebuilt from one `xuperResolve` stream. */
    private fun rebuilt(stream: JSONObject): JSONObject {
        val headers = stream.getJSONObject("headers").let { h -> h.keys().asSequence().associateWith { h.getString(it) } }
        val subs = stream.getJSONArray("subtitles")
        return GatewayPlayable(
            kind = "magis",
            url = stream.getString("url"),
            headers = headers,
            mime = stream.getString("mime"),
            durationMs = stream.getLong("durationMs"),
            videoCodec = stream.getString("videoCodec"),
            container = stream.getString("container"),
            subtitles = (0 until subs.length()).map { i ->
                subs.getJSONObject(i).let {
                    com.arkiv.player.data.gateway.GatewaySubtitle(it.getString("lang"), it.getString("url"), it.getString("format"))
                }
            },
        ).toJson()
    }

    /** Key order differs between Android's and the JVM's org.json, so JSON compares key-sorted, recursively. */
    private fun sorted(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":${sorted(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { sorted(v.get(it)).toString() }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    /** Key order differs between Android's and the JVM's org.json, so beans compare key-sorted. */
    private fun canonical(bean: JSONObject) = bean.keys().asSequence().sorted().joinToString(",") { "$it=${bean.get(it)}" }

    /**
     * Path and bean of every call, in order. A call the harness refused is compared by path only: its
     * arguments are generated fresh on every run by design.
     */
    private fun calls(fake: FakePortalClient, fixture: JSONObject): List<String> {
        val recorded = fixture.getJSONArray("portal")
        return fake.calls.mapIndexed { i, (path, bean) ->
            val blocked = i < recorded.length() && recorded.getJSONObject(i).optBoolean("blockedByHarness")
            if (blocked) path else path + " " + canonical(JSONObject(bean))
        }
    }

    private fun recordedCalls(fixture: JSONObject): List<String> {
        val recorded = fixture.getJSONArray("portal")
        return (0 until recorded.length()).map { i ->
            val c = recorded.getJSONObject(i)
            if (c.optBoolean("blockedByHarness")) c.getString("path") else c.getString("path") + " " + canonical(c.getJSONObject("bean"))
        }
    }

    /** The answer that ended the resolution: the last portal call the harness let through. */
    private fun lastPortalAnswer(fixture: JSONObject): MagisResult<JSONObject>? {
        val recorded = fixture.getJSONArray("portal")
        return (recorded.length() - 1 downTo 0).map { recorded.getJSONObject(it) }
            .firstOrNull { !it.optBoolean("blockedByHarness") }
            ?.let(::answerOf)
    }

    /** The code/message the envelope must carry for a fixture whose device resolution failed. */
    private fun expectedFailure(fixture: JSONObject): Pair<String, String> {
        val error = fixture.getJSONObject("expected").getJSONObject("error")
        return when (val last = lastPortalAnswer(fixture)) {
            is MagisResult.PortalError -> last.toPluginError()
            is MagisResult.RedError -> last.toPluginError()
            else -> PluginErrors.UNAVAILABLE to error.getString("message")
        }
    }

    @Test fun `the fixtures cover the capturable cases`() {
        val all = fixtures().map { it.second }
        assertEquals(6, all.size)
        val playables = all.filter { it.getJSONObject("expected").has("playable") }
        // A plain movie, a series chapter asked by number, a series' first chapter.
        assertTrue(playables.any { it.getJSONObject("request").getString("ref").startsWith("magis1:movie:") })
        assertTrue(playables.any { it.getJSONObject("request").getString("ref").matches(Regex("magis1:[a-z]+:[1-9]\\d*:.*")) && !it.getJSONObject("request").getString("ref").startsWith("magis1:movie:") })
        assertTrue(playables.any { it.getJSONObject("request").getString("ref").let { r -> r.contains(":0:") && !r.startsWith("magis1:movie:") } })
        // A series without the asked chapter, a title the portal doesn't have, a dead session.
        val failureCodes = all.filter { it.getJSONObject("expected").has("error") }.map { expectedFailure(it).first }.toSet()
        assertEquals(setOf(PluginErrors.UNAVAILABLE, PluginErrors.NOT_FOUND, PluginErrors.AUTH_REQUIRED), failureCodes)
        assertTrue(all.any { it.optJSONObject("deadSession") != null })
    }

    @Test fun `MagisSource on the replay reproduces the device's resolution`() = runBlocking {
        for ((name, fixture) in fixtures()) {
            val fake = replayPortal(fixture)
            val expected = fixture.getJSONObject("expected")
            val ref = fixture.getJSONObject("request").getString("ref")
            try {
                val playable = source(fixture, fake).resolve(ref)
                assertTrue(name + " resolved but the device failed: " + expected, expected.has("playable"))
                assertEquals(name, sorted(expected.getJSONObject("playable")), sorted(playable.toJson()))
            } catch (e: AssertionError) {
                throw e
            } catch (e: Exception) {
                assertTrue("$name failed but the device resolved: $e", expected.has("error"))
                val error = expected.getJSONObject("error")
                assertEquals(name, error.getString("type"), e.javaClass.simpleName)
                assertEquals(name, error.getString("message"), e.message)
            }
            assertEquals(name, recordedCalls(fixture), calls(fake, fixture))
        }
    }

    @Test fun `xuperResolve matches MagisSource resolve for every captured fixture`() = runBlocking {
        for ((name, fixture) in fixtures()) {
            val fake = replayPortal(fixture)
            val expected = fixture.getJSONObject("expected")
            val envelope = JSONObject(host(fixture, fake).xuperResolve(fixture.getJSONObject("request").getString("ref")))

            if (expected.has("playable")) {
                assertTrue(name + ": " + envelope, envelope.getBoolean("ok"))
                assertEquals(name, sorted(expected.getJSONObject("playable")), sorted(rebuilt(envelope.getJSONObject("data"))))
            } else {
                assertFalse(name + ": " + envelope, envelope.getBoolean("ok"))
                val (code, message) = expectedFailure(fixture)
                assertEquals(name, code, envelope.getString("code"))
                assertEquals(name, message, envelope.getString("message"))
            }
            assertEquals(name, recordedCalls(fixture), calls(fake, fixture))
        }
    }

    @Test fun `a second resolve of the same series reuses the chapter list`() = runBlocking {
        val fixture = fixtures().map { it.second }.first {
            it.getJSONObject("expected").has("playable") && !it.getJSONObject("request").getString("ref").startsWith("magis1:movie:")
        }
        val recorded = fixture.getJSONArray("portal")
        val fake = replayPortal(fixture)
        // Queue the device's answers once more, minus the chapter listing (the first call): MagisSource
        // caches that listing, so a second resolve must not ask for it again.
        for (i in 1 until recorded.length()) recorded.getJSONObject(i).let { fake.queueResponse(it.getString("path"), answerOf(it)) }
        val host = host(fixture, fake)
        val ref = fixture.getJSONObject("request").getString("ref")
        val first = JSONObject(host.xuperResolve(ref))
        val second = JSONObject(host.xuperResolve(ref))
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertEquals(first.getJSONObject("data").getString("url"), second.getJSONObject("data").getString("url"))
        assertEquals(1, fake.timesCalled(recorded.getJSONObject(0).getString("path")))
    }

    // --- not device captures ---------------------------------------------------------------------
    //
    // A geo-blocked session couldn't be produced on the test phone without touching its real
    // session/region state (see the Task 5 and Task 6 reports). This feeds the geo-block answer
    // through a fake portal and checks the code reaches Task 4's mapping, while MagisSource shows it
    // as its own blocked dialog.

    private fun syntheticHost(fake: FakePortalClient): DefaultPrivilegedXuperHost {
        val session = testSession(fake)
        return DefaultPrivilegedXuperHost(
            "xuper",
            PluginHttp(OkHttpClient(), "xuper", EffectiveHosts(emptyList()), "9.9.9"),
            PluginStorage(File(tmp.newFolder(), "storage.json")),
            PluginConfig.EMPTY,
            null,
            EffectiveHosts(emptyList()),
            lazyOf(MagisPluginBridge(MagisCatalog(fake, session), MagisResolve(fake, session), TmdbApi(), vodStore = null)),
        )
    }

    @Test fun `a geo-blocked resolve is geo_blocked, and MagisSource shows it as blocked`() = runBlocking {
        val blocked = MagisResult.PortalError("portal100024", "blocked")
        for (ref in listOf("magis1:movie:0:ABC", "magis1:teleplay:2:ABC")) {
            val envelope = JSONObject(syntheticHost(FakePortalClient().apply { defaultResponse = blocked }).xuperResolve(ref))
            assertFalse(envelope.getBoolean("ok"))
            assertEquals(ref, PluginErrors.GEO_BLOCKED, envelope.getString("code"))
            assertEquals(ref, "blocked", envelope.getString("message"))

            val fake = FakePortalClient().apply { defaultResponse = blocked }
            val session = testSession(fake)
            try {
                MagisSource(MagisCatalog(fake, session), MagisResolve(fake, session), TmdbApi()).resolve(ref)
                fail("MagisSource resolved a geo-blocked $ref")
            } catch (e: GatewayBlockedException) {
                // expected: the same answer MagisSource turns into its blocked dialog
            }
        }
    }

    @Test fun `a ref that isn't Xuper's is unavailable with MagisSource's text`() = runBlocking {
        val envelope = JSONObject(syntheticHost(FakePortalClient()).xuperResolve("https://example.com/video.mp4"))
        assertFalse(envelope.getBoolean("ok"))
        assertEquals(PluginErrors.UNAVAILABLE, envelope.getString("code"))
        assertEquals("ese ref no es de Xuper: no se puede reproducir", envelope.getString("message"))
    }
}
