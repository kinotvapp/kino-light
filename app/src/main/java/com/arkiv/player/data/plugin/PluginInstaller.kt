package com.arkiv.player.data.plugin

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileNotFoundException
import java.io.IOException

class InstallException(message: String) : Exception(message)

/**
 * Set only on a preview a [NuvioPluginInstaller] (Task 5) builds: the already-converted script, so
 * [PluginInstaller.commit] can write it directly instead of fetching `manifest.entry` from
 * [InstallPreview.address] -- for a Nuvio-origin preview, that address serves the ORIGINAL scraper
 * file, not the wrapped one Kino actually runs (see `NuvioPluginConverter`'s note on `entry`).
 */
data class NuvioOrigin(val repo: String, val scraperId: String, val script: ByteArray)

/** What the consent sheet shows before anything is downloaded beyond the manifest. */
data class InstallPreview(
    val address: PluginAddress,
    val manifest: PluginManifest,
    val manifestJson: String,
    val isUpdate: Boolean,
    /** Hosts not yet approved for this plugin (all of them on a first install). */
    val newHosts: List<String>,
    /** Permissions not yet approved (all of them on a first install); each gets its own consent line. */
    val newPermissions: List<String> = emptyList(),
    /** `download`/`drm`/`channels` not yet approved (all of them on a first install); each needs its own consent. */
    val newCapabilities: List<String> = emptyList(),
    /** Hosts newly marked `insecureHttp` (all insecure ones on a first install), even when the host itself was already approved as https-only. */
    val newInsecureHosts: List<String> = emptyList(),
    /** The manifest asks for `liveStreamHosts: "any"` and the person has not approved it yet (always, on a first install that asks). */
    val newLiveStreamHostsAny: Boolean = false,
    /** The manifest declares `secrets` and the person has not approved that yet (always, on a first install that has any). */
    val newSealedSecrets: Boolean = false,
    /** The manifest asks for `streamHosts: "any"` and the person has not approved it yet. */
    val newStreamHostsAny: Boolean = false,
    /** A Nuvio-converted manifest asks for `fetchHosts: "any"` and the person has not approved it yet (never for a hand-written plugin). */
    val newFetchHostsAny: Boolean = false,
    /** Set for a Nuvio-origin install/update: the converted script to write, skipping the fetch. */
    val nuvioOrigin: NuvioOrigin? = null,
)

sealed interface UpdateOutcome {
    data object UpToDate : UpdateOutcome
    data class Applied(val version: String) : UpdateOutcome
    data class NeedsApproval(val preview: InstallPreview) : UpdateOutcome
    data class Failed(val message: String) : UpdateOutcome
}

/**
 * Reads a plugin file. Throws [FileNotFoundException] on 404, [PluginFetchStatusException] on any other
 * non-2xx, [PluginFileTooBigException] past `maxBytes`, [IOException] otherwise (offline, timeout).
 */
fun interface PluginFetcher {
    suspend fun fetch(url: String, maxBytes: Int): ByteArray
}

/** The server answered [code] (not 404, not 2xx). */
class PluginFetchStatusException(val code: Int) : IOException("GitHub respondió $code")

/**
 * What the person reads when a plugin's files can't be read from GitHub: the kind of failure in plain
 * Spanish, never the exception's own (English, technical) text.
 */
internal fun installReadFailureMessage(e: IOException): String = when (e) {
    is java.net.UnknownHostException, is java.net.ConnectException ->
        "No hay conexión a internet. Revisa tu conexión y vuelve a intentar."
    is java.net.SocketTimeoutException -> "GitHub tardó demasiado en responder. Intenta de nuevo en un rato."
    is PluginFetchStatusException -> "GitHub respondió con un error (${e.code}). Intenta de nuevo en un rato."
    else -> "No se pudo leer el plugin de GitHub. Intenta de nuevo en un rato."
}

/** The file is bigger than the caller allowed. */
class PluginFileTooBigException : IOException("archivo demasiado grande")

