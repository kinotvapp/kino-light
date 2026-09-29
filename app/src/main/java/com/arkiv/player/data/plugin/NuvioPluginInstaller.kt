package com.arkiv.player.data.plugin

import java.io.FileNotFoundException
import java.io.IOException

/**
 * The Nuvio-format twin of [PluginInstaller]: detects a Nuvio provider repo, lists its scrapers,
 * converts a chosen one into a real Kino plugin via [NuvioPluginConverter], and installs/updates it
 * through [PluginInstaller]'s own atomic staging (spec §3-§7). Every manifest this produces is
 * validated by the SAME [ManifestParser.parse] a hand-written plugin goes through.
 */
class NuvioPluginInstaller(
    private val installer: PluginInstaller,
    private val fetcher: PluginFetcher,
    private val tmdbApiKey: String,
) {
    /** Null when [input] doesn't parse as a plugin address, or its manifest isn't Nuvio-shaped: the caller falls back to [PluginInstaller.preview]. */
    suspend fun previewRepo(input: String): List<NuvioScraperEntry>? {
        val address = PluginAddress.parse(input) ?: return null
        val text = try {
            fetcher.fetch(address.rawUrl(MANIFEST_FILE), MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        } catch (e: IOException) {
            return null
        }
        val manifest = NuvioManifestParser.parse(text) ?: return null
        return NuvioManifestParser.installable(manifest)
    }

    suspend fun previewScraper(input: String, scraperId: String): InstallPreview {
        val address = PluginAddress.parse(input) ?: throw InstallException("Dirección de repositorio inválida")
        val text = try {
            fetcher.fetch(address.rawUrl(MANIFEST_FILE), MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré manifest.json en ${address.canonical}")
        } catch (e: IOException) {
            throw InstallException(installReadFailureMessage(e))
        }
        val manifest = NuvioManifestParser.parse(text) ?: throw InstallException("Este repositorio no tiene el formato de plugins de Nuvio")
        val scraper = manifest.scrapers.firstOrNull { it.id == scraperId } ?: throw InstallException("No encontré el scraper \"$scraperId\" en este repositorio")

        val scraperSource = try {
            fetcher.fetch(address.rawUrl(scraper.filename), PluginInstaller.MAX_SCRIPT_BYTES).toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré ${scraper.filename} en ${address.canonical}")
        } catch (e: IOException) {
            throw InstallException(installReadFailureMessage(e))
        }

        val domainsUrl = NuvioHostExtractor.findDomainsJsonUrl(scraperSource)
        val extraHosts = domainsUrl?.let { url ->
            runCatching { fetcher.fetch(url, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8) }
                .getOrNull()?.let(::parseDomainsJson)
        }.orEmpty()

        val conversion = NuvioPluginConverter.convert(scraper, scraperSource, repoSlug = address.canonical, extraHosts = extraHosts, tmdbApiKey = tmdbApiKey)
        val manifestResult = ManifestParser.parse(conversion.manifestJson)
        val parsedManifest = (manifestResult as? ManifestResult.Valid)?.manifest
            ?: throw InstallException("El plugin generado no es válido: ${(manifestResult as ManifestResult.Invalid).message}")

        return installer.diffAgainstInstalled(
            address, parsedManifest, conversion.manifestJson,
            nuvioOrigin = NuvioOrigin(repo = address.canonical, scraperId = scraper.id, script = conversion.script.toByteArray(Charsets.UTF_8)),
        )
    }

    suspend fun install(preview: InstallPreview): InstalledRecord {
        val origin = preview.nuvioOrigin ?: throw InstallException("No es un plugin convertido de Nuvio")
        return installer.commit(preview, origin.script, icon = null)
    }

    /**
     * Re-runs the WHOLE conversion (spec §7): there is no stable URL serving the wrapped script to
     * just re-download. Always goes through [previewScraper] above -- never [PluginInstaller]'s own
     * generic `checkUpdate`/`previewFor` path, which defaults `nuvioOrigin` to null in
     * [PluginInstaller.diffAgainstInstalled] and would silently clear `nuvioRepo`/`nuvioScraperId`
     * off this record on commit (Task 4's review note).
     */
    suspend fun checkUpdate(id: String): UpdateOutcome {
        val current = installer.store.get(id) ?: return UpdateOutcome.Failed("El plugin no está instalado")
        val repo = current.record.nuvioRepo ?: return UpdateOutcome.Failed("Este plugin no viene de Nuvio")
        val scraperId = current.record.nuvioScraperId ?: return UpdateOutcome.Failed("Este plugin no viene de Nuvio")
        val preview = try {
            previewScraper(repo, scraperId)
        } catch (e: InstallException) {
            return UpdateOutcome.Failed(e.message.orEmpty())
        }
        if (SemVer.compare(preview.manifest.version, current.record.version) <= 0 && preview.newHosts.isEmpty()) return UpdateOutcome.UpToDate
        if (preview.newHosts.isNotEmpty() || preview.newPermissions.isNotEmpty() || preview.newCapabilities.isNotEmpty()) {
            return UpdateOutcome.NeedsApproval(preview)
        }
        return try {
            install(preview)
            UpdateOutcome.Applied(preview.manifest.version)
        } catch (e: InstallException) {
            UpdateOutcome.Failed(e.message.orEmpty())
        }
    }

    /** Nuvio's own `domains.json` files are plain arrays of domain strings; anything else parses to nothing. */
    private fun parseDomainsJson(text: String): List<String> = runCatching {
        val v = org.json.JSONTokener(text).nextValue()
        when (v) {
            is org.json.JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf { s -> '.' in s } }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
