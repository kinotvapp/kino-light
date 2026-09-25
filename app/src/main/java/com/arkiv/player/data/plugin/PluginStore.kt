package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** `installed.json`: what the person approved and the state the app keeps about the plugin. */
data class InstalledRecord(
    val address: String,
    val version: String,
    /** sha256 of the entry script as installed; checked on every load. */
    val sha256: String,
    /** The hosts the person APPROVED — the gate uses these, never the manifest on disk. */
    val hosts: List<String>,
    val installedAt: Long,
    val enabled: Boolean = true,
    val unresponsive: Boolean = false,
    val damaged: Boolean = false,
    val lastUpdateCheckAt: Long = 0L,
    val pendingVersion: String? = null,
    val pendingHosts: List<String> = emptyList(),
    /** The permissions the person APPROVED (from the closed list in contract.json; none exist in SDK v1). */
    val permissions: List<String> = emptyList(),
    /** Permissions a pending update adds, shown on its consent sheet. */
    val pendingPermissions: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("address", address).put("version", version).put("sha256", sha256)
        .put("hosts", JSONArray(hosts)).put("installedAt", installedAt).put("enabled", enabled)
        .put("unresponsive", unresponsive).put("damaged", damaged)
        .put("lastUpdateCheckAt", lastUpdateCheckAt)
        .put("pendingVersion", pendingVersion ?: JSONObject.NULL)
        .put("pendingHosts", JSONArray(pendingHosts))
        .put("permissions", JSONArray(permissions))
        .put("pendingPermissions", JSONArray(pendingPermissions))
        .toString()

    companion object {
        fun fromJson(text: String): InstalledRecord? = runCatching {
            val o = JSONObject(text)
            fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            InstalledRecord(
                address = o.getString("address"), version = o.getString("version"),
                sha256 = o.getString("sha256"), hosts = list("hosts"),
                installedAt = o.optLong("installedAt"), enabled = o.optBoolean("enabled", true),
                unresponsive = o.optBoolean("unresponsive"), damaged = o.optBoolean("damaged"),
                lastUpdateCheckAt = o.optLong("lastUpdateCheckAt"),
                pendingVersion = if (o.isNull("pendingVersion")) null else o.optString("pendingVersion").ifEmpty { null },
                pendingHosts = list("pendingHosts"),
                permissions = list("permissions"),
                pendingPermissions = list("pendingPermissions"),
            )
        }.getOrNull()
    }
}

data class StoredPlugin(val manifest: PluginManifest, val manifestJson: String, val record: InstalledRecord, val dir: File) {
    val iconFile: File? get() = File(dir, PluginStore.ICON_FILE).takeIf { it.isFile }
}

/**
 * Installed plugins on disk. A plugin directory only ever appears whole: files are written to a
 * staging directory, and [commit] swaps it in with renames, restoring the previous version if the
 * swap fails. The manifest is re-validated on every read; a folder that doesn't parse is ignored.
 */
class PluginStore(private val root: File, private val dataRoot: File) {

    fun list(): List<StoredPlugin> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.mapNotNull(::read).orEmpty()

    fun get(id: String): StoredPlugin? = File(root, id).takeIf { it.isDirectory }?.let(::read)

    fun newStaging(id: String): File = File(root, ".staging-$id-${System.nanoTime()}").apply { mkdirs() }

    fun writeFiles(staging: File, manifestJson: String, entry: String, script: ByteArray, icon: ByteArray?, record: InstalledRecord) {
        File(staging, MANIFEST_FILE).writeText(manifestJson)
        File(staging, entry).apply { parentFile?.mkdirs() }.writeBytes(script)
        icon?.let { File(staging, ICON_FILE).writeBytes(it) }
        File(staging, RECORD_FILE).writeText(record.toJson())
    }

    @Synchronized fun commit(staging: File, id: String) {
        val target = File(root, id)
        val backup = File(root, ".old-$id-${System.nanoTime()}")
        if (target.exists() && !target.renameTo(backup)) throw IOException("no se pudo reemplazar la versión anterior")
        if (!staging.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            throw IOException("no se pudo instalar")
        }
        backup.deleteRecursively()
        forgetRemoved(id)
    }