/** [PluginFetcher] for raw.githubusercontent.com only (destination #6 in `.claude/reglas.md`). */
class RawGithubFetcher(base: OkHttpClient) : PluginFetcher {
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Cancelling the caller cancels the call, so a host that never answers cannot outlive a timeout. */
    override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
        val u = url.toHttpUrl()
        require(u.scheme == "https" && u.host == "raw.githubusercontent.com") { "not a raw GitHub URL: $url" }
        val call = client.newCall(Request.Builder().url(u).build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

                override fun onResponse(call: Call, response: Response) {
                    cont.resumeWith(runCatching { response.use { read(it, u.encodedPath, maxBytes) } })
                }
            })
        }
    }

    private fun read(r: Response, path: String, maxBytes: Int): ByteArray {
        if (r.code == 404) throw FileNotFoundException(path)
        if (!r.isSuccessful) throw PluginFetchStatusException(r.code)
        val source = r.body?.source() ?: throw IOException("respuesta vacía")
        if (source.request(maxBytes + 1L)) throw PluginFileTooBigException()
        return source.buffer.readByteArray()
    }
}

/**
 * Host for the install-time load check: the plugin must load without touching the network.
 * `kino.secret(name)` answers a marker of the real runtime's shape (`__kinoSecret_<name>_<nonce>__`,
 * see [PluginSecrets]) for a name in [secretNames] -- the manifest's declared ones -- so a module-level
 * `const KEY = kino.secret("apiKey")` loads here exactly as it does in the real runtime. Never a value:
 * the probe opens no seal. An undeclared name answers null ("not declared"), as the runtime does.
 */
open class ProbeHost(secretNames: Set<String> = emptySet()) : PluginHost {
    private val nonce = PluginSecrets.randomNonce()
    private val markers: Map<String, String> = secretNames.associateWith { "__kinoSecret_${it}_${nonce}__" }

    override fun secret(name: String): String? = markers[name]
    override suspend fun fetch(requestJson: String): String = throw IOException("el plugin no puede usar la red al cargarse")
    override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
    override fun storageGet(key: String): String? = null
    override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
    override fun storageRemove(key: String) = Unit
    override fun log(level: String, message: String) = Unit
}

/** [ProbeHost] for a plugin that declares no secrets. */
object ProbePluginHost : ProbeHost()

/**
 * Install and update flow from the spec: fetch + validate the manifest, (consent happens in the
 * UI between [preview] and [install]), fetch script and icon, load the script in a throwaway
 * sandbox ([probe]) and require every declared capability to be an exported function, then commit
 * atomically. A failure at any step leaves the installed version untouched.
 */
