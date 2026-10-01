package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/**
 * Author signatures are strictly opt-in: every plugin written before them (apiVersion 1-4, with or
 * without sealed secrets, and every Nuvio-converted one) parses, installs, updates, consents and
 * loads exactly as before -- no new consent line, no pinned key, no extra field on disk, and the seal
 * agreement is never touched for its code.
 */
class SignedPluginBackwardCompatTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = mutableMapOf<String, ByteArray>()
    private val fetcher = PluginFetcher { url, _ -> files[url] ?: throw FileNotFoundException(url) }
    private var agreementCalls = 0
    private val agreement = X25519Agreement { agreementCalls++; TestSealing.x25519(TestSealing.TEST_PRIVATE, it) }
    private val refusingAgreement = X25519Agreement { error("a plain entry must never reach the seal agreement") }
    private lateinit var store: PluginStore
    private lateinit var installer: PluginInstaller
    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"
    private val script = "export async function search(){ return [] }\nexport async function resolve(){ return null }\n"

    // installed.json's keys before author signatures existed: a plain plugin's record must still have exactly these.
    private val recordKeysBefore = setOf(
        "address", "version", "sha256", "hosts", "installedAt", "enabled", "unresponsive", "damaged", "lastUpdateCheckAt",
        "pendingVersion", "pendingHosts", "permissions", "pendingPermissions", "capabilities", "pendingCapabilities",
        "insecureHosts", "pendingInsecureHosts", "exports", "liveStreamHostsAny", "pendingLiveStreamHostsAny",
        "sealedSecrets", "pendingSealedSecrets", "streamHostsAny", "pendingStreamHostsAny", "fetchHostsAny",
        "pendingFetchHostsAny", "rejectedHosts", "nuvioRepo", "nuvioScraperId", "anyVideoHost",
    )

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        installer = PluginInstaller(
            store, fetcher, probe = { _, _ -> setOf("search", "resolve") }, clock = { 1L },
            sealAgreement = agreement, sealRecipient = TestSealing.TEST_PUBLIC,
        )
    }

    private fun manifest(api: Int, version: String = "1.0.0", extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", api)
        .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve"))).apply(extra)

    private fun publish(m: JSONObject) {
        files[base + "kino-plugin.json"] = m.toString().toByteArray()
        files[base + "plugin.js"] = script.toByteArray()
    }

    private fun assertPlainEverywhere(expectSecretsLine: Boolean) = runBlocking {
        val preview = installer.preview("o/r")
        assertNull(preview.manifest.signature)
        assertNull(preview.signedEntry)
        val lines = PluginConsent.extraLines(preview).map { it.text }
        assertEquals(if (expectSecretsLine) listOf("Usa datos sellados por su autor") else emptyList<String>(), lines)
        val callsBefore = agreementCalls
        val record = installer.install(preview)
        // The secrets were opened once at preview (as before); the install itself opens nothing.
        assertEquals(callsBefore, agreementCalls)
        assertNull(record.authorKey)
        assertEquals(recordKeysBefore, JSONObject(File(tmp.root, "plugins/demo/installed.json").readText()).keys().asSequence().toSet())
        assertEquals(script, store.readVerifiedScript("demo"))
    }

    @Test fun `apiVersion 1 to 4 plain plugins install, consent and load exactly as before`() {
        for (api in 1..4) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            publish(manifest(api))
            assertPlainEverywhere(expectSecretsLine = false)
        }
    }

    @Test fun `an apiVersion 4 plugin with sealed secrets is unchanged`() {
        val seal = TestSealing.seal("shh", "o/r", "apiKey")
        publish(manifest(4) { put("secrets", JSONObject().put("apiKey", seal)) })
        assertPlainEverywhere(expectSecretsLine = true)
    }

    @Test fun `below apiVersion 5 a stray signature is ignored like any unknown field`() {
        for (api in 1..4) {
            val m = ManifestParser.parse(manifest(api) { put("signature", "not even an object") }.toString()) as ManifestResult.Valid
            assertNull(m.manifest.signature)
        }
        // So an apiVersion 4 plugin carrying one installs unsigned, with nothing pinned.
        publish(manifest(4) { put("signature", JSONObject().put("authorKey", "00".repeat(32)).put("value", "00".repeat(64))) })
        assertPlainEverywhere(expectSecretsLine = false)
    }

    @Test fun `older Kino's refusal of apiVersion 5 is the clear update message`() {
        // What Kino 0.9.45 and older answer (SUPPORTED_API was 4): the same rule, one version up.
        val r = ManifestParser.parse(manifest(ManifestParser.SUPPORTED_API + 1).toString()) as ManifestResult.Invalid
        assertEquals("apiVersion", r.field)
        assertEquals("Este plugin necesita una versión más nueva de Kino", r.message)
    }

    @Test fun `a plain update of a plain plugin applies without asking, as before`() = runBlocking {
        publish(manifest(4, "1.0.0")); installer.install(installer.preview("o/r"))
        publish(manifest(4, "1.1.0"))
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        assertNull(store.get("demo")!!.record.authorKey)
    }

    @Test fun `apiVersion 5 itself is opt-in -- without a signature it is an unsigned plugin`() {
        publish(manifest(5))
        assertPlainEverywhere(expectSecretsLine = false)
    }

    @Test fun `a record written by an older Kino reads back without a pinned key`() {
        val old = JSONObject().put("address", "o/r").put("version", "1.0.0").put("sha256", "ab").put("hosts", JSONArray())
        assertNull(InstalledRecord.fromJson(old.toString())!!.authorKey)
    }

    @Test fun `a Nuvio-converted plugin installs and loads untouched`() = runBlocking {
        val manifestJson = """{ "name": "n", "scrapers": [ { "id": "fakesrc", "name": "FakeSrc", "filename": "providers/fakesrc.js", "enabled": true, "supportedTypes": ["movie"] } ] }"""
        val scraperJs = "function getStreams() { return [{ name: \"F\", title: \"t\", url: \"https://fakesrc.example/v.mp4\", quality: \"1080p\" }]; }\nmodule.exports = { getStreams: getStreams };"
        val realInstaller = PluginInstaller(store, PluginFetcher { url, _ -> throw FileNotFoundException(url) }, probe = { s, _ ->
            val runtime = PluginRuntime.open("probe", s, ProbePluginHost, PluginEnv(appVersion = "1.0"))
            try { runtime.exports } finally { runtime.close() }
        }, sealAgreement = refusingAgreement)
        val nuvio = NuvioPluginInstaller(realInstaller, PluginFetcher { url, _ ->
            mapOf(
                "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/manifest.json" to manifestJson,
                "https://raw.githubusercontent.com/owner/nuvio-repo/HEAD/providers/fakesrc.js" to scraperJs,
            )[url]?.toByteArray() ?: throw FileNotFoundException(url)
        })
        val preview = nuvio.previewScraper("owner/nuvio-repo", "fakesrc")
        assertNull(preview.manifest.signature)
        assertNull(preview.signedEntry)
        assertTrue(PluginConsent.extraLines(preview).none { it.text.startsWith(PluginConsent.SIGNED_LINE) })
        val record = nuvio.install(preview)
        assertNull(record.authorKey)
        val stored = store.list().single()
        assertTrue(stored.manifest.entry.endsWith(".js"))
        assertEquals(String(preview.nuvioOrigin!!.script, Charsets.UTF_8), store.readVerifiedScript(stored.manifest.id))
        assertEquals(recordKeysBefore, JSONObject(File(stored.dir, "installed.json").readText()).keys().asSequence().toSet())
    }
}