    /**
     * Finishes an install/update under this store's lock: [build] gets a fresh read of whatever
     * record is on disk for [id] right now -- taken here, not by the caller before its own slow
     * network fetch + sandbox probe -- so it can carry forward a person-controlled/health field
     * (e.g. `enabled`) that changed while that work was running, without clobbering it with a value
     * read before the change happened. The record [build] returns replaces the one already staged
     * (nothing outside this store observes the staged one before [commit] swaps it in).
     *
     * [isUpdate]: this replaces an installed plugin. If that plugin is gone by now, the person
     * uninstalled it while the update was downloading or waiting for approval: committing would
     * bring it back, enabled, undoing their withdrawal of consent, so it throws instead.
     */
    @Synchronized fun finishInstall(
        staging: File,
        id: String,
        isUpdate: Boolean,
        build: (InstalledRecord?) -> InstalledRecord,
    ): InstalledRecord {
        val previous = get(id)?.record
        if (isUpdate && previous == null) throw InstallException("El plugin se desinstaló mientras se actualizaba")
        val record = build(previous)
        File(staging, RECORD_FILE).writeText(record.toJson())
        commit(staging, id)
        return record
    }

    @Synchronized fun writeRecord(id: String, record: InstalledRecord) {
        val dir = File(root, id)
        if (!dir.isDirectory) return
        writeFileAtomically(File(dir, RECORD_FILE), record.toJson().toByteArray(Charsets.UTF_8))
    }

    /**
     * Read-modify-write under this store's lock: [change] is applied to a record read fresh from
     * disk right here, not to a snapshot the caller may have taken before something slow (e.g. a
     * network fetch in [PluginInstaller.checkUpdate]) -- so a concurrent change to a field [change]
     * doesn't touch (the person disabling the plugin, the pool marking it unresponsive/damaged)
     * survives instead of being silently overwritten by this write. Returns the record written, or
     * null if [id] isn't installed (any more).
     */
    @Synchronized fun updateRecord(id: String, change: (InstalledRecord) -> InstalledRecord): InstalledRecord? {
        val fresh = get(id)?.record ?: return null
        val next = change(fresh)
        writeRecord(id, next)
        return next
    }

    /** The entry script, only if its sha256 still matches `installed.json`. */
    fun readVerifiedScript(id: String): String {
        val p = get(id) ?: throw PluginScriptException("El plugin no está instalado")
        val bytes = runCatching { File(p.dir, p.manifest.entry).readBytes() }.getOrNull() ?: throw PluginDamagedException()
        if (sha256Hex(bytes) != p.record.sha256) throw PluginDamagedException()
        return bytes.toString(Charsets.UTF_8)
    }

    fun dataDir(id: String): File = File(dataRoot, id)

    /** Deletes the plugin and its data (storage, Home cache). Library rows are NOT touched. */
    @Synchronized fun remove(id: String, name: String) {
        File(root, id).deleteRecursively()
        dataDir(id).deleteRecursively()
        root.mkdirs()
        val names = readRemoved().put(id, name)
        writeFileAtomically(File(root, REMOVED_FILE), names.toString().toByteArray(Charsets.UTF_8))
    }

    fun removedName(id: String): String? = readRemoved().optString(id).ifEmpty { null }

    /** Leftovers of an install or update interrupted by a crash. */
    fun cleanStaging() {
        root.listFiles()?.filter { it.name.startsWith(".staging-") || it.name.startsWith(".old-") }?.forEach { it.deleteRecursively() }
    }

    private fun forgetRemoved(id: String) {
        val names = readRemoved()
        if (names.has(id)) {
            names.remove(id)
            writeFileAtomically(File(root, REMOVED_FILE), names.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun readRemoved(): JSONObject =
        runCatching { JSONObject(File(root, REMOVED_FILE).readText()) }.getOrElse { JSONObject() }

    private fun read(dir: File): StoredPlugin? {
        val json = runCatching { File(dir, MANIFEST_FILE).readText() }.getOrNull() ?: return null
        val manifest = (ManifestParser.parse(json) as? ManifestResult.Valid)?.manifest ?: return null
        if (manifest.id != dir.name) return null
        val record = runCatching { File(dir, RECORD_FILE).readText() }.getOrNull()?.let(InstalledRecord::fromJson) ?: return null
        return StoredPlugin(manifest, json, record, dir)
    }

    companion object {
        const val MANIFEST_FILE = "kino-plugin.json"
        const val RECORD_FILE = "installed.json"
        const val ICON_FILE = "icon.png"
        private const val REMOVED_FILE = "removed.json"
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
