package com.arkiv.player.ui.plugin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.PluginSettingsForm
import com.arkiv.player.data.plugin.UpdateOutcome
import com.arkiv.player.data.plugin.catalog.CatalogArt
import com.arkiv.player.data.plugin.catalog.CatalogArtProvider
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import com.arkiv.player.data.plugin.catalog.CatalogProvider
import com.arkiv.player.data.plugin.catalog.CatalogResult
import com.arkiv.player.data.plugin.catalog.PluginCatalog
import com.arkiv.player.data.plugin.catalog.PluginCatalogParser
import com.arkiv.player.data.plugin.catalog.filterCatalog
import com.arkiv.player.data.plugin.discovery.DiscoveredPlugin
import com.arkiv.player.data.plugin.discovery.DiscoveryResult
import com.arkiv.player.data.plugin.discovery.PluginDiscoveryProvider
import com.arkiv.player.data.plugin.discovery.dedupeDiscovered
import com.arkiv.player.data.plugin.discovery.ownerRepoKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginsUiState(
    val address: String = "",
    val busy: Boolean = false,
    /** One line of feedback in Spanish, or null. */
    val message: String? = null,
    /**
     * The plugin [message] is about (an update check on its row), or null when it's about the
     * whole section (adding, uninstalling). The row shows its own line so the result lands next
     * to the button that was pressed, not off-screen at the top of a long TV list.
     */
    val messagePluginId: String? = null,
    /** Non-null = the consent sheet is open for this install/update. */
    val consent: InstallPreview? = null,
    /** Non-null = asking "¿Desinstalar …?". */
    val confirmUninstall: InstalledPlugin? = null,
    /** Non-null = the Configurar screen is open with these values. */
    val configuring: PluginConfigDraft? = null,
    /** The Configurar screen was saved or cancelled (its own route pops on this). */
    val settingsClosed: Boolean = false,
    /** What the person typed in the catalog's search box; the rows in [PluginsViewModel.catalog] are filtered by it. */
    val query: String = "",
)

/**
 * One catalog entry as the list shows it: [installed] is the plugin already installed from that repo, or null.
 * [community]: found by the GitHub search (`De la comunidad`), not in the catalog.
 */
data class CatalogRow(val entry: CatalogEntry, val installed: InstalledPlugin?, val community: Boolean = false)

/**
 * What the recommended list shows. The rows are there from the first frame (the disk copy: the last good
 * download, else the seed shipped in the APK) and are replaced when a download ends. [refreshing] is true
 * from the start of a network load until it ends, forced reloads included. [loading] is true only while
 * there is truly nothing to show yet: no rows at all and a download pending.
 */
data class CatalogUiState(
    val loading: Boolean = true,
    val rows: List<CatalogRow> = emptyList(),
    val origin: CatalogOrigin? = null,
    val refreshing: Boolean = false,
    /**
     * Why the sources behind [rows] failed, as [CatalogResult.failures] reports it (the keys "cache" and
     * "seed" are local labels), so a later phase can report which one broke. Empty when nothing failed.
     * The screens do not read it.
     */
    val failures: Map<String, String> = emptyMap(),
)

/**
 * What "De la comunidad" shows: plugins found by the GitHub search, as ordinary [CatalogRow]s
 * ([CatalogRow.community] set) so the same cards, consent and install serve them. [loading] only while
 * there is nothing at all to show and a search is pending; [refreshing] for the whole of any load.
 */
data class CommunityUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<CatalogRow> = emptyList(),
)

/** The default discovery: knows nothing and never searches, for the screens that show no community list. */
internal object NoDiscovery : PluginDiscoveryProvider {
    override fun cached(): DiscoveryResult = DiscoveryResult.NONE
    override suspend fun load(force: Boolean): DiscoveryResult = DiscoveryResult.NONE
}

/**
 * [discovered] as rows (ruling R5 via [dedupeDiscovered]): after dropping what the catalog already lists
 * and what cannot be installed, filtered by [query] like the catalog, each marked with the installed
 * plugin from the same `owner/repo` (any spelling). The card's one tag names the author.
 */
internal fun communityRows(
    discovered: List<DiscoveredPlugin>,
    catalog: List<CatalogEntry>,
    installed: List<InstalledPlugin>,
    query: String,
): List<CatalogRow> {
    val entries = dedupeDiscovered(discovered, catalog, installed).map { d ->
        CatalogEntry(id = d.id, repo = d.address, name = d.name, description = d.description, tags = listOf("por ${d.owner}"))
    }
    return filterCatalog(entries, query).map { e ->
        CatalogRow(e, installed.firstOrNull { ownerRepoKey(it.record.address) == e.repo.lowercase() }, community = true)
    }
}