class PluginInstaller(
    internal val store: PluginStore,
    private val fetcher: PluginFetcher,
    /** Loads a script in a throwaway sandbox ([ProbeHost] answering the manifest's secret names) and returns its exports. */
    private val probe: suspend (script: String, secretNames: Set<String>) -> Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** [PluginSettings.PERMISSIONS]; tests pass their own (SDK v1 has none, so nothing else can reach this). */
    private val knownPermissions: Set<String> = PluginSettings.PERMISSIONS,
    /** Diagnostics only (logcat, the same "KinoPlugin" tag as [PluginHttp]'s); tests capture it. */
    private val log: (String) -> Unit = { android.util.Log.w("KinoPlugin", it) },
    /** The X25519 step of opening a manifest's sealed secrets at install/update; null means this build can't (see [previewFor]). */
    private val sealAgreement: X25519Agreement? = null,
    /** The recipient public key seals are opened against; production always uses [SealedSecrets.KINO_PUBLIC_KEY_V1], tests swap in their own. */
    internal val sealRecipient: ByteArray = SealedSecrets.KINO_PUBLIC_KEY_V1,
) {
    suspend fun preview(input: String): InstallPreview {
        val address = PluginAddress.parse(input)
            ?: throw InstallException("Escribe usuario/repositorio, por ejemplo kinotvapp/kino-plugin-archive")
        // A pasted GitHub page or file URL (`…/tree/<branch>/<folder>`, `…/blob/<branch>/…/kino-plugin.json`,
        // `raw.githubusercontent.com/…/<branch>/…`) names its branch only because the page does: that
        // is not a pin the person chose. See [previewFor]'s headTwin.
        val headTwin = address.copy(ref = PluginAddress.HEAD).takeIf { address.ref != PluginAddress.HEAD && PluginAddress.isBrowsedRefUrl(input) }
        return previewFor(address, headTwin)
    }

    suspend fun install(preview: InstallPreview): InstalledRecord {
        val m = preview.manifest
        val script = preview.nuvioOrigin?.script ?: try {
            fetcher.fetch(preview.address.rawUrl(m.entry), MAX_SCRIPT_BYTES)
        } catch (e: FileNotFoundException) {
            reportInstall(preview, "download", mapOf("error" to "not_found"))
            throw InstallException("No encontré ${m.entry} en ${preview.address.canonical}")
        } catch (e: IOException) {
            reportInstall(preview, "download", mapOf("error" to PluginTelemetry.errorName(e)))
            throw InstallException("No se pudo descargar el plugin: ${e.message}")
        }
        // A Nuvio-origin preview has no icon of its own to fetch (yet): NuvioPluginConverter
        // doesn't produce one, and preview.address serves the original scraper repo, not one
        // that carries a matching icon file for m.icon's path.
        val icon = if (preview.nuvioOrigin != null) null
            else m.icon?.let { runCatching { fetcher.fetch(preview.address.rawUrl(it), MAX_ICON_BYTES) }.getOrNull() }
        return commit(preview, script, icon)
    }

    /**
     * The tail [install] and [NuvioPluginInstaller]'s own install (Task 5) share once each has
     * [script] in hand: probe it, check its exports match the declared capabilities, hash it,
     * stage it, commit atomically -- carrying forward reactively-approved hosts, remembered
     * rejections, and `enabled` from whatever was previously installed (see [hostsCarriedOver]).
     * Never fetches anything itself.
     */
    internal suspend fun commit(preview: InstallPreview, script: ByteArray, icon: ByteArray?): InstalledRecord {
        val m = preview.manifest
        val exports = try {
            probe(script.toString(Charsets.UTF_8), m.secrets.keys)
        } catch (e: PluginException) {
            reportInstall(preview, "probe", raw = e.message)
            throw InstallException("El plugin no carga: ${e.message}")
        }
        // Declarative capabilities (download/drm) export nothing; `channels` exports
        // liveCategories + liveChannels (guide is optional): see ManifestParser.requiredExports.
        val missing = ManifestParser.requiredExports(m.capabilities) - exports
        if (missing.isNotEmpty()) reportInstall(preview, "exports", mapOf("missing" to missing.sorted().joinToString(",")))
        if (missing.isNotEmpty()) throw InstallException("El plugin no carga: le falta ${missing.sorted().joinToString(", ")}")
        val sha = sha256Hex(script)
        val installedAt = clock()
        // Set by the build that really commits (the one finishInstall runs with the fresh previous
        // install), and logged only once that commit went through.
        fun buildRecord(previous: StoredPlugin?) = InstalledRecord(
            address = preview.address.canonical, version = m.version, sha256 = sha,
            hosts = hostsCarriedOver(m.hosts, previous), installedAt = installedAt,
            enabled = previous?.record?.enabled ?: true, lastUpdateCheckAt = installedAt,
            permissions = m.permissions, capabilities = m.capabilities.toList(), insecureHosts = m.insecureHosts.toList(),
            exports = exports.sorted(), liveStreamHostsAny = m.liveStreamHostsAny, streamHostsAny = m.streamHostsAny,
            // Honoured only for a Nuvio-converted install: a hand-written manifest's copy is ignored.
            fetchHostsAny = m.fetchHostsAny && preview.nuvioOrigin != null,
            sealedSecrets = m.secrets.isNotEmpty(),
            // A "no" is remembered until the person forgets it (Ajustes ▸ Plugins), not until the next version.
            rejectedHosts = previous?.record?.rejectedHosts.orEmpty(),
            // The person's broad video permission, like a reactive "yes": until they revoke it or uninstall.
            anyVideoHost = previous?.record?.anyVideoHost == true,
            nuvioRepo = preview.nuvioOrigin?.repo, nuvioScraperId = preview.nuvioOrigin?.scraperId,
        )
        val staging = store.newStaging(m.id)
        try {
            // The record staged here is a placeholder: store.finishInstall rewrites it from a
            // record built with a FRESH read of any previous state, taken right before the atomic
            // commit below, not from one taken before the fetch/probe above (see its KDoc) --
            // nothing outside this store observes the staged one before that commit.
            store.writeFiles(staging, preview.manifestJson, m.entry, script, icon, buildRecord(null))
            return store.finishInstall(staging, m.id, isUpdate = preview.isUpdate) { previous -> buildRecord(previous) }
        } catch (e: IOException) {
            reportInstall(preview, "save", mapOf("error" to PluginTelemetry.errorName(e)))
            throw InstallException("No se pudo guardar el plugin: ${e.message}")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    suspend fun checkUpdate(id: String): UpdateOutcome {
        val current = store.get(id) ?: return UpdateOutcome.Failed("El plugin no está instalado")
        // touch() patches ONLY lastUpdateCheckAt (+ the pending fields, when given) onto whatever
        // is on disk for `id` at write time -- via store.updateRecord's own fresh read -- never
        // onto a snapshot taken here before previewFor()'s network fetch below, which can take
        // seconds and during which the person could disable the plugin, or the pool could mark it
        // unresponsive/damaged. Using a stale snapshot would silently undo that change.
        fun touch(patch: (InstalledRecord) -> InstalledRecord = { it }) {
            store.updateRecord(id) { fresh -> patch(fresh).copy(lastUpdateCheckAt = clock()) }
        }
        fun fail(message: String): UpdateOutcome { touch(); return UpdateOutcome.Failed(message) }
        val address = PluginAddress.parse(current.record.address) ?: return fail("Dirección inválida: ${current.record.address}")
        val preview = try { previewFor(address) } catch (e: InstallException) { return fail(e.message.orEmpty()) }
        if (preview.manifest.id != id) return fail("El repositorio ahora publica otro plugin (${preview.manifest.id})")
        if (SemVer.compare(preview.manifest.version, current.record.version) <= 0) {
            touch {
                it.copy(
                    pendingVersion = null, pendingHosts = emptyList(), pendingPermissions = emptyList(),
                    pendingCapabilities = emptyList(), pendingInsecureHosts = emptyList(), pendingLiveStreamHostsAny = false, pendingStreamHostsAny = false,
                    pendingSealedSecrets = false, pendingFetchHostsAny = false,
                )
            }
            return UpdateOutcome.UpToDate
        }
        // More reach than the person approved -- a host, a permission, a download/drm/channels capability,
        // a host newly marked insecureHttp, liveStreamHosts "any" or newly sealed secrets -- waits for
        // them. A new REQUIRED setting doesn't: the update applies and the plugin shows "Falta configurar".
        if (preview.newHosts.isNotEmpty() || preview.newPermissions.isNotEmpty() ||
            preview.newCapabilities.isNotEmpty() || preview.newInsecureHosts.isNotEmpty() || preview.newLiveStreamHostsAny ||
            preview.newStreamHostsAny || preview.newSealedSecrets || preview.newFetchHostsAny
        ) {
            touch {
                it.copy(
                    pendingVersion = preview.manifest.version, pendingHosts = preview.newHosts, pendingPermissions = preview.newPermissions,
                    pendingCapabilities = preview.newCapabilities, pendingInsecureHosts = preview.newInsecureHosts,
                    pendingLiveStreamHostsAny = preview.newLiveStreamHostsAny, pendingStreamHostsAny = preview.newStreamHostsAny,
                    pendingSealedSecrets = preview.newSealedSecrets, pendingFetchHostsAny = preview.newFetchHostsAny,
                )
            }
            return UpdateOutcome.NeedsApproval(preview)
        }
        return try {
            install(preview)
            UpdateOutcome.Applied(preview.manifest.version)
        } catch (e: InstallException) {
            fail(e.message.orEmpty())
        }
    }

    /**
     * Telemetry for an install/update that could not complete at [stage] (`download`, `probe`,
     * `exports`, `save`): a Nuvio-converted one is a [PluginFailureKind.CONVERSION], any other an
     * [PluginFailureKind.INSTALL]. [raw] (the probe's error) is cleaned by [PluginTelemetry].
     */
    private fun reportInstall(preview: InstallPreview, stage: String, detail: Map<String, String> = emptyMap(), raw: String? = null) {
        val m = preview.manifest
        val nuvio = preview.nuvioOrigin
        PluginTelemetry.current.report(
            PluginFailure(
                m.id, "install:$stage", if (nuvio != null) PluginFailureKind.CONVERSION else PluginFailureKind.INSTALL,
                raw = raw, detail = detail,
                facts = PluginFacts(m.version, m.apiVersion, if (nuvio != null) "nuvio" else "repo", nuvio?.repo, nuvio?.scraperId),
            ),
        )
    }

    /**
     * [headTwin]: the same repo and folder without a ref, for an [address] whose ref came from a
     * pasted GitHub page or file URL ([PluginAddress.isBrowsedRefUrl]). Only for a manifest with sealed secrets (which never open at an
     * explicit ref, [SealedSecrets.opensAt]): when the default branch serves the very same
     * `kino-plugin.json`, the plugin installs from there, ref-less, exactly as if the person had
     * typed `owner/repo/folder`; otherwise the refusal names that address.
     */
    private suspend fun previewFor(address: PluginAddress, headTwin: PluginAddress? = null): InstallPreview {
        val bytes = try {
            fetcher.fetch(address.rawUrl(PluginStore.MANIFEST_FILE), ManifestParser.MAX_BYTES + 1)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré kino-plugin.json en ${address.canonical}")
        } catch (e: IOException) {
            throw InstallException(installReadFailureMessage(e))
        }
        val json = bytes.toString(Charsets.UTF_8)
        val manifest = when (val r = ManifestParser.parse(json, knownPermissions)) {
            is ManifestResult.Valid -> r.manifest
            is ManifestResult.Invalid -> {
                // The repo has a kino-plugin.json, so its author meant a plugin: a field it got wrong is theirs to fix.
                PluginTelemetry.current.report(
                    PluginFailure(
                        address.owner + "/" + address.repo, "install:manifest", PluginFailureKind.INSTALL,
                        detail = mapOf("field" to r.field), facts = PluginFacts(null, null, "repo"),
                    ),
                )
                throw InstallException(r.message)
            }
        }
        if (headTwin != null && manifest.secrets.isNotEmpty()) {
            val atHead = runCatching { fetcher.fetch(headTwin.rawUrl(PluginStore.MANIFEST_FILE), ManifestParser.MAX_BYTES + 1) }.getOrNull()
            if (atHead == null || !atHead.contentEquals(bytes)) throw InstallException(treeSealsMessage(headTwin))
            return previewFor(headTwin)
        }
        verifySeals(address, manifest)
        return diffAgainstInstalled(address, manifest, json)
    }

    /**
     * Opens every declared secret once against [address], discarding the plaintext immediately: this
     * only proves the seal belongs to this plugin (a seal for another repo, or a tampered one, fails
     * GCM), never stores or logs anything. [SealException.message] equal to
     * [SealedSecrets.NO_NATIVE_MESSAGE] (thrown here when [sealAgreement] is null, or by the native
     * agreement when the device build can't open seals at all) means the app itself can't; any other
     * failure means the seal doesn't belong to this plugin. An address with any explicit `@ref` is
     * refused before any seal is tried ([SealedSecrets.opensAt]): that manifest may be a fork's.
     */
    private fun verifySeals(address: PluginAddress, manifest: PluginManifest) {
        if (manifest.secrets.isEmpty()) return
        if (!SealedSecrets.opensAt(address)) throw InstallException(NON_HEAD_SEALS_MESSAGE)
        val binding = SealedSecrets.bindingOf(address)
        manifest.secrets.forEach { (name, seal) ->
            try {
                SealedSecrets.open(seal, binding, name, sealAgreement ?: throw SealException(SealedSecrets.NO_NATIVE_MESSAGE), sealRecipient)
            } catch (e: SealException) {
                throw InstallException(if (e.message == SealedSecrets.NO_NATIVE_MESSAGE) NO_SEALS_MESSAGE else WRONG_SEALS_MESSAGE)
            }
        }
    }

    /**
     * Diffs a freshly built [manifest] against whatever [manifest.id] already has installed (if
     * anything): shared by [previewFor] and [NuvioPluginInstaller]'s own preview step (Task 5).
     */
    internal fun diffAgainstInstalled(address: PluginAddress, manifest: PluginManifest, json: String, nuvioOrigin: NuvioOrigin? = null): InstallPreview {
        val existing = store.get(manifest.id)
        if (existing != null && existing.record.address != address.canonical) {
            throw InstallException("Ya hay un plugin con ese id (${manifest.id}), instalado desde ${existing.record.address}")
        }
        val approved = existing?.record?.hosts.orEmpty().toSet()
        val approvedPermissions = existing?.record?.permissions.orEmpty().toSet()
        val approvedCapabilities = existing?.record?.capabilities.orEmpty().toSet()
        val approvedInsecureHosts = existing?.record?.insecureHosts.orEmpty().toSet()
        return InstallPreview(
            address, manifest, json, existing != null,
            newHosts = manifest.hosts.filterNot { it in approved },
            newPermissions = manifest.permissions.filterNot { it in approvedPermissions },
            newCapabilities = manifest.capabilities.filter { it in ManifestParser.APPROVAL_CAPABILITIES }.filterNot { it in approvedCapabilities },
            newInsecureHosts = manifest.insecureHosts.filterNot { it in approvedInsecureHosts },
            newLiveStreamHostsAny = manifest.liveStreamHostsAny && existing?.record?.liveStreamHostsAny != true,
            newSealedSecrets = manifest.secrets.isNotEmpty() && existing?.record?.sealedSecrets != true,
            newStreamHostsAny = manifest.streamHostsAny && existing?.record?.streamHostsAny != true,
            newFetchHostsAny = manifest.fetchHostsAny && nuvioOrigin != null && existing?.record?.fetchHostsAny != true,
            nuvioOrigin = nuvioOrigin,
        )
    }

    companion object {
        /**
         * The new version's declared [declared] hosts, plus every host the person approved
         * REACTIVELY for [previous] (a host in its record that its own manifest never declared --
         * `PluginRegistry.addApprovedHost` is the only other writer of `hosts`), all of them,
         * declared ones first: neither list has a count limit (a manifest's `hosts` none since 0.9.45;
         * the person's approvals only the safety cap of `PluginRegistry.addApprovedHost`). A reactive approval lasts until
         * uninstall (spec §9), not until the next update. A host the OLD manifest declared and the
         * new one drops is dropped: that narrowing is the plugin's own, and the person never approved
         * it separately. A reactive host the new manifest now covers (itself or a `*.` pattern)
         * isn't kept twice. The consent sheet is unaffected: [previewFor]'s `newHosts` already
         * compares the manifest against the whole installed record, so a reactively-approved host
         * the new version declares asks nothing, and a genuinely new one still waits for approval.
         */
        internal fun hostsCarriedOver(
            declared: List<String>,
            previous: StoredPlugin?,
        ): List<String> {
            val old = previous ?: return declared
            val reactive = old.record.hosts.filter { h -> h !in old.manifest.hosts && !HostRules.matches(h, declared) }
            return (declared + reactive).distinct()
        }

        const val MAX_SCRIPT_BYTES = 1024 * 1024
        const val MAX_ICON_BYTES = 128 * 1024
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Shown when a manifest declares `secrets` and this build has no way to open any seal at all. */
        const val NO_SEALS_MESSAGE = "Este Kino no puede abrir datos sellados"
        /** Shown when a manifest with `secrets` is installed or updated from any address with an explicit `@ref` ([SealedSecrets.opensAt]). */
        /** A pasted `…/tree/<branch>/…` (or blob/raw file) URL of a plugin with seals whose branch isn't what the default branch serves. */
        fun treeSealsMessage(headTwin: PluginAddress) =
            "Los datos sellados solo funcionan desde la rama principal del repositorio: escribe ${headTwin.canonical}"

        const val NON_HEAD_SEALS_MESSAGE = "Los datos sellados solo funcionan si instalas el plugin desde su rama principal, sin @rama"
        /** Shown when a seal doesn't open for this plugin's address (wrong repo, wrong name, tampered, or malformed). */
        const val WRONG_SEALS_MESSAGE = "Los datos sellados de este plugin no son para este repositorio o están dañados"
    }
}
