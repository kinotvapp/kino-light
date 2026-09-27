package com.arkiv.player.data.plugin.catalog

import com.arkiv.player.data.plugin.ManifestParser
import com.arkiv.player.data.plugin.ManifestResult
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.PluginFetcher
import com.arkiv.player.data.plugin.PluginInstaller
import com.arkiv.player.data.plugin.PluginStore
import com.arkiv.player.data.plugin.writeFileAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The art a recommended plugin ships in its own repo (`color` and `icon` of its `kino-plugin.json`):
 * [colorHex] is `#RRGGBB`, [iconFile] a PNG on disk. Either may be null (the plugin declared none, or
 * it could not be used); the card then falls back to the neutral default.
 */
data class CatalogArt(val colorHex: String?, val iconFile: File?)

interface CatalogArtProvider {
    /** What is on disk for [repo], whatever its age; never touches the network. */
    fun cached(repo: String): CatalogArt?

    /** The art for [repo]: the disk copy while it is younger than the TTL, else a download. Null when there is none. */
    suspend fun refresh(repo: String): CatalogArt?
}

/**
 * Fetches and caches each recommended plugin's own art from its GitHub repo, through the same
 * [PluginFetcher] the installer uses (raw.githubusercontent.com, destination #6 in `.claude/reglas.md`,
 * no new host). It only ever reads the manifest and the icon the manifest names, under the manifest's
 * own rules ([ManifestParser], [ManifestParser.isSafeRelativePath], at most
 * [PluginInstaller.MAX_ICON_BYTES], a real PNG).
 *
 * Nothing here may block or crash the list it decorates: every failure (offline, 404, invalid
 * manifest, not a PNG, a hostile repo string, a full disk) is a null or the stale copy, never an
 * exception; only [CancellationException] propagates. A repo that failed is not tried again in this
 * process until [ttlMs] passes.
 *
 * Disk layout under [dir]: `<owner>__<repo>/art.json` (`{"fetchedAt": ms, "color": "#RRGGBB"|null,
 * "icon": true|false}`, written last so it is the commit point) and `<owner>__<repo>/icon.png`, both via
 * temp file + rename. A GitHub owner never contains `_` (and [PluginAddress] refuses one), so the folder
 * name cannot collide between two repos.
 */
