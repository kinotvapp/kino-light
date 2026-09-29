package com.arkiv.player.data.plugin

import java.io.FileNotFoundException
import java.io.IOException

/** [NuvioPluginInstaller.previewRepo]'s result: [address] is the one that actually worked -- with an explicit `@ref` when a fallback (see [NuvioPluginInstaller.resolveNuvioManifest]) was needed -- so [PluginsViewModel.pickNuvioScraper] hands it, not the person's raw typed text, to [NuvioPluginInstaller.previewScraper]. */
data class NuvioRepoPreview(val address: String, val scrapers: List<NuvioScraperEntry>)

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
    /** Null when [input] doesn't parse as a plugin address, or no candidate branch has a Nuvio-shaped manifest: the caller falls back to [PluginInstaller.preview]. */
    suspend fun previewRepo(input: String): NuvioRepoPreview? {
        val address = PluginAddress.parse(input) ?: return null
        val (resolved, manifest) = try {
            resolveNuvioManifest(address)
        } catch (e: InstallException) {
            return null
        }
        return NuvioRepoPreview(resolved.canonical, NuvioManifestParser.installable(manifest))
    }

    /**
     * Fetches `manifest.json` at [typed] and parses it as Nuvio-shaped, returning it together with
     * the [PluginAddress] it actually came from. [typed] with an explicit `@ref` (the person asked
     * for that branch specifically, or this is a re-resolution of an address [previewRepo] already
     * settled on) is tried exactly once, whatever happens.
     *
     * A plain `owner/repo` address (`ref == `[PluginAddress.HEAD]`) that parses as WRONG-SHAPED --
     * `yoruix/nuvio-providers` (GitHub redirects the old name `tapframe/nuvio-providers` to it) has
     * default branch `template`, where `manifest.json` is a placeholder array, not
     * `{name, scrapers:[...]}` -- also tries `@main`, then `@master`, keeping the first one that
     * parses. A manifest.json that doesn't exist AT ALL on the default branch never gets this retry:
     * that 404 is what a plain native Kino plugin address (no Nuvio manifest, ever) looks like too,
     * and trying `@main`/`@master` for every one of those would add two dead network round trips
     * before [PluginsViewModel.add] falls through to [PluginInstaller]'s own native-plugin preview.
     */
    private suspend fun resolveNuvioManifest(typed: PluginAddress): Pair<PluginAddress, NuvioProviderManifest> {
        val primaryText = try {
            fetcher.fetch(typed.rawUrl(MANIFEST_FILE), MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré manifest.json en ${typed.canonical}")
        } catch (e: IOException) {
            throw InstallException(installReadFailureMessage(e))
        }
        NuvioManifestParser.parse(primaryText)?.let { return typed to it }
        if (typed.ref != PluginAddress.HEAD) throw InstallException("Este repositorio no tiene el formato de plugins de Nuvio")
        for (ref in FALLBACK_REFS) {
            val candidate = typed.copy(ref = ref)
            val text = try {
                fetcher.fetch(candidate.rawUrl(MANIFEST_FILE), MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
            } catch (e: IOException) {
                continue
            }
            val manifest = NuvioManifestParser.parse(text) ?: continue
            return candidate to manifest
        }
        throw InstallException("Este repositorio no tiene el formato de plugins de Nuvio")
    }

    suspend fun previewScraper(input: String, scraperId: String): InstallPreview {
        val typed = PluginAddress.parse(input) ?: throw InstallException("Dirección de repositorio inválida")
        val (address, manifest) = resolveNuvioManifest(typed)
        val scraper = manifest.scrapers.firstOrNull { it.id == scraperId } ?: throw InstallException("No encontré el scraper \"$scraperId\" en este repositorio")

        val scraperSource = try {
            fetcher.fetch(address.rawUrl(scraper.filename), PluginInstaller.MAX_SCRIPT_BYTES).toString(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            throw InstallException("No encontré ${scraper.filename} en ${address.canonical}")
        } catch (e: IOException) {
            throw InstallException(installReadFailureMessage(e))
        }

        val domainsUrl = NuvioHostExtractor.findDomainsJsonUrl(scraperSource)
        val remoteHosts = domainsUrl?.let { url ->
            runCatching { fetcher.fetch(url, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8) }
                .getOrNull()?.let { NuvioHostExtractor.parseDomainsJson(it, scraperSource) }
        } ?: NuvioRemoteHosts.NONE

        val conversion = NuvioPluginConverter.convert(scraper, scraperSource, repoSlug = address.canonical, tmdbApiKey = tmdbApiKey, remoteHosts = remoteHosts)
        if (conversion.scraperHosts.isEmpty()) {
            throw InstallException("No encontré ningún dominio en el código de \"${scraper.name}\": no se puede convertir a un plugin de Kino")
        }
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
     *
     * "Did anything change" is NOT a version comparison: the converter always writes `"1.0.0"`, so
     * that would never see a scraper whose upstream code changed without adding a host. Instead the
     * freshly converted script's sha256 is compared against the installed `record.sha256` (the same
     * [sha256Hex] of the same bytes [PluginInstaller.commit] stored), together with the generated
     * manifest itself (a `domains.json` rotation changes only the host list, not the script).
     *
     * The pending-update fields are written onto the record exactly like [PluginInstaller.checkUpdate]
     * does -- set on `NeedsApproval`, cleared on `UpToDate` (and by the fresh record [install] commits
     * on `Applied`) -- so `PluginRegistry` shows a background check's pending update as
     * `UPDATE_PENDING` in Ajustes, not only when the person taps "Buscar actualizaciones".
     * `lastUpdateCheckAt` stays [PluginUpdateCoordinator]'s job, on every outcome.
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
        val script = preview.nuvioOrigin?.script ?: return UpdateOutcome.Failed("No es un plugin convertido de Nuvio")
        val changed = sha256Hex(script) != current.record.sha256 || preview.manifest != current.manifest
        // Patches onto whatever is on disk NOW (store.updateRecord's own fresh read), never onto
        // `current`, read before previewScraper's network round trips -- same reason as PluginInstaller's touch().
        fun patch(block: (InstalledRecord) -> InstalledRecord) { installer.store.updateRecord(id, block) }
        if (!changed) {
            patch {
                it.copy(
                    pendingVersion = null, pendingHosts = emptyList(), pendingPermissions = emptyList(),
                    pendingCapabilities = emptyList(), pendingInsecureHosts = emptyList(), pendingLiveStreamHostsAny = false,
                )
            }
            return UpdateOutcome.UpToDate
        }
        if (preview.newHosts.isNotEmpty() || preview.newPermissions.isNotEmpty() ||
            preview.newCapabilities.isNotEmpty() || preview.newInsecureHosts.isNotEmpty() || preview.newLiveStreamHostsAny
        ) {
            patch {
                it.copy(
                    pendingVersion = preview.manifest.version, pendingHosts = preview.newHosts, pendingPermissions = preview.newPermissions,
                    pendingCapabilities = preview.newCapabilities, pendingInsecureHosts = preview.newInsecureHosts,
                    pendingLiveStreamHostsAny = preview.newLiveStreamHostsAny,
                )
            }
            return UpdateOutcome.NeedsApproval(preview)
        }
        return try {
            install(preview)
            UpdateOutcome.Applied(preview.manifest.version)
        } catch (e: InstallException) {
            UpdateOutcome.Failed(e.message.orEmpty())
        }
    }

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val MAX_MANIFEST_BYTES = 256 * 1024

        /** Tried in order, only after a ref-less address's default branch parses as NOT Nuvio-shaped. */
        private val FALLBACK_REFS = listOf("main", "master")
    }
}
