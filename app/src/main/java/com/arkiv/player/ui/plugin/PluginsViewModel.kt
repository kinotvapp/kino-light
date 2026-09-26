package com.arkiv.player.ui.plugin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.PluginAdmin
import com.arkiv.player.data.plugin.UpdateOutcome
import com.arkiv.player.data.plugin.catalog.CatalogEntry
import com.arkiv.player.data.plugin.catalog.CatalogOrigin
import com.arkiv.player.data.plugin.catalog.CatalogProvider
import com.arkiv.player.data.plugin.catalog.CatalogResult
import com.arkiv.player.data.plugin.catalog.PluginCatalog
import com.arkiv.player.data.plugin.catalog.PluginCatalogParser
import com.arkiv.player.data.plugin.catalog.filterCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

/** One catalog entry as the list shows it: [installed] is the plugin already installed from that repo, or null. */
data class CatalogRow(val entry: CatalogEntry, val installed: InstalledPlugin?)

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
)

/** What the seed line of the window says and does; the phone and the TV window both draw it. */
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

/** The rows for one snapshot of the catalog, the query and the installed plugins (each row marked by repo). */
private fun catalogUiState(result: CatalogResult, refreshing: Boolean, query: String, installed: List<InstalledPlugin>): CatalogUiState =
    CatalogUiState(
        loading = refreshing && result.catalog.entries.isEmpty(),
        rows = filterCatalog(result.catalog.entries, query).map { e -> CatalogRow(e, installed.firstOrNull { it.record.address == e.repo }) },
        origin = result.origin,
        refreshing = refreshing,
    )

/**
 * Ajustes ▸ Plugins on phone and TV: the same state, two layouts.
 *
 * [io] runs [PluginAdmin.setEnabled] and [PluginAdmin.uninstall]: they are plain functions that
 * write (uninstall deletes a directory tree), so they never run on Main.
 *
 * [catalogProvider] is the recommended-plugins list ([catalog]); the default is an empty one so a
 * screen that only needs the installed plugins (Configurar) never touches the network.
 */
class PluginsViewModel(
    private val admin: PluginAdmin,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val catalogProvider: CatalogProvider = object : CatalogProvider {
        override suspend fun load(force: Boolean) = EMPTY_SEED
        override fun cachedOrSeed() = EMPTY_SEED
    },
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

    /** The catalog as rows: filtered by the query, each marked with the installed plugin it matches (by repo). */
    val catalog: StateFlow<CatalogUiState> =
        combine(loaded, refreshing, query, admin.plugins) { result, isRefreshing, q, installed ->
            catalogUiState(result, isRefreshing, q, installed)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            catalogUiState(loaded.value, refreshing.value, query.value, admin.plugins.value),
        )

    init { loadCatalog(force = false) }

    fun onQueryChange(value: String) {
        query.value = value
        _state.update { it.copy(query = value) }
    }

    /** Asks the provider for a fresh download instead of the cached copy. */
    fun reloadCatalog() = loadCatalog(force = true)

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
            _state.update {
                if (preview.isUpdate) {
                    // The address field may hold something else the person was typing: keep it.
                    it.copy(message = "${m.name} quedó actualizado a la ${m.version}")
                } else {
                    it.copy(address = "", message = "${m.name} quedó instalado")
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
            val form = try {
                admin.settingsOf(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update {
                if (form == null) it.copy(message = "El plugin ya no está instalado", messagePluginId = null, settingsClosed = true)
                else it.copy(configuring = PluginConfigDraft.of(id, form.plugin.manifest.name, form.plugin.manifest.settings, form.values), settingsClosed = false)
            }
        }
    }

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
