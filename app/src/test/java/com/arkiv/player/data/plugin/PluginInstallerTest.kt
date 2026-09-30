package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import java.io.IOException

class PluginInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val files = mutableMapOf<String, ByteArray>()
    private val fetcher = PluginFetcher { url, max ->
        val bytes = files[url] ?: throw FileNotFoundException(url)
        if (bytes.size > max) throw IOException("archivo demasiado grande")
        bytes
    }
    private var exports: (String) -> Set<String> = { setOf("search", "resolve") }
    private var now = 1_000L
    private lateinit var store: PluginStore
    private lateinit var installer: PluginInstaller
    private val base = "https://raw.githubusercontent.com/o/r/HEAD/"

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        installer = PluginInstaller(store, fetcher, probe = { exports(it) }, clock = { now })
    }

    private fun publish(
        version: String = "1.0.0",
        hosts: List<Any> = listOf("example.com"),
        capabilities: List<String> = listOf("search", "resolve"),
        script: String = "export async function search(){}\nexport async function resolve(){}",
        prefix: String = base,
        api: Int = 1,
        liveStreamHosts: String? = null,
        secrets: Map<String, String> = emptyMap(),
    ) {
        files[prefix + "kino-plugin.json"] = JSONObject()
            .put("id", "demo").put("name", "Demo").put("version", version).put("apiVersion", api)
            .put("entry", "plugin.js").put("hosts", JSONArray(hosts))
            .put("capabilities", JSONArray(capabilities))
            .apply { if (liveStreamHosts != null) put("liveStreamHosts", liveStreamHosts) }
            .apply { if (secrets.isNotEmpty()) put("secrets", JSONObject(secrets)) }
            .toString().toByteArray()
        files[prefix + "plugin.js"] = script.toByteArray()
    }

    private fun insecureHost(host: String) = JSONObject().put("host", host).put("insecureHttp", true)

    private fun installFresh() = runBlocking { installer.install(installer.preview("o/r")) }

    @Test fun `install writes the files and a verified record`() = runBlocking {
        publish()
        val preview = installer.preview("o/r")
        assertFalse(preview.isUpdate)
        assertEquals(listOf("example.com"), preview.newHosts)
        val record = installer.install(preview)
        val stored = store.get("demo")!!
        assertEquals("o/r", stored.record.address)
        assertEquals(sha256Hex(files[base + "plugin.js"]!!), record.sha256)
        assertTrue(store.readVerifiedScript("demo").contains("resolve"))
        assertTrue(File(tmp.root, "plugins").list()!!.none { it.startsWith(".staging") || it.startsWith(".old") })
    }

    @Test fun `bad address, missing manifest and invalid manifest are explained in Spanish`() {
        val bad = assertThrows(InstallException::class.java) { runBlocking { installer.preview("nope") } }
        assertTrue(bad.message!!.contains("usuario/repositorio"))
        val missing = assertThrows(InstallException::class.java) { runBlocking { installer.preview("o/r") } }
        assertTrue(missing.message!!.contains("kino-plugin.json"))
        publish()
        files[base + "kino-plugin.json"] = "{\"id\":\"X\"}".toByteArray()
        val invalid = assertThrows(InstallException::class.java) { runBlocking { installer.preview("o/r") } }
        assertTrue(invalid.message!!.contains("\"id\""))
    }

    @Test fun `a new version missing a declared export is refused and the installed one survives`() {
        publish("1.0.0"); installFresh()
        publish("1.1.0", script = "export async function search(){}")
        exports = { setOf("search") }
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.install(installer.preview("o/r")) } }
        assertEquals("El plugin no carga: le falta resolve", e.message)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        assertTrue(store.readVerifiedScript("demo").contains("resolve"))
    }

    @Test fun `a script that does not load is refused with the engine message`() {
        publish()
        exports = { throw PluginScriptException("SyntaxError: unexpected token") }
        val e = assertThrows(InstallException::class.java) { installFresh() }
        assertEquals("El plugin no carga: SyntaxError: unexpected token", e.message)
        assertNull(store.get("demo"))
    }

    @Test fun `the same id from another address is refused`() {
        publish(); installFresh()
        publish(prefix = "https://raw.githubusercontent.com/other/r/HEAD/")
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.preview("other/r") } }
        assertTrue(e.message!!.startsWith("Ya hay un plugin con ese id"))
    }

    @Test fun `an update with the same hosts is applied silently and keeps the enabled flag`() = runBlocking {
        publish("1.0.0"); installFresh()
        store.writeRecord("demo", store.get("demo")!!.record.copy(enabled = false))
        publish("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        assertEquals("1.1.0", store.get("demo")!!.record.version)
        assertFalse(store.get("demo")!!.record.enabled)
    }

    @Test fun `an update with new hosts waits for approval`() = runBlocking {
        publish("1.0.0"); installFresh()
        publish("2.0.0", hosts = listOf("example.com", "cdn.example.net"))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertEquals(listOf("cdn.example.net"), outcome.preview.newHosts)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        assertEquals("2.0.0", store.get("demo")!!.record.pendingVersion)
        installer.install(outcome.preview) // the person approved on the consent sheet
        val after = store.get("demo")!!.record
        assertEquals("2.0.0", after.version)
        assertNull(after.pendingVersion)
        assertEquals(listOf("example.com", "cdn.example.net"), after.hosts)
    }

    // Spec §9: a reactive "yes" lasts until uninstall and a "no" until the person forgets it --
    // neither is undone by the plugin updating itself.
    @Test fun `an update keeps reactively approved hosts and remembered rejections, and still asks about a new declared host`() = runBlocking {
        publish("1.0.0"); installFresh()
        // What PluginRegistry.addApprovedHost / rejectHost write, mid-kino.fetch.
        store.updateRecord("demo") { it.copy(hosts = it.hosts + "new-cdn.example", rejectedHosts = listOf("ads.example.net")) }

        publish("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        store.get("demo")!!.record.let {
            assertEquals(listOf("example.com", "new-cdn.example"), it.hosts)
            assertEquals(listOf("ads.example.net"), it.rejectedHosts)
        }

        // A host the manifest itself adds still waits for the consent sheet.
        publish("2.0.0", hosts = listOf("example.com", "cdn.example.net"))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertEquals(listOf("cdn.example.net"), outcome.preview.newHosts)
        installer.install(outcome.preview)
        store.get("demo")!!.record.let {
            assertEquals(listOf("example.com", "cdn.example.net", "new-cdn.example"), it.hosts)
            assertEquals(listOf("ads.example.net"), it.rejectedHosts)
        }

        // The manifest now declares the reactively approved host (nothing to ask: the person already
        // said yes) and drops one of its own: that one goes, the approved one is kept once.
        publish("3.0.0", hosts = listOf("example.com", "new-cdn.example"))
        assertEquals(UpdateOutcome.Applied("3.0.0"), installer.checkUpdate("demo"))
        assertEquals(listOf("example.com", "new-cdn.example"), store.get("demo")!!.record.hosts)
    }

    // The broad video permission is the person's, like a reactive "yes": an update or a reinstall
    // over the installed plugin keeps it; only uninstall drops it (with the record).
    @Test fun `an update and a reinstall keep the broad video permission`() = runBlocking {
        publish("1.0.0"); installFresh()
        store.updateRecord("demo") { it.copy(anyVideoHost = true) }
        publish("1.1.0")
        assertEquals(UpdateOutcome.Applied("1.1.0"), installer.checkUpdate("demo"))
        assertTrue(store.get("demo")!!.record.anyVideoHost)
        publish("2.0.0", hosts = listOf("example.com", "cdn.example.net"))
        installer.install((installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval).preview)
        assertTrue(store.get("demo")!!.record.anyVideoHost)
        installer.install(installer.preview("o/r"))
        assertTrue(store.get("demo")!!.record.anyVideoHost)
        store.remove("demo", "Demo")
        installer.install(installer.preview("o/r"))
        assertFalse(store.get("demo")!!.record.anyVideoHost)
    }

    @Test fun `a reactively approved host the update's own hosts push past the cap is logged, not dropped silently`() = runBlocking {
        val logs = mutableListOf<String>()
        val logging = PluginInstaller(store, fetcher, probe = { exports(it) }, clock = { now }, log = { logs += it })
        val nineteen = (1..19).map { "h$it.example.com" }
        publish("1.0.0", hosts = nineteen)
        logging.install(logging.preview("o/r"))
        // The person said yes in the moment: the record now holds the 20th host.
        store.updateRecord("demo") { it.copy(hosts = it.hosts + "new-cdn.example") }

        // An update that stops needing nothing but takes the last slot for a host of its own.
        publish("2.0.0", hosts = nineteen + "h20.example.com")
        val outcome = logging.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        logging.install(outcome.preview)

        // Behavior unchanged: the declared hosts win the cap, and the reactive one is asked about again later.
        assertEquals(nineteen + "h20.example.com", store.get("demo")!!.record.hosts)
        assertEquals(logs.toString(), 1, logs.size)
        assertTrue(logs.single(), "[demo]" in logs.single() && "new-cdn.example" in logs.single() && "2.0.0" in logs.single() && "20" in logs.single())
    }

    @Test fun `carrying reactive hosts over logs nothing when none is dropped by the cap`() = runBlocking {
        val logs = mutableListOf<String>()
        val logging = PluginInstaller(store, fetcher, probe = { exports(it) }, clock = { now }, log = { logs += it })
        publish("1.0.0"); logging.install(logging.preview("o/r"))
        store.updateRecord("demo") { it.copy(hosts = it.hosts + "new-cdn.example" + "old-cdn.example") }
        // One reactive host kept, one now declared by the manifest itself: neither is a cap drop.
        publish("1.1.0", hosts = listOf("example.com", "old-cdn.example"))
        assertEquals(UpdateOutcome.Applied("1.1.0"), logging.checkUpdate("demo"))
        assertEquals(listOf("example.com", "old-cdn.example", "new-cdn.example"), store.get("demo")!!.record.hosts)
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun `carried-over reactive hosts respect the host cap and a covering wildcard`() {
        fun stored(manifestHosts: List<String>, recordHosts: List<String>) = StoredPlugin(
            PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", manifestHosts, setOf("search"), null, null),
            "{}", InstalledRecord("o/r", "1.0.0", "x", recordHosts, 0L), tmp.root,
        )
        val nineteen = (1..19).map { "h$it.example.com" }
        var dropped: List<String>? = null
        val carried = PluginInstaller.hostsCarriedOver(nineteen, stored(nineteen, nineteen + listOf("a.example.org", "b.example.org"))) { dropped = it }
        assertEquals(nineteen + "a.example.org", carried)
        assertEquals(ManifestParser.MAX_HOSTS, carried.size)
        assertEquals(listOf("b.example.org"), dropped)
        // Already covered by the new version's own `*.example.org`: not kept a second time, and not a cap drop
        // (neither is x.example.com, which the OLD manifest declared and the new one no longer does).
        dropped = null
        assertEquals(
            listOf("*.example.org", "c.example.net"),
            PluginInstaller.hostsCarriedOver(listOf("*.example.org"), stored(listOf("x.example.com"), listOf("x.example.com", "a.example.org", "c.example.net"))) { dropped = it },
        )
        assertNull(dropped)
        // A first install carries nothing over.
        assertEquals(listOf("example.com"), PluginInstaller.hostsCarriedOver(listOf("example.com"), null))
    }

    @Test fun `same or older version is up to date`() = runBlocking {
        publish("1.0.0"); installFresh()
        assertEquals(UpdateOutcome.UpToDate, installer.checkUpdate("demo"))
        publish("0.9.0")
        assertEquals(UpdateOutcome.UpToDate, installer.checkUpdate("demo"))
    }

    @Test fun `an update needing a newer Kino is reported, not applied`() = runBlocking {
        publish("1.0.0"); installFresh()
        publish("2.0.0", api = 5)
        val o = installer.checkUpdate("demo") as UpdateOutcome.Failed
        assertEquals("Este plugin necesita una versión más nueva de Kino", o.message)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
    }

    @Test fun `a channels plugin must export liveCategories and liveChannels but never guide`() = runBlocking {
        exports = { setOf("home", "resolve") }
        publish(api = 3, capabilities = listOf("home", "resolve", "channels"))
        val e = runCatching { installer.install(installer.preview("o/r")) }.exceptionOrNull()
        assertEquals("El plugin no carga: le falta liveCategories, liveChannels", e?.message)
        exports = { setOf("home", "resolve", "liveCategories", "liveChannels") }
        val record = installer.install(installer.preview("o/r"))
        assertEquals(listOf("home", "resolve", "channels"), record.capabilities)
    }

    @Test fun `the installed record remembers which functions the entry file exports`() = runBlocking {
        exports = { setOf("home", "resolve", "liveCategories", "liveChannels", "guide") }
        publish(api = 3, capabilities = listOf("home", "resolve", "channels"))
        val record = installer.install(installer.preview("o/r"))
        assertEquals(listOf("guide", "home", "liveCategories", "liveChannels", "resolve"), record.exports)
        assertEquals(record, InstalledRecord.fromJson(record.toJson()))
        assertEquals(emptyList<String>(), InstalledRecord.fromJson("""{"address":"o/r","version":"1.0.0","sha256":"x","hosts":[],"installedAt":1}""")!!.exports)
    }

    @Test fun `an update that adds channels waits for approval`() = runBlocking {
        exports = { setOf("home", "resolve", "liveCategories", "liveChannels") }
        publish("1.0.0", api = 3, capabilities = listOf("home", "resolve")); installFresh()
        publish("1.1.0", api = 3, capabilities = listOf("home", "resolve", "channels"))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertEquals(listOf("channels"), outcome.preview.newCapabilities)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        assertEquals(listOf("channels"), store.get("demo")!!.record.pendingCapabilities)
        installer.install(outcome.preview)
        assertEquals(listOf("home", "resolve", "channels"), store.get("demo")!!.record.capabilities)
    }

    @Test fun `an update that adds download or drm waits for approval and does not require their export`() = runBlocking {
        publish("1.0.0", api = 2, capabilities = listOf("search", "resolve")); installFresh()
        publish("2.0.0", api = 2, capabilities = listOf("search", "resolve", "download", "drm"))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertEquals(listOf("download", "drm"), outcome.preview.newCapabilities)
        assertEquals("1.0.0", store.get("demo")!!.record.version)
        assertEquals("2.0.0", store.get("demo")!!.record.pendingVersion)
        assertEquals(listOf("download", "drm"), store.get("demo")!!.record.pendingCapabilities)
        // exports is still just search/resolve: download/drm are declarative, never exported functions.
        installer.install(outcome.preview)
        val after = store.get("demo")!!.record
        assertEquals("2.0.0", after.version)
        assertNull(after.pendingVersion)
        assertEquals(emptyList<String>(), after.pendingCapabilities)
        assertEquals(listOf("search", "resolve", "download", "drm"), after.capabilities)
    }

    @Test fun `an update that marks an already-approved host insecureHttp waits for approval`() = runBlocking {
        publish("1.0.0", api = 2, hosts = listOf("example.com")); installFresh()
        publish("2.0.0", api = 2, hosts = listOf(insecureHost("example.com")))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertEquals(emptyList<String>(), outcome.preview.newHosts) // same host string: already approved
        assertEquals(listOf("example.com"), outcome.preview.newInsecureHosts)
        assertEquals(listOf("example.com"), store.get("demo")!!.record.pendingInsecureHosts)
        installer.install(outcome.preview)
        val after = store.get("demo")!!.record
        assertEquals(listOf("example.com"), after.insecureHosts)
        assertEquals(emptyList<String>(), after.pendingInsecureHosts)
    }

    @Test fun `a change made while checkUpdate is still fetching is not lost (no stale write-back)`() = runBlocking {
        publish("1.0.0"); installFresh()
        // A fetcher that simulates the person disabling the plugin WHILE the manifest fetch that
        // this checkUpdate call kicked off is still in flight -- checkUpdate must not silently
        // revert that disable when it later writes back its own result (lastUpdateCheckAt here,
        // since the manifest is unchanged so the outcome is UpToDate).
        val racyFetcher = PluginFetcher { url, max ->
            val bytes = fetcher.fetch(url, max)
            if (url == base + "kino-plugin.json") {
                store.writeRecord("demo", store.get("demo")!!.record.copy(enabled = false))
            }
            bytes
        }
        val racyInstaller = PluginInstaller(store, racyFetcher, probe = { exports(it) }, clock = { now })
        assertEquals(UpdateOutcome.UpToDate, racyInstaller.checkUpdate("demo"))
        assertFalse(store.get("demo")!!.record.enabled)
    }

    @Test fun `an uninstall while a silent update is fetching wins - the plugin does not come back`() = runBlocking {
        publish("1.0.0"); installFresh()
        publish("1.1.0")
        // The person taps Desinstalar while UpdateWorker's update is downloading the new script.
        val racyFetcher = PluginFetcher { url, max ->
            val bytes = fetcher.fetch(url, max)
            if (url == base + "plugin.js") store.remove("demo", "Demo")
            bytes
        }
        val racyInstaller = PluginInstaller(store, racyFetcher, probe = { exports(it) }, clock = { now })
        assertEquals(UpdateOutcome.Failed("El plugin se desinstaló mientras se actualizaba"), racyInstaller.checkUpdate("demo"))
        assertNull(store.get("demo"))
        assertEquals("Demo", store.removedName("demo"))
        assertTrue(File(tmp.root, "plugins").list()!!.none { it.startsWith(".staging") || it.startsWith(".old") || it == "demo" })
    }

    @Test fun `approving an update after the plugin was uninstalled installs nothing`() {
        publish("1.0.0"); installFresh()
        publish("2.0.0", hosts = listOf("example.com", "cdn.example.net"))
        val outcome = runBlocking { installer.checkUpdate("demo") } as UpdateOutcome.NeedsApproval
        store.remove("demo", "Demo")
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.install(outcome.preview) } }
        assertEquals("El plugin se desinstaló mientras se actualizaba", e.message)
        assertNull(store.get("demo"))
        assertTrue(File(tmp.root, "plugins").list()!!.none { it.startsWith(".staging") || it.startsWith(".old") || it == "demo" })
    }

    @Test fun `a tampered script is detected`() {
        publish(); installFresh()
        File(store.get("demo")!!.dir, "plugin.js").writeText("evil")
        assertThrows(PluginDamagedException::class.java) { store.readVerifiedScript("demo") }
    }

    // `checkDueUpdates` moved to PluginUpdateCoordinator (Task 6), which routes each plugin by
    // origin before applying the same "at most once per maxAgeMs" rule this used to test here --
    // see PluginUpdateCoordinatorTest's "checkDueUpdates checks each plugin, of either origin, at
    // most once per maxAgeMs".

    @Test fun `an update that adds liveStreamHosts any waits for approval, and installing records it`() = runBlocking {
        exports = { setOf("home", "resolve", "liveCategories", "liveChannels") }
        publish("1.0.0", api = 3, capabilities = listOf("home", "resolve", "channels")); installFresh()
        assertEquals(false, store.get("demo")!!.record.liveStreamHostsAny)
        publish("1.1.0", api = 3, capabilities = listOf("home", "resolve", "channels"), liveStreamHosts = "any")
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertTrue(outcome.preview.newLiveStreamHostsAny)
        assertTrue(store.get("demo")!!.record.pendingLiveStreamHostsAny)
        installer.install(outcome.preview)
        assertTrue(store.get("demo")!!.record.liveStreamHostsAny)
        assertEquals(false, store.get("demo")!!.record.pendingLiveStreamHostsAny)
        val record = store.get("demo")!!.record
        assertEquals(record, InstalledRecord.fromJson(record.toJson()))
        // Already approved: a later update keeping it applies without asking again.
        publish("1.2.0", api = 3, capabilities = listOf("home", "resolve", "channels"), liveStreamHosts = "any")
        assertEquals(UpdateOutcome.Applied("1.2.0"), installer.checkUpdate("demo"))
        assertTrue(store.get("demo")!!.record.liveStreamHostsAny)
    }

    @Test fun `a first install with liveStreamHosts any flags it as new on the preview`() = runBlocking {
        exports = { setOf("home", "resolve", "liveCategories", "liveChannels") }
        publish(api = 3, capabilities = listOf("home", "resolve", "channels"), liveStreamHosts = "any")
        val preview = installer.preview("o/r")
        assertTrue(preview.newLiveStreamHostsAny)
        assertTrue(installer.install(preview).liveStreamHostsAny)
    }

    private fun installerWithSeal() = PluginInstaller(
        store, fetcher, probe = { exports(it) }, clock = { now },
        sealAgreement = TestSealing.agreement, sealRecipient = TestSealing.TEST_PUBLIC,
    )

    @Test fun `seals for this repo install`() = runBlocking {
        installer = installerWithSeal()
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("o", "r")), "apiKey")
        publish(api = 4, secrets = mapOf("apiKey" to seal))
        val preview = installer.preview("o/r")
        assertTrue(preview.newSealedSecrets)
        assertTrue(installer.install(preview).sealedSecrets)
    }

    @Test fun `seals bound to another repo refuse the install`() {
        installer = installerWithSeal()
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("owner", "repo")), "apiKey")
        publish(api = 4, secrets = mapOf("apiKey" to seal), prefix = "https://raw.githubusercontent.com/fork/repo/HEAD/")
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.preview("fork/repo") } }
        assertEquals("Los datos sellados de este plugin no son para este repositorio o están dañados", e.message)
    }

    // GitHub can serve a fork's (or a pull request's) commit -- even one that isn't obviously a SHA,
    // such as a short prefix or a git-describe ref -- through the parent repo's own raw URL. Seals
    // don't depend on what the ref looks like or is named: only the ref-less address (HEAD) opens
    // them, any explicit @ref refuses, whatever shape or name it has.
    @Test fun `seals open only at HEAD -- any explicit ref refuses, whatever it looks like`() = runBlocking {
        installer = installerWithSeal()
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("o", "r")), "apiKey")
        for (ref in listOf("dev", "main", "v1.2.0", "abc123", "deadbeef-fix", "0123456789abcdef0123456789ABCDEF01234567")) {
            publish(api = 4, secrets = mapOf("apiKey" to seal), prefix = "https://raw.githubusercontent.com/o/r/$ref/")
            val e = assertThrows(InstallException::class.java) { runBlocking { installer.preview("o/r@$ref") } }
            assertEquals(ref, PluginInstaller.NON_HEAD_SEALS_MESSAGE, e.message)
        }
        assertEquals(
            "Los datos sellados solo funcionan si instalas el plugin desde su rama principal, sin @rama",
            PluginInstaller.NON_HEAD_SEALS_MESSAGE,
        )
        // No explicit ref (HEAD) is the only address that opens seals.
        publish(api = 4, secrets = mapOf("apiKey" to seal))
        assertTrue(installer.preview("o/r").newSealedSecrets)
        // An explicit ref without secrets installs as before -- the rule only guards manifests that declare them.
        publish(prefix = "https://raw.githubusercontent.com/o/r/dev/")
        assertFalse(installer.preview("o/r@dev").newSealedSecrets)
    }

    @Test fun `an update that brings seals to a plugin installed at a non-HEAD ref fails`() = runBlocking {
        installer = installerWithSeal()
        val prefix = "https://raw.githubusercontent.com/o/r/dev/"
        publish("1.0.0", prefix = prefix)
        installer.install(installer.preview("o/r@dev"))
        publish("1.1.0", api = 4, secrets = mapOf("apiKey" to TestSealing.seal("shh", "o/r", "apiKey")), prefix = prefix)
        assertEquals(UpdateOutcome.Failed(PluginInstaller.NON_HEAD_SEALS_MESSAGE), installer.checkUpdate("demo"))
        assertEquals("1.0.0", store.get("demo")!!.record.version)
    }

    @Test fun `a runtime of a plugin recorded at an explicit ref gets no secrets`() = runBlocking {
        installer = installerWithSeal()
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("o", "r")), "apiKey")
        publish(api = 4, secrets = mapOf("apiKey" to seal))
        installer.install(installer.preview("o/r"))
        val plugin = PluginRegistry(store).apply { reload() }.find("demo")!!
        assertEquals("shh", pluginSecretsFor(plugin, TestSealing.agreement, TestSealing.TEST_PUBLIC)!!.let { it.substitute(it.marker("apiKey")!!) })
        for (ref in listOf("dev", "main", "0123abc", "0123456789abcdef0123456789abcdef01234567")) {
            val atRef = plugin.copy(record = plugin.record.copy(address = "o/r@$ref"))
            assertNull(ref, pluginSecretsFor(atRef, TestSealing.agreement, TestSealing.TEST_PUBLIC))
        }
    }

    // The runtime side of the same binding: the secrets AppGraph opens a runtime with come from the
    // INSTALLED record's address, and only the ordinary host gets them.
    @Test fun `an installed plugin's runtime opens its seals with its recorded address`() = runBlocking {
        installer = installerWithSeal()
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("o", "r")), "apiKey")
        publish(api = 4, hosts = listOf("api.example.com", "*.cdn.example.com"), secrets = mapOf("apiKey" to seal))
        installer.install(installer.preview("o/r"))
        val plugin = PluginRegistry(store).apply { reload() }.find("demo")!!
        val secrets = pluginSecretsFor(plugin, TestSealing.agreement, TestSealing.TEST_PUBLIC)!!
        assertEquals(listOf("api.example.com", "*.cdn.example.com"), secrets.sealedHosts)
        assertEquals("shh", secrets.substitute(secrets.marker("apiKey")!!))
        assertEquals(secrets.marker("apiKey"), hostFor(plugin, secrets).secret("apiKey"))
    }

    @Test fun `the privileged Xuper host never gets secrets`() = runBlocking {
        installer = installerWithSeal()
        val (owner, repo) = XuperPrivilege.SOURCE_REPO.split('/')
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress(owner, repo)), "apiKey")
        publish(api = 4, secrets = mapOf("apiKey" to seal), prefix = "https://raw.githubusercontent.com/${XuperPrivilege.SOURCE_REPO}/HEAD/")
        installer.install(installer.preview(XuperPrivilege.SOURCE_REPO))
        val plugin = PluginRegistry(store).apply { reload() }.find("demo")!!
        val host = hostFor(plugin, pluginSecretsFor(plugin, TestSealing.agreement, TestSealing.TEST_PUBLIC)!!)
        assertTrue(host is PrivilegedXuperHost)
        assertNull(host.secret("apiKey"))
    }

    private fun hostFor(plugin: InstalledPlugin, secrets: PluginSecrets?): PluginHost = pluginHostFor(
        plugin, PluginHttp(okhttp3.OkHttpClient(), plugin.id, plugin.hosts, "9.9.9"), PluginStorage(File(tmp.root, "s.json")),
        PluginConfig.EMPTY, null, lazy<com.arkiv.player.data.magis.MagisPluginBridge> { error("no Magis objects in this test") }, secrets,
    )

    @Test fun `a plugin without seals opens its runtime without secrets`() {
        publish()
        installFresh()
        assertNull(pluginSecretsFor(PluginRegistry(store).apply { reload() }.find("demo")!!, TestSealing.agreement))
    }

    @Test fun `no agreement means no install`() {
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("o", "r")), "apiKey")
        publish(api = 4, secrets = mapOf("apiKey" to seal))
        val e = assertThrows(InstallException::class.java) { runBlocking { installer.preview("o/r") } }
        assertEquals("Este Kino no puede abrir datos sellados", e.message)
    }

    @Test fun `an update that adds secrets needs approval again, and keeping them doesn't ask twice`() = runBlocking {
        installer = installerWithSeal()
        publish("1.0.0"); installFresh()
        assertEquals(false, store.get("demo")!!.record.sealedSecrets)
        val seal = TestSealing.seal("shh", SealedSecrets.bindingOf(PluginAddress("o", "r")), "apiKey")
        publish("1.1.0", api = 4, secrets = mapOf("apiKey" to seal))
        val outcome = installer.checkUpdate("demo") as UpdateOutcome.NeedsApproval
        assertTrue(outcome.preview.newSealedSecrets)
        assertTrue(store.get("demo")!!.record.pendingSealedSecrets)
        installer.install(outcome.preview)
        assertTrue(store.get("demo")!!.record.sealedSecrets)
        assertEquals(false, store.get("demo")!!.record.pendingSealedSecrets)
        val record = store.get("demo")!!.record
        assertEquals(record, InstalledRecord.fromJson(record.toJson()))
        // Already approved: a later update keeping the same secret applies without asking again.
        publish("1.2.0", api = 4, secrets = mapOf("apiKey" to seal))
        assertEquals(UpdateOutcome.Applied("1.2.0"), installer.checkUpdate("demo"))
        assertTrue(store.get("demo")!!.record.sealedSecrets)
    }

    // manifestJson must be the SAME json the manifest was parsed from (not a stand-in like "{}"):
    // PluginStore.read() re-parses whatever ends up on disk and drops the record if that fails.
    private fun nuvioManifest(icon: String? = null): Pair<PluginManifest, String> {
        val json = JSONObject()
            .put("id", "nuvio-x-abc123").put("name", "X").put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("x.example")))
            .put("capabilities", JSONArray(listOf("search", "resolve")))
            .apply { if (icon != null) put("icon", icon) }
            .toString()
        return (ManifestParser.parse(json) as ManifestResult.Valid).manifest to json
    }

    @Test fun `commit writes a record with the given script, bypassing the fetcher`() = runBlocking {
        val (manifest, json) = nuvioManifest()
        val preview = InstallPreview(PluginAddress.parse("owner/nuvio-repo")!!, manifest, json, isUpdate = false, newHosts = listOf("x.example"))
        val script = "export async function search(){return [];} export async function resolve(){return {url:\"https://x.example/a\"};}".toByteArray()
        val record = installer.commit(preview, script, icon = null)
        assertEquals("nuvio-x-abc123", store.get("nuvio-x-abc123")!!.manifest.id)
        assertEquals(sha256Hex(script), record.sha256)
    }

    @Test fun `commit records the Nuvio origin when the preview carries one, and leaves it null otherwise`() = runBlocking {
        val (manifest, json) = nuvioManifest()
        val script = "export async function search(){return [];} export async function resolve(){return {url:\"https://x.example/a\"};}".toByteArray()
        val origin = NuvioOrigin(repo = "nuvio-repo-owner/nuvio-repo", scraperId = "abc123", script = script)
        val preview = InstallPreview(
            PluginAddress.parse("owner/nuvio-repo")!!, manifest, json, isUpdate = false,
            newHosts = listOf("x.example"), nuvioOrigin = origin,
        )
        val record = installer.commit(preview, script, icon = null)
        assertEquals("nuvio-repo-owner/nuvio-repo", record.nuvioRepo)
        assertEquals("abc123", record.nuvioScraperId)

        // A normal, hand-written-repo install has neither field set.
        publish()
        val normal = installer.install(installer.preview("o/r"))
        assertNull(normal.nuvioRepo)
        assertNull(normal.nuvioScraperId)
    }

    @Test fun `install skips the script fetch and icon fetch when the preview carries a Nuvio origin`() = runBlocking {
        val (manifest, json) = nuvioManifest(icon = "icon.png")
        val script = "export async function search(){return [];} export async function resolve(){return {url:\"https://x.example/a\"};}".toByteArray()
        val origin = NuvioOrigin(repo = "nuvio-repo-owner/nuvio-repo", scraperId = "abc123", script = script)
        // No files registered in the fake fetcher for this address at all: fetching anything throws.
        val preview = InstallPreview(
            PluginAddress.parse("owner/nuvio-repo")!!, manifest, json, isUpdate = false,
            newHosts = listOf("x.example"), nuvioOrigin = origin,
        )
        val record = installer.install(preview)
        assertEquals(sha256Hex(script), record.sha256)
        assertNull(store.get("nuvio-x-abc123")!!.iconFile)
    }
}