internal fun communityUiState(
    result: DiscoveryResult,
    refreshing: Boolean,
    catalog: List<CatalogEntry>,
    query: String,
    installed: List<InstalledPlugin>,
): CommunityUiState = CommunityUiState(
    loading = refreshing && result.plugins.isEmpty(),
    refreshing = refreshing,
    rows = communityRows(result.plugins, catalog, installed, query),
)

/** What the seed line of the screen says and does; the phone and the TV screen both draw it. */
data class CatalogRefreshLine(
    /** The notice, or null while a refresh is running (nothing to apologise for yet). */
    val notice: String?,
    /** "Reintentar", or "Actualizando…" while a refresh is running. */
    val actionLabel: String,
    /** False while a refresh is running: the action is shown but ignored. */
    val actionEnabled: Boolean,
)

/**
 * The line under the search box when the list is still the copy shipped in the APK, or null when the list
 * came from the network or the cache. While a refresh runs it shows no notice (the refresh may still
 * succeed); only a refresh that ended without replacing the seed says so.
 */
fun catalogRefreshLine(catalog: CatalogUiState): CatalogRefreshLine? =
    if (catalog.origin != CatalogOrigin.SEED) {
        null
    } else {
        CatalogRefreshLine(
            notice = if (catalog.refreshing) null else "No se pudo actualizar la lista de recomendados; mostrando la que viene con la app.",
            actionLabel = if (catalog.refreshing) "Actualizando…" else "Reintentar",
            actionEnabled = !catalog.refreshing,
        )
    }

private val EMPTY_SEED = CatalogResult(PluginCatalog(emptyList()), CatalogOrigin.SEED)

/** The default art provider: knows no art and makes no calls, for the screens that show no cards. */
internal object NoCatalogArt : CatalogArtProvider {
    override fun cached(repo: String): CatalogArt? = null
    override suspend fun refresh(repo: String): CatalogArt? = null
    override fun retryFailed() = Unit
}

/**
 * Whether two plugin addresses name the same plugin. `installed.json` stores [PluginAddress.canonical], so
 * `a/b.git`, `a/b@HEAD` and `https://github.com/a/b` are all `a/b`; comparing the raw strings would show
 * a plugin installed from one spelling as not installed under another. An address that does not parse has
 * no canonical form, so it only equals the very same string. The official Xuper addresses, new and legacy
 * ([XuperPrivilege.isOfficial]), name the same plugin.
 */
internal fun sameAddress(a: String, b: String): Boolean {
    val canonicalA = PluginAddress.parse(a)?.canonical
    val canonicalB = PluginAddress.parse(b)?.canonical
    if (canonicalA == null || canonicalB == null) return a == b
    // Xuper's new and legacy repos are one plugin: a Xuper installed from the old one is "Instalado"
    // on a catalog row that already names the new one (and keeps its art).
    return canonicalA == canonicalB || (XuperPrivilege.isOfficial(canonicalA) && XuperPrivilege.isOfficial(canonicalB))
}

/** The rows for one snapshot of the catalog, the query and the installed plugins (each row marked by its address). */
private fun catalogUiState(result: CatalogResult, refreshing: Boolean, query: String, installed: List<InstalledPlugin>): CatalogUiState =
    CatalogUiState(
        loading = refreshing && result.catalog.entries.isEmpty(),
        rows = filterCatalog(result.catalog.entries, query).map { e -> CatalogRow(e, installed.firstOrNull { sameAddress(it.record.address, e.repo) }) },
        origin = result.origin,
        refreshing = refreshing,
        failures = result.failures,
    )

/**
 * The Plugins screen on phone and TV (Ajustes ▸ Plugins): the same state, two layouts.
 *
 * [io] runs [PluginAdmin.setEnabled] and [PluginAdmin.uninstall]: they are plain functions that
 * write (uninstall deletes a directory tree), so they never run on Main.
 *
 * [catalogProvider] is the recommended-plugins list ([catalog]); the default is an empty one so a
 * screen that only needs the installed plugins (Configurar) never touches the network.
 *
 * [artProvider] is the art each listed row's own repo ships ([art]); the default knows none and makes
 * no calls, so only the screens that draw cards pay for it. Its refreshes run on [io]: the real
 * repository reads and parses files on the thread that calls it.
 *
 * [discovery] is the GitHub community list ([community]); the default knows none and never searches.
 */
