package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** Author-signed plugins (apiVersion 5's `signature`) through the real installer and store. */
class SignedPluginInstallTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = mutableMapOf<String, ByteArray>()
    private val fetched = mutableListOf<String>()
    private val fetcher = PluginFetcher { url, max ->
        fetched += url
        val bytes = files[url] ?: throw FileNotFoundException(url)
        if (bytes.size > max) throw PluginFileTooBigException()
        bytes
    }
    private val probed = mutableListOf<String>()
    private lateinit var store: PluginStore
    private lateinit var installer: PluginInstaller
    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"
    private val author = TestSigning.newAuthorKey()
    private fun script(v: String = "1") =
        "// readable, version $v\nexport async function search(){ return [] }\nexport async function resolve(){ return null }\n"

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        installer = PluginInstaller(store, fetcher, probe = { s, _ -> probed += s; setOf("search", "resolve") }, clock = { 1L })
    }

    /** Publishes a signed plugin; [signed]'s parameters let a test sign something other than what it publishes. */
    private fun publishSigned(
        version: String = "1.0.0", prefix: String = base, key: TestSigning.AuthorKey = author,
        binding: String = "o/r", id: String = "demo", signedVersion: String = version,
        source: String = script(version), signedSource: String = source,
    ) {
        files[prefix + "kino-plugin.json"] = manifest(version, 5)
            .put("signature", TestSigning.field(signedSource.toByteArray(), binding, id, signedVersion, key)).toString().toByteArray()
        files[prefix + "plugin.js"] = source.toByteArray()
    }

    private fun publishPlain(version: String, prefix: String = base) {
        files[prefix + "kino-plugin.json"] = manifest(version, 4).toString().toByteArray()
        files[prefix + "plugin.js"] = script(version).toByteArray()
    }

    private fun manifest(version: String, api: Int) = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", api)
        .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve")))

    private fun failure(block: suspend () -> Unit): String =
        assertThrows(InstallException::class.java) { runBlocking { block() } }.message!!

    private val fingerprint get() = SignedEntry.fingerprint(author.publicRaw)

    @Test fun `a signed plugin installs its readable script, says so and pins its author key`() = runBlocking<Unit> {
        publishSigned()
        val preview = installer.preview("o/r")
        assertTrue(preview.signedEntry!!.firstKey)
        val lines = PluginConsent.extraLines(preview).map { it.text }
        // Just "Firmado por su autor": the key's fingerprint is shown in the plugin's details, not on the sheet.
        assertEquals(listOf("Firmado por su autor"), lines)
        assertFalse(lines.single().contains(fingerprint))
        val record = installer.install(preview)
        assertEquals(SignedEntry.hex(author.publicRaw), record.authorKey)
        assertEquals(script("1.0.0"), probed.single())
        // Downloaded once, at preview; the installed file is those exact, plain bytes, loaded like any plugin.
        assertEquals(1, fetched.count { it.endsWith("plugin.js") })
        assertArrayEquals(files[base + "plugin.js"], File(tmp.root, "plugins/demo/plugin.js").readBytes())
        assertEquals(script("1.0.0"), store.readVerifiedScript("demo"))
    }

    @Test fun `the fingerprint is the first 8 bytes of the key's SHA-256, grouped`() {
        val key = ByteArray(32) { it.toByte() }
        // sha256(00..1f) = 630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd
        assertEquals("630D-CD29-66C4-3366", SignedEntry.fingerprint(key))
    }

    @Test fun `a tampered script is refused before anyone is asked`() {
        publishSigned(source = script() + "// injected\n", signedSource = script())
        assertEquals(PluginInstaller.BAD_SIGNATURE_MESSAGE, failure { installer.preview("o/r") })
    }

    @Test fun `a signature made for another repo, folder, plugin id or version is refused`() {
        for ((binding, id, version) in listOf(Triple("other/r", "demo", "1.0.0"), Triple("o/r/sub", "demo", "1.0.0"), Triple("o/r", "another", "1.0.0"), Triple("o/r", "demo", "0.9.0"))) {
            publishSigned(binding = binding, id = id, signedVersion = version)
            assertEquals("$binding $id $version", PluginInstaller.BAD_SIGNATURE_MESSAGE, failure { installer.preview("o/r") })
        }
    }

    @Test fun `a key swapped into the manifest does not carry someone else's signature`() {
        publishSigned()
        val m = JSONObject(String(files[base + "kino-plugin.json"]!!))
        m.getJSONObject("signature").put("authorKey", TestSigning.hex(TestSigning.newAuthorKey().publicRaw))
        files[base + "kino-plugin.json"] = m.toString().toByteArray()
        assertEquals(PluginInstaller.BAD_SIGNATURE_MESSAGE, failure { installer.preview("o/r") })
    }

    @Test fun `a malformed signature field is refused by the manifest check`() {
        for (bad in listOf<Any>("x", JSONObject().put("authorKey", "00"), JSONObject().put("authorKey", "AB".repeat(32)).put("value", "00".repeat(64)),
            JSONObject().put("authorKey", "00".repeat(32)).put("value", "00".repeat(64)).put("extra", 1))) {
            val r = ManifestParser.parse(manifest("1.0.0", 5).put("signature", bad).toString()) as ManifestResult.Invalid
            assertEquals(bad.toString(), "signature", r.field)
            assertEquals(ManifestParser.BAD_SIGNATURE_FIELD, r.message)
        }
    }

    @Test fun `any ref works -- the code is readable, and the binding never includes the ref`() = runBlocking<Unit> {
        publishSigned(prefix = "https://raw.githubusercontent.com/o/r/v1.0.0/")
        val record = installer.install(installer.preview("o/r@v1.0.0"))
        assertEquals(SignedEntry.hex(author.publicRaw), record.authorKey)
    }

    @Test fun `an update signed by the same key applies without asking`() = runBlocking<Unit> {
        publishSigned("1.0.0"); installer.install(installer.preview("o/r"))
        publishSigned("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        assertEquals(script("1.1.0"), store.readVerifiedScript("demo"))
        // The same key on an update: the same plain line, not marked new.
        publishSigned("1.2.0")
        assertEquals(listOf(ConsentLine("Firmado por su autor")), PluginConsent.extraLines(installer.preview("o/r")))
    }

    @Test fun `a tampered update is refused and the installed version stays`() = runBlocking<Unit> {
        publishSigned("1.0.0"); installer.install(installer.preview("o/r"))
        publishSigned("1.1.0", source = script("1.1.0") + "// injected\n", signedSource = script("1.1.0"))
        assertEquals(UpdateOutcome.Failed(PluginInstaller.BAD_SIGNATURE_MESSAGE), installer.checkUpdate("demo"))
        assertEquals("1.0.0", store.get("demo")!!.record.version)
    }

    @Test fun `an update signed by another key is refused, and only uninstall plus install accepts it`() = runBlocking<Unit> {
        publishSigned("1.0.0"); installer.install(installer.preview("o/r"))
        val other = TestSigning.newAuthorKey()
        publishSigned("1.1.0", key = other)
        assertEquals(UpdateOutcome.Failed(PluginInstaller.AUTHOR_KEY_CHANGED_MESSAGE), installer.checkUpdate("demo"))
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        // "Reinstalar" from the list goes through the same preview: refused too.
        assertEquals(PluginInstaller.AUTHOR_KEY_CHANGED_MESSAGE, failure { installer.preview("o/r") })
        store.remove("demo", "Demo")
        val record = installer.install(installer.preview("o/r"))
        assertEquals(SignedEntry.hex(other.publicRaw), record.authorKey)
    }

    @Test fun `an update that drops the signature is refused`() = runBlocking<Unit> {
        publishSigned("1.0.0"); installer.install(installer.preview("o/r"))
        publishPlain("1.1.0")
        assertEquals(UpdateOutcome.Failed(PluginInstaller.SIGNATURE_DROPPED_MESSAGE), installer.checkUpdate("demo"))
        assertEquals("1.0.0", store.get("demo")!!.record.version)
    }

    @Test fun `an unsigned plugin that becomes signed waits for consent, then pins the key`() = runBlocking<Unit> {
        publishPlain("1.0.0"); installer.install(installer.preview("o/r"))
        assertNull(store.get("demo")!!.record.authorKey)
        publishSigned("1.1.0")
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertTrue(PluginConsent.extraLines(outcome.preview).single().isNew)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        installer.install(outcome.preview)
        assertEquals(SignedEntry.hex(author.publicRaw), store.get("demo")!!.record.authorKey)
    }

    @Test fun `a stored script changed after install is damaged at load, as for any plugin`() = runBlocking<Unit> {
        publishSigned(); installer.install(installer.preview("o/r"))
        File(tmp.root, "plugins/demo/plugin.js").appendText("// edited on disk\n")
        assertThrows(PluginDamagedException::class.java) { store.readVerifiedScript("demo") }
    }

    @Test fun `telemetry tags a signed plugin`() = runBlocking<Unit> {
        publishSigned()
        val events = mutableListOf<PluginTelemetry.Event>()
        val previous = PluginTelemetry.current
        PluginTelemetry.current = PluginTelemetry(facts = { null }, sink = PluginFailureSink { events += it })
        try {
            val failing = PluginInstaller(store, fetcher, probe = { _, _ -> throw PluginScriptException("SyntaxError") })
            assertThrows(InstallException::class.java) { runBlocking { failing.install(failing.preview("o/r")) } }
        } finally {
            PluginTelemetry.current = previous
        }
        assertEquals("true", events.single().tags["plugin_signed"])
    }
}
