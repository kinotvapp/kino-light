package com.arkiv.player.data.plugin

import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Sealed code (apiVersion 5's `sealedEntry`) through the real installer, store and runtime loader. */
class SealedCodeInstallTest {
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
    private val agreements = AtomicInteger()
    private val agreement = X25519Agreement { agreements.incrementAndGet(); TestSealing.x25519(TestSealing.TEST_PRIVATE, it) }
    private lateinit var store: PluginStore
    private lateinit var installer: PluginInstaller
    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"
    private val author = TestSealing.newAuthorKey()
    // A marker that must never appear in any file Kino writes.
    private val secretMarker = "SECRET_PLAINTEXT_MARKER_8f1e"
    private fun script(v: String = "1") =
        "const $secretMarker = '$v';\nexport async function search(){ return [] }\nexport async function resolve(){ return null }\n"

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        installer = PluginInstaller(
            store, fetcher, probe = { s, _ -> probed += s; setOf("search", "resolve") }, clock = { 1L },
            sealAgreement = agreement, sealRecipient = TestSealing.TEST_PUBLIC,
        )
    }

    private fun publishSealed(
        version: String = "1.0.0", prefix: String = base, key: TestSealing.AuthorKey = author,
        binding: String = "o/r", id: String = "demo", source: String = script(version), blob: ByteArray? = null,
    ) {
        files[prefix + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", 5)
            .put("sealedEntry", "plugin.kjs").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString().toByteArray()
        files[prefix + "plugin.kjs"] = blob ?: TestSealing.sealCode(source, binding, id, key)
    }

    private fun publishPlain(version: String, prefix: String = base) {
        files[prefix + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", 4)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString().toByteArray()
        files[prefix + "plugin.js"] = script(version).toByteArray()
    }

    private fun installed() = PluginRegistry(store).apply { reload() }.find("demo")!!
    private fun failure(block: suspend () -> Unit): String =
        assertThrows(InstallException::class.java) { runBlocking { block() } }.message!!

    @Test fun `a sealed plugin installs, pins its author key and stores only the sealed file`() = runBlocking<Unit> {
        publishSealed()
        val preview = installer.preview("o/r")
        assertTrue(preview.newSealedCode)
        assertTrue(preview.sealedEntry!!.firstKey)
        val lines = PluginConsent.extraLines(preview).map { it.text }
        assertTrue(lines.toString(), "El código de este plugin está cifrado" in lines)
        assertTrue(lines.toString(), "Firmado por su autor con la clave ${SealedCode.fingerprint(author.publicRaw)} (primera vez)" in lines)
        val record = installer.install(preview)
        assertEquals(SealedCode.hex(author.publicRaw), record.authorKey)
        assertEquals(sha256Hex(files[base + "plugin.kjs"]!!), record.sha256)
        assertEquals(script("1.0.0"), probed.single())
        // The blob was fetched once, at preview; install wrote those exact bytes.
        assertEquals(1, fetched.count { it.endsWith("plugin.kjs") })
        assertArrayEquals(files[base + "plugin.kjs"], File(tmp.root, "plugins/demo/plugin.kjs").readBytes())
        assertNoPlaintextOnDisk()
    }

    @Test fun `the runtime opens it lazily, off the caller's thread, and never writes it anywhere`() = runBlocking<Unit> {
        publishSealed(); installer.install(installer.preview("o/r"))
        val before = agreements.get()
        // Listing, reloading and reading installed plugins never decrypts anything.
        PluginRegistry(store).apply { reload() }.plugins.value
        store.list(); store.get("demo")
        assertEquals(before, agreements.get())

        var openedOn: String? = null
        val trackingAgreement = X25519Agreement { openedOn = Thread.currentThread().name; agreement.sharedSecret(it) }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kino-io-test") }
        var timing: Pair<Long, Int>? = null
        val loaded = try {
            loadEntryScript(store, installed(), trackingAgreement, TestSealing.TEST_PUBLIC, executor.asCoroutineDispatcher()) { ms, n -> timing = ms to n }
        } finally { executor.shutdown() }
        assertEquals(script("1.0.0"), loaded)
        assertTrue(openedOn, openedOn!!.startsWith("kino-io-test"))
        assertEquals(script("1.0.0").length, timing!!.second)
        assertNoPlaintextOnDisk()
    }

    @Test fun `a stored sealed file that was changed, or a pinned key that was edited, is damaged at load`() = runBlocking<Unit> {
        publishSealed(); installer.install(installer.preview("o/r"))
        File(tmp.root, "plugins/demo/plugin.kjs").appendBytes(byteArrayOf(0))
        assertThrows(PluginDamagedException::class.java) { runBlocking { loadEntryScript(store, installed(), agreement, TestSealing.TEST_PUBLIC) } }
        File(tmp.root, "plugins/demo/plugin.kjs").writeBytes(files[base + "plugin.kjs"]!!)
        store.updateRecord("demo") { it.copy(authorKey = SealedCode.hex(TestSealing.newAuthorKey().publicRaw)) }
        assertThrows(PluginDamagedException::class.java) { runBlocking { loadEntryScript(store, installed(), agreement, TestSealing.TEST_PUBLIC) } }
    }

    @Test fun `this build without the native key says so, at install and at load`() = runBlocking<Unit> {
        publishSealed()
        val noNative = PluginInstaller(store, fetcher, probe = { _, _ -> setOf("search", "resolve") })
        assertEquals(PluginInstaller.NO_CODE_NATIVE_MESSAGE, failure { noNative.preview("o/r") })
        installer.install(installer.preview("o/r"))
        val e = assertThrows(PluginScriptException::class.java) {
            runBlocking { loadEntryScript(store, installed(), { throw SealException(SealedSecrets.NO_NATIVE_MESSAGE) }, TestSealing.TEST_PUBLIC) }
        }
        assertEquals(PluginInstaller.NO_CODE_NATIVE_MESSAGE, e.message)
    }

    @Test fun `sealed code opens only at HEAD -- any explicit ref refuses before downloading it`() {
        for (ref in listOf("main", "v1.0.0", "a1b2c3d")) {
            publishSealed(prefix = "https://raw.githubusercontent.com/o/r/$ref/")
            fetched.clear()
            assertEquals(ref, PluginInstaller.NON_HEAD_CODE_MESSAGE, failure { installer.preview("o/r@$ref") })
            assertTrue(fetched.none { it.endsWith(".kjs") })
        }
    }

    @Test fun `a pasted tree URL installs ref-less when the default branch serves the same manifest, else names the address`() = runBlocking<Unit> {
        publishSealed(prefix = "https://raw.githubusercontent.com/o/r/main/")
        publishSealed(prefix = base)
        files["https://raw.githubusercontent.com/o/r/main/kino-plugin.json"] = files[base + "kino-plugin.json"]!!
        val p = installer.preview("https://github.com/o/r/tree/main")
        assertEquals("o/r", p.address.canonical)
        publishSealed("2.0.0", prefix = "https://raw.githubusercontent.com/o/r/dev/")
        assertEquals(PluginInstaller.treeCodeMessage(PluginAddress("o", "r")), failure { installer.preview("https://github.com/o/r/tree/dev") })
    }

    @Test fun `a blob sealed for another repo, folder or plugin id is refused`() {
        publishSealed(binding = "other/r")
        assertEquals(PluginInstaller.WRONG_CODE_MESSAGE, failure { installer.preview("o/r") })
        publishSealed(binding = "o/r/sub")
        assertEquals(PluginInstaller.WRONG_CODE_MESSAGE, failure { installer.preview("o/r") })
        publishSealed(id = "another")
        assertEquals(PluginInstaller.WRONG_CODE_MESSAGE, failure { installer.preview("o/r") })
    }

    @Test fun `a tampered blob or a forged signature is refused`() {
        val good = TestSealing.sealCode(script(), "o/r", "demo", author)
        publishSealed(blob = good.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() })
        assertEquals(PluginInstaller.BAD_SIGNATURE_MESSAGE, failure { installer.preview("o/r") })
        val unsigned = good.copyOf(good.size - SealedCode.SIGNATURE_BYTES)
        publishSealed(blob = unsigned + TestSealing.signCode(unsigned, TestSealing.newAuthorKey()))
        assertEquals(PluginInstaller.BAD_SIGNATURE_MESSAGE, failure { installer.preview("o/r") })
        publishSealed(blob = "export function resolve(){}".toByteArray())
        assertEquals(PluginInstaller.WRONG_CODE_MESSAGE, failure { installer.preview("o/r") })
    }

    @Test fun `a sealed file over the 1 MiB download cap is refused`() {
        publishSealed(blob = ByteArray(PluginInstaller.MAX_SCRIPT_BYTES + 1))
        assertEquals("No se pudo leer el plugin de GitHub. Intenta de nuevo en un rato.", failure { installer.preview("o/r") })
    }

    @Test fun `an update signed by the same key applies, and its new blob is what loads`() = runBlocking<Unit> {
        publishSealed("1.0.0"); installer.install(installer.preview("o/r"))
        publishSealed("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        assertEquals(sha256Hex(files[base + "plugin.kjs"]!!), store.get("demo")!!.record.sha256)
        assertEquals(script("1.1.0"), loadEntryScript(store, installed(), agreement, TestSealing.TEST_PUBLIC))
        // The same key on an update: named, but not "primera vez".
        publishSealed("1.2.0")
        val lines = PluginConsent.extraLines(installer.preview("o/r")).map { it.text }
        assertTrue(lines.toString(), "Firmado por su autor con la clave ${SealedCode.fingerprint(author.publicRaw)}" in lines)
    }

    @Test fun `an update signed by another key is refused, and only uninstall plus install accepts it`() = runBlocking<Unit> {
        publishSealed("1.0.0"); installer.install(installer.preview("o/r"))
        val other = TestSealing.newAuthorKey()
        publishSealed("1.1.0", key = other)
        assertEquals(UpdateOutcome.Failed(PluginInstaller.AUTHOR_KEY_CHANGED_MESSAGE), installer.checkUpdate("demo"))
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        // "Reinstalar" from the list goes through the same preview: refused too.
        assertEquals(PluginInstaller.AUTHOR_KEY_CHANGED_MESSAGE, failure { installer.preview("o/r") })
        store.remove("demo", "Demo")
        val record = installer.install(installer.preview("o/r"))
        assertEquals(SealedCode.hex(other.publicRaw), record.authorKey)
    }

    @Test fun `an update that drops the sealed entry is refused`() = runBlocking<Unit> {
        publishSealed("1.0.0"); installer.install(installer.preview("o/r"))
        publishPlain("1.1.0")
        assertEquals(UpdateOutcome.Failed(PluginInstaller.SEALED_CODE_DROPPED_MESSAGE), installer.checkUpdate("demo"))
        assertEquals("1.0.0", store.get("demo")!!.record.version)
    }

    @Test fun `a plain plugin that becomes sealed waits for consent, then pins the key`() = runBlocking<Unit> {
        publishPlain("1.0.0"); installer.install(installer.preview("o/r"))
        assertNull(store.get("demo")!!.record.authorKey)
        publishSealed("1.1.0")
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertTrue(outcome.preview.newSealedCode)
        val lines = PluginConsent.extraLines(outcome.preview)
        assertTrue(lines.single { it.text == PluginConsent.SEALED_CODE_LINE }.isNew)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        installer.install(outcome.preview)
        assertEquals(SealedCode.hex(author.publicRaw), store.get("demo")!!.record.authorKey)
        assertEquals(script("1.1.0"), loadEntryScript(store, installed(), agreement, TestSealing.TEST_PUBLIC))
    }

    @Test fun `a runtime of a sealed plugin recorded at an explicit ref does not open`() = runBlocking<Unit> {
        publishSealed(); installer.install(installer.preview("o/r"))
        store.updateRecord("demo") { it.copy(address = "o/r@main") }
        val e = assertThrows(PluginScriptException::class.java) { runBlocking { loadEntryScript(store, installed(), agreement, TestSealing.TEST_PUBLIC) } }
        assertEquals(PluginInstaller.NON_HEAD_CODE_MESSAGE, e.message)
    }

    @Test fun `telemetry tags a sealed plugin and never carries its source`() = runBlocking<Unit> {
        publishSealed()
        val events = mutableListOf<PluginTelemetry.Event>()
        val previous = PluginTelemetry.current
        PluginTelemetry.current = PluginTelemetry(facts = { null }, sink = PluginFailureSink { events += it })
        try {
            val failing = PluginInstaller(
                store, fetcher, probe = { _, _ -> throw PluginScriptException("SyntaxError: unexpected token at plugin.js:2") },
                sealAgreement = agreement, sealRecipient = TestSealing.TEST_PUBLIC,
            )
            assertThrows(InstallException::class.java) { runBlocking { failing.install(failing.preview("o/r")) } }
        } finally {
            PluginTelemetry.current = previous
        }
        val e = events.single()
        assertEquals("true", e.tags["plugin_sealed"])
        val all = (e.extras.values + e.tags.values + e.message + e.fingerprint).joinToString(" ")
        assertFalse(all, all.contains(secretMarker))
    }

    private fun assertNoPlaintextOnDisk() {
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("${f.path} holds plaintext", String(f.readBytes(), Charsets.ISO_8859_1).contains(secretMarker))
        }
    }

}