class PluginsViewModel(
    private val admin: PluginAdmin,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val catalogProvider: CatalogProvider = object : CatalogProvider {
        override suspend fun load(force: Boolean) = EMPTY_SEED
        override fun cachedOrSeed() = EMPTY_SEED
    },
    private val artProvider: CatalogArtProvider = NoCatalogArt,
    private val discovery: PluginDiscoveryProvider = NoDiscovery,
) : ViewModel() {
    val plugins: StateFlow<List<InstalledPlugin>> = admin.plugins

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    // Declared before [catalog], which combines them: an Eagerly started flow reads them on creation.
    // [loaded] starts as what is on the device, so the very first state already has rows: the network only
    // refreshes them. [refreshing] starts true because [init] starts that refresh at once.
    private val loaded = MutableStateFlow(diskCatalog())
    private val refreshing = MutableStateFlow(true)
    private val query = MutableStateFlow("")
    private var catalogLoad: Job? = null
    private var loadGeneration = 0

    /** The catalog as rows: filtered by the query, each marked with the installed plugin it matches (by [sameAddress]). */
    val catalog: StateFlow<CatalogUiState> =
        combine(loaded, refreshing, query, admin.plugins) { result, isRefreshing, q, installed ->
            catalogUiState(result, isRefreshing, q, installed)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            catalogUiState(loaded.value, refreshing.value, query.value, admin.plugins.value),
        )

    private val discovered = MutableStateFlow(diskDiscovery())
    private val discovering = MutableStateFlow(true)
    private var communityLoad: Job? = null

    /** "De la comunidad": the discovered plugins as rows, deduped against the catalog and the installed ones. */
    val community: StateFlow<CommunityUiState> =
        combine(discovered, discovering, loaded, query, admin.plugins) { result, isLoading, cat, q, installed ->
            communityUiState(result, isLoading, cat.catalog.entries, q, installed)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            communityUiState(discovered.value, discovering.value, loaded.value.catalog.entries, query.value, admin.plugins.value),
        )

    // What is on disk for the rows listed at construction, read here on the calling thread (a handful of
    // tiny files, like [diskCatalog]) so the very first [art] value already has it.
    private val _art = MutableStateFlow(diskArt((catalog.value.rows + community.value.rows).map { it.entry.repo }))

    /**
     * The art of the listed rows, keyed by the entry's `repo` exactly as in the catalog. A repo with no art
     * is absent, never null-valued. It starts as what is on disk, and each repo's refresh lands on its own
     * as it ends: a slow or failed one never holds back the others.
     */
    val art: StateFlow<Map<String, CatalogArt>> = _art.asStateFlow()

    // Every repo is requested once per view-model lifetime, whatever it answers: the repository keeps its own
    // negative cache, and asking again on every keystroke of the search would not help. The one thing that
    // asks again is [reloadCatalog] ("Reintentar"), and only for a repo whose request has ended without art
    // ([retryArt]). The value is that repo's latest request, so "still in flight" is its [Job.isActive].
    // Only touched from Main.
    private val artRequests = HashMap<String, Job>()

    init {
        loadCatalog(force = false)
        loadCommunity(force = false)
        // Follows the rows as they are listed (the search filter, a reload, the download replacing the
        // disk copy), so a row that shows up later gets its art too.
        viewModelScope.launch {
            combine(catalog, community) { c, m -> (c.rows + m.rows).map { it.entry.repo } }.distinctUntilChanged().collect { requestArt(it) }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
        _state.update { it.copy(query = value) }
    }

    /**
     * "Reintentar": asks the provider for a fresh download instead of the cached copy, and asks again for the
     * art the last attempt did not get (see [retryArt]).
     */
    fun reloadCatalog() {
        retryArt()
        loadCatalog(force = true)
    }

    /** "Actualizar" of "De la comunidad": asks for a new search (the discovery client decides whether GitHub may be asked) and retries the missing art. */
    fun refreshCommunity() {
        retryArt()
        loadCommunity(force = true)
    }

    private fun diskDiscovery(): DiscoveryResult =
        try {
            discovery.cached()
        } catch (e: Exception) {
            DiscoveryResult.NONE
        }

    /**
     * One load at a time: while one runs, another request is ignored (the screens disable "Actualizar"
     * meanwhile). GitHub never breaks the screen: a failure keeps what is shown.
     */
    private fun loadCommunity(force: Boolean) {
        if (communityLoad?.isActive == true) return
        discovering.value = true
        communityLoad = viewModelScope.launch {
            try {
                discovered.value = withContext(io) { discovery.load(force) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what is on screen.
            } finally {
                discovering.value = false
            }
        }
    }

    /**
     * What is on the device (see [CatalogProvider.cachedOrSeed]), read on the calling thread: two small
     * local files. A provider that throws anyway leaves an empty list, which the download then fills.
     */
    private fun diskCatalog(): CatalogResult =
        try {
            catalogProvider.cachedOrSeed()
        } catch (e: Exception) {
            EMPTY_SEED
        }

    /**
     * What [artProvider] has on disk for each of [repos]; a repo with nothing, and a provider that throws
     * anyway, leave that repo out (its refresh is still requested).
     */
    private fun diskArt(repos: List<String>): Map<String, CatalogArt> {
        val found = LinkedHashMap<String, CatalogArt>()
        for (repo in repos) {
            val cached = try {
                artProvider.cached(repo)
            } catch (e: Exception) {
                null
            }
            if (cached != null) found[repo] = cached
        }
        return found
    }

    /**
     * Starts the refresh of every repo in [repos] not requested before, each on [io] as its own job so the
     * answers land one at a time. An answer of null leaves what [art] holds; a provider that throws leaves
     * it untouched too (only cancellation is passed on, so clearing the view model stops them all).
     */
    private fun requestArt(repos: List<String>) {
        for (repo in repos) {
            if (repo in artRequests) continue
            artRequests[repo] = viewModelScope.launch(io) {
                val refreshed = try {
                    artProvider.refresh(repo)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (refreshed != null) _art.update { it + (repo to refreshed) }
            }
        }
    }

    /**
     * Gives the art another try after "Reintentar", the person's own signal that the network may be back. The
     * provider first forgets its recent failures (in memory, it never blocks, so it is called right here and
     * is done before any request below), then every repo whose request has ended and left it without art is
     * forgotten too, and the rows listed now are requested again. A repo that has art (its disk copy, or
     * a refresh that landed) is left alone, and so is one whose request is still running: never twice at once.
     * A repo the search hides is forgotten as well, so it is requested when it is listed again.
     */
    private fun retryArt() {
        try {
            artProvider.retryFailed()
        } catch (e: Exception) {
            // The provider then keeps its failures; the requests below still run.
        }
        val withArt = _art.value
        artRequests.entries.removeAll { (repo, request) -> !request.isActive && repo !in withArt }
        requestArt((catalog.value.rows + community.value.rows).map { it.entry.repo })
    }

    /**
     * The newest request wins: an older one still running is cancelled, so a slow cached answer can
     * never land on top of a forced download, and only the newest one may end [refreshing] (a cancelled
     * load that blocks on IO finishes after its replacement has started). A download that fails keeps
     * the rows already shown.
     */
    private fun loadCatalog(force: Boolean) {
        catalogLoad?.cancel()
        val generation = ++loadGeneration
        refreshing.value = true
        catalogLoad = viewModelScope.launch {
            try {
                loaded.value = catalogProvider.load(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what is on screen: the person can still read and install from it.
            } finally {
                if (generation == loadGeneration) refreshing.value = false
            }
        }
    }

    /** Only an `owner/repo` the parser would have accepted goes to the installer, whatever built the entry. */
    fun installFromCatalog(entry: CatalogEntry) {
        if (!PluginCatalogParser.isValidRepo(entry.repo)) return
        busy(pluginId = null) { _state.update { it.copy(consent = admin.preview(entry.repo)) } }
    }

    /**
     * The custom address the person is typing. It also drops the message of an earlier action and the row it
     * was about: what a previous try said is not news about a new address, so the Plugins screens change it
     * to "" both when their "Agregar" dialog opens and when it is dismissed.
     */
    fun onAddressChange(value: String) = _state.update { it.copy(address = value, message = null, messagePluginId = null) }

    fun add() {
        val input = _state.value.address.trim()
        if (input.isEmpty()) return
        busy(pluginId = null) { _state.update { it.copy(consent = admin.preview(input)) } }
    }

    fun confirmInstall() {
        val preview = _state.value.consent ?: return
        _state.update { it.copy(consent = null) }
        val m = preview.manifest
        busy(pluginId = m.id.takeIf { preview.isUpdate }) {
            admin.install(preview)
            if (preview.isUpdate) {
                // The address field may hold something else the person was typing: keep it. An update never
                // opens Configurar: the plugin was already set up (or the person already skipped it).
                _state.update { it.copy(message = "${m.name} quedó actualizado a la ${m.version}") }
            } else {
                // A new plugin that cannot work until a required setting is filled goes straight to its
                // Configurar instead of waiting for the person to find the button. Whatever stops that
                // (the plugin gone, a failed read) only skips it: the install itself succeeded.
                val setup = settingsFormOf(m.id)?.takeIf { it.plugin.needsSetup }
                _state.update {
                    val installed = it.copy(address = "", message = "${m.name} quedó instalado")
                    // settingsClosed = false only mirrors openSettings: just the standalone Configurar route reads it.
                    if (setup == null) installed else installed.copy(configuring = draftOf(m.id, setup), settingsClosed = false)
                }
            }
        }
    }

    fun cancelConsent() = _state.update { it.copy(consent = null) }

    fun checkUpdate(id: String) = busy(pluginId = id) {
        val message = when (val outcome = admin.checkUpdate(id)) {
            UpdateOutcome.UpToDate -> "Ya tienes la última versión"
            is UpdateOutcome.Applied -> "Actualizado a la ${outcome.version}"
            is UpdateOutcome.NeedsApproval -> {
                _state.update { it.copy(consent = outcome.preview) }
                null
            }
            // Already Spanish, written by the installer -- including "Este plugin necesita una
            // versión más nueva de Kino" for an update that raises apiVersion past what this
            // build supports (a failed check, never a pending approval).
            is UpdateOutcome.Failed -> outcome.message
        }
        _state.update { it.copy(message = message) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(io) { admin.setEnabled(id, enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = pluginErrorText(e), messagePluginId = id) }
            }
        }
    }

    fun askUninstall(plugin: InstalledPlugin) {
        // The TV's actions can't be disabled while busy; confirming would then be dropped by busy().
        if (_state.value.busy) return
        _state.update { it.copy(confirmUninstall = plugin) }
    }

    fun cancelUninstall() = _state.update { it.copy(confirmUninstall = null) }

    /** Opens Configurar with the stored values (read on IO by the admin: passwords come from the Keystore). */
    fun openSettings(id: String) {
        viewModelScope.launch {
            val form = settingsFormOf(id)
            _state.update {
                if (form == null) it.copy(message = "El plugin ya no está instalado", messagePluginId = null, settingsClosed = true)
                else it.copy(configuring = draftOf(id, form), settingsClosed = false)
            }
        }
    }

    /** The stored settings of [id], or null when it is not installed or they cannot be read. */
    private suspend fun settingsFormOf(id: String): PluginSettingsForm? =
        try {
            admin.settingsOf(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun draftOf(id: String, form: PluginSettingsForm): PluginConfigDraft =
        PluginConfigDraft.of(id, form.plugin.manifest.name, form.plugin.manifest.settings, form.values)

    fun onSettingChange(key: String, value: Any?) = _state.update { s -> s.copy(configuring = s.configuring?.with(key, value)) }

    fun closeSettings() = _state.update { it.copy(configuring = null, settingsClosed = true) }

    /**
     * Checks the values first (the same rule the store applies), then saves on IO. Saving closes
     * the plugin's runtime and forgets its cookies (a new user must not inherit a session).
     */
    fun saveSettings() {
        val draft = _state.value.configuring ?: return
        if (draft.saving) return
        draft.problem()?.let { problem -> _state.update { it.copy(configuring = draft.copy(error = problem)) }; return }
        _state.update { it.copy(configuring = draft.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val refused = try {
                admin.saveSettings(draft.pluginId, draft.values)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pluginErrorText(e)
            }
            _state.update {
                if (refused != null) it.copy(configuring = it.configuring?.copy(saving = false, error = refused))
                else it.copy(configuring = null, settingsClosed = true, message = "${draft.pluginName} quedó configurado", messagePluginId = draft.pluginId)
            }
        }
    }

    fun confirmUninstall() {
        val plugin = _state.value.confirmUninstall ?: return
        _state.update { it.copy(confirmUninstall = null) }
        busy(pluginId = null) {
            withContext(io) { admin.uninstall(plugin.id) }
            _state.update { it.copy(message = "${plugin.manifest.name} quedó desinstalado") }
        }
    }

    /**
     * Runs one action at a time: a second one while [PluginsUiState.busy] is ignored (the UI
     * disables its buttons, this is the guarantee). Failures become [pluginErrorText] on the
     * section, or on [pluginId]'s row.
     */
    private fun busy(pluginId: String?, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, messagePluginId = pluginId) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = pluginErrorText(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

/**
 * The installed plugin whose row shows [PluginsUiState.message], or null to show it under
 * "Agregar" -- also when the message is about a plugin that is no longer installed.
 */
fun rowMessagePluginId(state: PluginsUiState, plugins: List<InstalledPlugin>): String? =
    state.messagePluginId?.takeIf { id -> plugins.any { it.id == id } }
