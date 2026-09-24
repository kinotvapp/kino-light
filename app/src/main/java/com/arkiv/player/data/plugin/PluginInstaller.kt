package com.arkiv.player.data.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileNotFoundException
import java.io.IOException

class InstallException(message: String) : Exception(message)

/** What the consent sheet shows before anything is downloaded beyond the manifest. */
data class InstallPreview(
    val address: PluginAddress,
    val manifest: PluginManifest,
    val manifestJson: String,
    val isUpdate: Boolean,
    /** Hosts not yet approved for this plugin (all of them on a first install). */
    val newHosts: List<String>,
)

sealed interface UpdateOutcome {
    data object UpToDate : UpdateOutcome
    data class Applied(val version: String) : UpdateOutcome
    data class NeedsApproval(val preview: InstallPreview) : UpdateOutcome
    data class Failed(val message: String) : UpdateOutcome
}

/** Reads a plugin file. Throws [FileNotFoundException] on 404, [IOException] otherwise. */
fun interface PluginFetcher {
    suspend fun fetch(url: String, maxBytes: Int): ByteArray
}

/** [PluginFetcher] for raw.githubusercontent.com only (destination #6 in `.claude/reglas.md`). */
class RawGithubFetcher(base: OkHttpClient) : PluginFetcher {
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun fetch(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val u = url.toHttpUrl()
        require(u.scheme == "https" && u.host == "raw.githubusercontent.com") { "not a raw GitHub URL: $url" }
        client.newCall(Request.Builder().url(u).build()).execute().use { r ->
            if (r.code == 404) throw FileNotFoundException(u.encodedPath)
            if (!r.isSuccessful) throw IOException("GitHub respondió ${r.code}")
            val source = r.body?.source() ?: throw IOException("respuesta vacía")
            if (source.request(maxBytes + 1L)) throw IOException("archivo demasiado grande")
            source.buffer.readByteArray()
        }
    }
}

/** Host for the install-time load check: the plugin must load without touching the network. */
object ProbePluginHost : PluginHost {
    override suspend fun fetch(requestJson: String): String = throw IOException("el plugin no puede usar la red al cargarse")
    override fun select(html: String, css: String): String = PluginHtml.selectJson(html, css)
    override fun storageGet(key: String): String? = null
    override fun storageSet(key: String, value: String) = Unit
    override fun storageRemove(key: String) = Unit
    override fun log(level: String, message: String) = Unit
}

/**
 * Install and update flow from the spec: fetch + validate the manifest, (consent happens in the
 * UI between [preview] and [install]), fetch script and icon, load the script in a throwaway
 * sandbox ([probe]) and require every declared capability to be an exported function, then commit
 * atomically. A failure at any step leaves the installed version untouched.
 */
class PluginInstaller(
    private val store: PluginStore,
    private val fetcher: PluginFetcher,
    private val probe: suspend (script: String) -> Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun preview(input: String): InstallPreview {
        val address = PluginAddress.parse(input)
            ?: throw InstallException("Escribe usuario/repositorio, por ejemplo lordmacu/kino-plugin-archive")
        return previewFor(address)
    }

    suspend fun install(preview: InstallPreview): InstalledRecord {
        val m = preview.manifest
        val script = try {
            fetcher.fetch(preview.address.rawUrl(m.entry), MAX_SCRIPT_BYTES)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré ${m.entry} en ${preview.address.canonical}")
        } catch (e: IOException) {
            throw InstallException("No se pudo descargar el plugin: ${e.message}")
        }
        val icon = m.icon?.let { runCatching { fetcher.fetch(preview.address.rawUrl(it), MAX_ICON_BYTES) }.getOrNull() }
        val exports = try {
            probe(script.toString(Charsets.UTF_8))
        } catch (e: PluginException) {
            throw InstallException("El plugin no carga: ${e.message}")
        }
        val missing = m.capabilities - exports
        if (missing.isNotEmpty()) throw InstallException("El plugin no carga: le falta ${missing.sorted().joinToString(", ")}")
        val sha = sha256Hex(script)
        val installedAt = clock()
        fun buildRecord(enabled: Boolean) = InstalledRecord(
            address = preview.address.canonical, version = m.version, sha256 = sha,
            hosts = m.hosts, installedAt = installedAt, enabled = enabled, lastUpdateCheckAt = installedAt,
        )
        val staging = store.newStaging(m.id)
        try {
            // The record staged here is a placeholder: store.finishInstall rewrites it from a
            // record built with a FRESH read of any previous state, taken right before the atomic
            // commit below, not from one taken before the fetch/probe above (see its KDoc) --
            // nothing outside this store observes the staged one before that commit.
            store.writeFiles(staging, preview.manifestJson, m.entry, script, icon, buildRecord(true))
            return store.finishInstall(staging, m.id) { previous -> buildRecord(previous?.enabled ?: true) }
        } catch (e: IOException) {
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
            touch { it.copy(pendingVersion = null, pendingHosts = emptyList()) }
            return UpdateOutcome.UpToDate
        }
        if (preview.newHosts.isNotEmpty()) {
            touch { it.copy(pendingVersion = preview.manifest.version, pendingHosts = preview.newHosts) }
            return UpdateOutcome.NeedsApproval(preview)
        }
        return try {
            install(preview)
            UpdateOutcome.Applied(preview.manifest.version)
        } catch (e: InstallException) {
            fail(e.message.orEmpty())
        }
    }

    /** For `UpdateWorker`: each plugin at most once per [maxAgeMs]. */
    suspend fun checkDueUpdates(maxAgeMs: Long = DAY_MS): List<Pair<String, UpdateOutcome>> =
        store.list()
            .filter { clock() - it.record.lastUpdateCheckAt >= maxAgeMs }
            .map { it.manifest.id to checkUpdate(it.manifest.id) }

    private suspend fun previewFor(address: PluginAddress): InstallPreview {
        val bytes = try {
            fetcher.fetch(address.rawUrl(PluginStore.MANIFEST_FILE), ManifestParser.MAX_BYTES + 1)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré kino-plugin.json en ${address.canonical}")
        } catch (e: IOException) {
            throw InstallException("No se pudo leer el plugin de GitHub: ${e.message}")
        }
        val json = bytes.toString(Charsets.UTF_8)
        val manifest = when (val r = ManifestParser.parse(json)) {
            is ManifestResult.Valid -> r.manifest
            is ManifestResult.Invalid -> throw InstallException(r.message)
        }
        val existing = store.get(manifest.id)
        if (existing != null && existing.record.address != address.canonical) {
            throw InstallException("Ya hay un plugin con ese id (${manifest.id}), instalado desde ${existing.record.address}")
        }
        val approved = existing?.record?.hosts.orEmpty().toSet()
        return InstallPreview(address, manifest, json, existing != null, manifest.hosts.filterNot { it in approved })
    }

    companion object {
        const val MAX_SCRIPT_BYTES = 1024 * 1024
        const val MAX_ICON_BYTES = 128 * 1024
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
