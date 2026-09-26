package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Proves the trust gate for real: `kino.xuper` exists in a plugin's runtime if and only if that
 * specific INSTALLED plugin's recorded source address is [XuperPrivilege.SOURCE_REPO].
 *
 * `record.address` here comes from a real [PluginInstaller.install] call (as [PluginInstallerTest]
 * does), never a hand-built [InstalledRecord] -- a hand-built one would prove nothing, since the
 * whole point of the gate is that only a validated install can ever set that field. [PluginRegistry]
 * then reads it back from disk the same way the app does. From there, [installAndOpen] does exactly
 * what `AppGraph.openPluginRuntime` does with it -- compare `record.address` to
 * [XuperPrivilege.SOURCE_REPO], build [DefaultPrivilegedXuperHost] or [DefaultPluginHost]
 * accordingly, then [PluginRuntime.open] -- the same real `bind()` / `js.define("__kinoNative")`
 * conditional and the same real prelude.js feature-detect this task added. `AppGraph` itself is not
 * called: it needs an Android `Context` and the app's whole dependency graph (Room, TMDB, AniList...)
 * to construct, which a JVM unit test can't provide -- [PluginReferenceTest] takes the same
 * shortcut, building a runtime directly instead of through `AppGraph`. Nothing security-relevant is
 * skipped by that shortcut: openPluginRuntime's own body is exactly the lines reproduced below.
 */
class XuperPrivilegeGateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = mutableListOf<PluginRuntime>()

    @After fun stop() = opened.forEach { it.close() }

    private val files = mutableMapOf<String, ByteArray>()

    /** A manifest + script combo that just answers whether `kino.xuper` reached this runtime. */
    private fun publish(owner: String, repo: String, id: String) {
        val prefix = "https://raw.githubusercontent.com/$owner/$repo/HEAD/"
        files[prefix + "kino-plugin.json"] = JSONObject()
            .put("id", id).put("name", id).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("resolve", "home")))
            .toString().toByteArray()
        files[prefix + "plugin.js"] = (
            "export async function resolve(ref) { return typeof kino.xuper !== 'undefined'; }\n" +
                "export async function home() { return []; }"
            ).toByteArray()
    }

    /**
     * Installs one throwaway plugin through the real [PluginInstaller]/[PluginRegistry], then opens
     * its runtime the same way `AppGraph.openPluginRuntime` does.
     */
    private suspend fun installAndOpen(owner: String, repo: String, id: String): PluginRuntime {
        publish(owner, repo, id)
        val storeDir = tmp.newFolder()
        val store = PluginStore(File(storeDir, "plugins"), File(storeDir, "plugin-data"))
        val fetcher = PluginFetcher { url, max ->
            val bytes = files[url] ?: throw FileNotFoundException(url)
            if (bytes.size > max) throw IOException("archivo demasiado grande")
            bytes
        }
        val installer = PluginInstaller(store, fetcher, probe = { setOf("resolve", "home") })
        installer.install(installer.preview("$owner/$repo"))

        val registry = PluginRegistry(store)
        registry.reload()
        val plugin = registry.find(id) ?: error("plugin $id not registered")
        val script = store.readVerifiedScript(id)
        val hosts = plugin.hosts
        val http = PluginHttp(OkHttpClient(), id, hosts, "9.9.9")
        val storage = PluginStorage(File(storeDir, "storage.json"))
        // The exact branch AppGraph.openPluginRuntime runs: gated on record.address, never manifest.id.
        val host = if (plugin.record.address == XuperPrivilege.SOURCE_REPO) {
            DefaultPrivilegedXuperHost(id, http, storage, PluginConfig.EMPTY, null, hosts)
        } else {
            DefaultPluginHost(id, http, storage, PluginConfig.EMPTY, null, hosts)
        }
        return PluginRuntime.open(id, script, host, PluginEnv(appVersion = "9.9.9")).also { opened += it }
    }

    @Test fun `a plugin from the privileged repo sees kino xuper`() = runBlocking {
        val rt = installAndOpen("kinotvapp", "kino-plugin-xuper", "xuper")
        assertTrue(rt.call("resolve", JSONObject.quote("x"), 5_000).toBoolean())
    }

    @Test fun `a plugin claiming id xuper from any other repo never sees kino xuper`() = runBlocking {
        val rt = installAndOpen("someone-else", "kino-plugin-xuper-clone", "xuper")
        assertFalse(rt.call("resolve", JSONObject.quote("x"), 5_000).toBoolean())
    }
}