class CatalogArtRepository(
    private val fetcher: PluginFetcher,
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = PluginInstaller.DAY_MS,
    concurrency: Int = 3,
) : CatalogArtProvider {
    private val downloads = Semaphore(concurrency)

    // One lock per repo: a second caller waits, then finds the first one's copy on disk instead of
    // downloading again. Keyed by folder name; only repos that passed validation get one, so the maps
    // are bounded by the size of the catalog.
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    private class Target(val address: PluginAddress, val folder: File)

    /** What art.json says, plus where the icon would be. */
    private class Record(val fetchedAt: Long, val colorHex: String?, val hasIcon: Boolean, val iconFile: File) {
        val art: CatalogArt get() = CatalogArt(colorHex, iconFile.takeIf { hasIcon && it.isFile })
    }

    /** What one download produced; [keepPreviousIcon] is set when the icon failed for a reason that may be temporary. */
    private class Downloaded(val colorHex: String?, val icon: ByteArray?, val keepPreviousIcon: Boolean)

    override fun cached(repo: String): CatalogArt? {
        val target = targetOf(repo) ?: return null
        return readRecord(target.folder)?.art
    }

    override suspend fun refresh(repo: String): CatalogArt? {
        val target = targetOf(repo) ?: return null
        val key = target.folder.name
        return locks.computeIfAbsent(key) { Mutex() }.withLock {
            val previous = withContext(Dispatchers.IO) { readRecord(target.folder) }
            val now = clock()
            if (previous != null && isFresh(previous, now)) return@withLock previous.art
            val failed = failedAt[key]
            if (failed != null && now - failed in 0L until ttlMs) return@withLock previous?.art
            val downloaded = try {
                downloads.withPermit { download(target.address, previous) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val stored = downloaded?.let { store(target, previous, it) }
            if (stored == null) {
                failedAt[key] = clock()
                return@withLock previous?.art
            }
            failedAt.remove(key)
            stored
        }
    }

    /** The one entry point that turns a catalog string into a path: nothing reaches [dir] before both checks pass. */
    private fun targetOf(repo: String): Target? {
        if (!PluginCatalogParser.isValidRepo(repo)) return null
        val address = PluginAddress.parse(repo) ?: return null
        return Target(address, File(dir, "${address.owner}__${address.repo}"))
    }

    private fun isFresh(record: Record, now: Long): Boolean =
        // A copy dated in the future (the clock moved backwards) is stale, never fresh forever.
        now - record.fetchedAt in 0L until ttlMs && (!record.hasIcon || record.iconFile.isFile)

    /** The manifest, then its icon. Null when the manifest cannot be trusted; an unusable icon only costs the icon. */
    private suspend fun download(address: PluginAddress, previous: Record?): Downloaded? {
        val bytes = fetcher.fetch(address.rawUrl(PluginStore.MANIFEST_FILE), ManifestParser.MAX_BYTES + 1)
        if (bytes.size > ManifestParser.MAX_BYTES) return null
        val manifest = (ManifestParser.parse(bytes.toString(Charsets.UTF_8)) as? ManifestResult.Valid)?.manifest ?: return null
        val iconPath = manifest.icon ?: return Downloaded(manifest.color, icon = null, keepPreviousIcon = false)
        return try {
            val icon = fetcher.fetch(address.rawUrl(iconPath), PluginInstaller.MAX_ICON_BYTES).takeIf { isPng(it) }
            Downloaded(manifest.color, icon, keepPreviousIcon = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            Downloaded(manifest.color, icon = null, keepPreviousIcon = false)
        } catch (e: IOException) {
            // Offline or reset half-way: an icon that was good yesterday is better than none today.
            Downloaded(manifest.color, icon = null, keepPreviousIcon = previous?.hasIcon == true)
        } catch (e: Exception) {
            Downloaded(manifest.color, icon = null, keepPreviousIcon = false)
        }
    }

    /** Writes the icon, then art.json; null when the disk refuses (the caller then answers with the stale copy). */
    private suspend fun store(target: Target, previous: Record?, downloaded: Downloaded): CatalogArt? = withContext(Dispatchers.IO) {
        try {
            val iconFile = File(target.folder, ICON_FILE)
            val hasIcon = when {
                downloaded.icon != null -> {
                    writeFileAtomically(iconFile, downloaded.icon)
                    true
                }
                downloaded.keepPreviousIcon && previous?.hasIcon == true && iconFile.isFile -> true
                else -> {
                    iconFile.delete()
                    false
                }
            }
            val json = JSONObject()
                .put("fetchedAt", clock())
                .put("color", downloaded.colorHex ?: JSONObject.NULL)
                .put("icon", hasIcon)
            writeFileAtomically(File(target.folder, ART_FILE), json.toString().toByteArray(Charsets.UTF_8))
            CatalogArt(downloaded.colorHex, iconFile.takeIf { hasIcon })
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /** art.json as it is on disk, or null when it is missing or unreadable in any way. */
    private fun readRecord(folder: File): Record? = runCatching {
        val artFile = File(folder, ART_FILE)
        if (!artFile.isFile) return@runCatching null
        val o = JSONObject(artFile.readText())
        val fetchedAt = o.optLong("fetchedAt", -1L)
        if (fetchedAt < 0) return@runCatching null
        val color = (o.opt("color") as? String)?.takeIf { COLOR.matches(it) }
        Record(fetchedAt, color, o.optBoolean("icon", false), File(folder, ICON_FILE))
    }.getOrNull()

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > PNG_SIGNATURE.size && bytes.size <= PluginInstaller.MAX_ICON_BYTES &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private companion object {
        const val ART_FILE = "art.json"
        const val ICON_FILE = "icon.png"
        val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
