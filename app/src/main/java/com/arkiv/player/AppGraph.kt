package com.arkiv.player

import android.content.Context
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.catalog.AniListApi
import com.arkiv.player.data.catalog.AnimeMappingRepository
import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.SearchHistoryRepo
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.recommendations.AiReferee
import com.arkiv.player.data.recommendations.SourceSearcher
import com.arkiv.player.data.recommendations.TmdbSearcher
import com.arkiv.player.data.recommendations.ForYouGenerator
import com.arkiv.player.data.recommendations.withRealKind
import com.arkiv.player.data.recommendations.NormalizeTitle
import com.arkiv.player.data.recommendations.HistorySignals
import com.arkiv.player.data.recommendations.ForYouVerification
import com.arkiv.player.data.update.ApkDownloader
import com.arkiv.player.data.update.UpdateChecker
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.data.plugin.*
import com.arkiv.player.dlna.DlnaController
import com.arkiv.player.companion.CompanionManager
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/** Manual dependency graph (no Hilt): app singletons. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val database: ArkivDatabase by lazy { ArkivDatabase.get(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val searchHistory: SearchHistoryRepo by lazy {
        SearchHistoryRepo(database.searchHistoryDao(), database.recentTitleDao())
    }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .dns(com.arkiv.player.data.net.DohDns)
            .build())
    }

    private val _updateInfo = kotlinx.coroutines.flow.MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: kotlinx.coroutines.flow.StateFlow<UpdateInfo?> = _updateInfo

    private val _hasInternet = kotlinx.coroutines.flow.MutableStateFlow(true)
    val hasInternet: kotlinx.coroutines.flow.StateFlow<Boolean> = _hasInternet

    private val _homeReloads = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Manual "recargar catálogo" pulses from the top bar's reload button. Before each pulse the
     *  shared Magis catalog is invalidated ([com.arkiv.player.ui.home.MagisHomeCatalog.invalidate]:
     *  its next pass asks the portal past both caches, the Room snapshot kept as the fallback); the
     *  collectors (Home's plugin rows with `force`, which also skips `home.json`'s TTL, and the
     *  Categorías screen) then re-read it. Presses within 60 s of the last accepted one are ignored
     *  ([com.arkiv.player.ui.home.ForcedReloadGate]), so repeated presses never pace the portal. */
    val homeReloads: kotlinx.coroutines.flow.SharedFlow<Unit> = _homeReloads
    private val homeReloadGate = com.arkiv.player.ui.home.ForcedReloadGate()
    fun reloadHomeCatalog() {
        if (!homeReloadGate.tryAcquire()) return
        magisHomeCatalog.invalidate()
        _homeReloads.tryEmit(Unit)
    }

    private val networkMonitor: android.net.ConnectivityManager.NetworkCallback by lazy {
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { _hasInternet.value = true }
            override fun onLost(network: android.net.Network) {
                val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
                val active = cm?.activeNetwork
                if (active == null) _hasInternet.value = false
            }
            override fun onUnavailable() { _hasInternet.value = false }
        }.also { cb ->
            runCatching {
                val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
                    ?: return@runCatching
                // Initial state: check whether there's already a network at startup
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                _hasInternet.value = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                cm.registerDefaultNetworkCallback(cb)
            }.onFailure { android.util.Log.w("ArkivNetwork", "networkMonitor: ${it.message}") }
        }
    }

    /** Starts the connectivity monitor; call from Application.onCreate. */
    fun startNetworkMonitor() { networkMonitor }  // access forces the lazy to initialize

    val apkDownloader: ApkDownloader by lazy { ApkDownloader(appContext.cacheDir) }

    val credentialsStore: com.arkiv.player.data.credentials.RemoteCredentialsStore by lazy {
        com.arkiv.player.data.credentials.EncryptedRemoteCredentialsStore(appContext)
    }

    val credentialsActivator: com.arkiv.player.data.credentials.CredentialsActivator by lazy {
        com.arkiv.player.data.credentials.CredentialsActivator(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .dns(com.arkiv.player.data.net.DohDns)
                .build(),
            isOnline = { hasInternet.value },
        )
    }

    /** Backs the manual "Sembrar semillas" retry (Settings → App, phone and TV): re-downloads the
     *  backup-session pool and persists it, for a device whose local pool is empty or stale. See
     *  [com.arkiv.player.data.credentials.SeedRefresher]. */
    val seedRefresher: com.arkiv.player.data.credentials.SeedRefresher by lazy {
        com.arkiv.player.data.credentials.SeedRefresher(credentialsActivator, credentialsStore)
    }

    /**
     * `OkHttpClient` for the Magis portal (Task 9, sub-project 2B): with the accounts subsystem
     * gone -`InterceptorDeSesion`, which used to hang here to close the person's session on a real
     * 401/403, left along with the rest of `pocketbase/`- this client is down to a single user,
     * [magisPortal].
     *
     * `callTimeout` of 45s: CAP ON THE WHOLE CALL, not the socket -OkHttp's individual timeouts
     * reset with every byte that arrives, so a response that trickles in would never expire-.
     * [magisPortal] builds on top of this same client (same connection pool) a more patient
     * `readTimeout`, 25s, because the portal takes up to ~11s to resolve some channels (measured)
     * and OkHttp's default read timeout is 10 -it was killing it right before it landed-.
     */
    val portalHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .dns(com.arkiv.player.data.net.DohDns)
            .build()
    }

    // --- Direct Magis (sub-project 2A) --------------------------------------------------------
    //
    // The whole portal protocol lives in `data/magis`. The device's `sn` comes out of the store on
    // EVERY call (never captured): `MagisSession` mints it on the fly the first time, and that same
    // activation's body already has to carry it.

    internal val magisStore: com.arkiv.player.data.magis.MagisCredentialStore by lazy {
        com.arkiv.player.data.magis.EncryptedMagisCredentialStore(appContext)
    }

    private val magisPortal: com.arkiv.player.data.magis.MagisPortalClientLike by lazy {
        val creds = credentialsStore.read()!! // never null here: nothing reaching magisPortal is reachable before activation
        com.arkiv.player.data.magis.MagisPortalClient(
            // The 3DES key stays native: MagisCrypto hands the (encrypted) activation blob to the
            // native layer, which re-resolves the key into its own memory. No plaintext key here.
            crypto = com.arkiv.player.data.magis.MagisCrypto(creds.activationBlob),
            hosts = creds.iptvHosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
            appId = creds.iptvAppId,
            apkVersion = creds.iptvApkVersion,
            snProvider = { magisStore.readSession()?.sn.orEmpty() },
            // PATIENT: the portal takes ~11s to resolve some channels (measured) and OkHttp's
            // default read timeout is 10, i.e. it was killing them right before they landed.
            // `newBuilder()` and not a new client: shares the connection pool with the rest of the
            // calls to the portal.
            http = portalHttp.newBuilder()
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }

    /** Prefs-only, like [roomSyncSource]/[syncApply]/[syncCursorStore]: touching it never forces
     *  the credential lazies (`magisPortal`/`magisSession`/...), so the account screens can read
     *  [regionGeoBlocked] before activation without crashing. */
    internal val magisRegionBlockStore: com.arkiv.player.data.magis.MagisRegionBlockStore by lazy {
        com.arkiv.player.data.magis.SharedPrefsMagisRegionBlockStore(appContext)
    }

    /**
     * Failure-driven, persisted: true once THIS device has actually seen the Magis portal's
     * geo-block (`portal100024`) on some call -- never guessed from SIM/locale, no country list
     * anywhere (the earlier SIM/locale heuristic, `CountryDetector.isArgentina`, was removed). The
     * account screens (`AccountSection`, `TvSettingsAccount`, `MagisLinkOffer`/`TvMagisLinkOffer`'s
     * choice step) read it to hide offering a new account where it would just error -- the backup
     * seed pool already covers anonymous playback for that region.
     * [com.arkiv.player.data.magis.MagisSession] is what writes it, through [magisRegionBlockStore]
     * (`markBlocked`/`markClear` -- see that KDoc for exactly when each fires: this is UI-only
     * now, [magisSession] itself always tries a direct mint first regardless of this flag).
     */
    val regionGeoBlocked: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy { magisRegionBlockStore.geoBlocked }

    internal val magisSession: com.arkiv.player.data.magis.MagisSession by lazy {
        com.arkiv.player.data.magis.MagisSession(
            portal = magisPortal,
            store = magisStore,
            // Read LIVE from the store on every fallback (not captured once): a "Sembrar semillas"
            // re-seed persists a freshly downloaded pool to the store, and this is what makes that
            // take effect without restarting the app -- see SeedRefresher's KDoc.
            backupProvider = {
                credentialsStore.read()?.backupSessions?.map {
                    com.arkiv.player.data.magis.StoredSession(
                        userId = it.userId,
                        userToken = it.userToken,
                        jwtToken = "",
                        sn = it.sn,
                    )
                } ?: emptyList()
            },
            regionBlock = magisRegionBlockStore,
            // Dead-seed rescue: re-download the pool from archive.org and persist it (same op as the
            // "Sembrar semillas" button), so [backupProvider] then returns fresh seeds. No restart.
            refreshBackupPool = {
                seedRefresher.reseed() is com.arkiv.player.data.credentials.SeedResult.Ok
            },
        )
    }

    /** `account`, `seed` or `own`: see [MagisSession.sessionKind]. Telemetry only. */
    fun magisSessionKind(): String = runCatching { magisSession.sessionKind() }.getOrDefault("unknown")

    /** True when the published seed pool is exhausted for a device that needs seeds (every fresh
     *  seed came back dead too). The home shows a "wait for new seeds" note. */
    val seedsExhausted: kotlinx.coroutines.flow.StateFlow<Boolean> get() = magisSession.seedsExhausted

    /**
     * Periodic seed refresh, run by [com.arkiv.player.data.update.UpdateWorker] every 3h. Re-downloads
     * the backup pool ONLY for a device that has actually needed seeds ([regionGeoBlocked] -- it hit a
     * geo-block or a dead anonymous session) AND hasn't turned auto-refresh off in Ajustes. A device
     * that mints its own session never downloads seeds. The manual "Sembrar semillas" button is
     * unaffected and always available.
     */
    suspend fun refreshSeedsIfNeeded() {
        if (!regionGeoBlocked.value || !settings.seedAutoRefreshEnabled.value) return
        if (magisSession.hasAccountLinked) return // account sessions never use the backup pool
        seedRefresher.reseed()
    }

    /**
     * Force the heavy credential/Magis lazies to initialize OFF the main thread. [magisPortal]'s
     * initializer AES-decrypts the credentials store AND constructs [MagisCrypto], which eagerly
     * runs the native 3DES key resolution ([NativeCredentialResolver.magisActivate]) -- and the
     * O-MVLL VM makes that DES kernel slow (seconds). A Composable that reads `graph.liveCatalog` /
     * `contentSource` / `magisAccount` in its body triggers that whole chain on the UI thread, which
     * ANRs. Called once at startup on Dispatchers.IO (see [ArkivApp.onCreate]); a no-op before
     * activation (nothing reaching [magisPortal] is reachable then, and its `!!` would NPE).
     */
    private val _warmedUp = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Flips true once [warmUpCredentials] has finished building (or given up on) the heavy chain.
     *  The startup splash waits for this before composing the home, so a slow device never blocks
     *  the main thread on a half-built `by lazy` (the ANRs seen on weak phones / TV boxes). Fresh
     *  installs flip it immediately (nothing to warm -> the activation screen shows at once). */
    val warmedUp: kotlinx.coroutines.flow.StateFlow<Boolean> = _warmedUp

    private val _pickerDecisionReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    /**
     * Flips true once the roots may decide whether the mandatory source picker opens
     * (`Onboarding.opensPickerWhenReady`): right after the onboarding kind is recorded, or, for an
     * updating device, right after its Xuper migration step
     * ([com.arkiv.player.data.onboarding.Onboarding.decisionWaitsForMigration]).
     * Never waits for the rest of warm-up, and flips in its `finally` too, so it can never hang.
     */
    val pickerDecisionReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _pickerDecisionReady

    suspend fun warmUpCredentials() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var built = false
        try {
            val activated = credentialsStore.read() != null
            // Recorded once, on this build's first start, BEFORE the early return below: a device that was
            // already activated is an updating one (keeps the Xuper migration, never sees the source
            // picker); anything else is new. See data/onboarding/Onboarding.
            // Must stay BEFORE the fresh-install early return (ruling R11: the kind is decided from
            // whether credentials existed on the first start of this build).
            runCatching { com.arkiv.player.data.onboarding.Onboarding.classifyOnce(settings, activated) }
            if (!com.arkiv.player.data.onboarding.Onboarding.decisionWaitsForMigration(settings.onboardingKind)) {
                _pickerDecisionReady.value = true
            }
            if (!activated) return // fresh install: nothing to warm
            magisPortal   // -> MagisCrypto(...) -> NativeCredentialResolver.magisActivate (the slow part)
            magisSession  // depends on magisPortal + magisStore; warm it too
            // Also pre-build every heavy lazy a screen's ViewModel factory reads on the MAIN thread
            // during composition (HomeScreen/LibraryScreen/TvHome build with magisHomeCatalog +
            // repository; Live/Search read liveCatalog/contentSource; the account prompt reads
            // magisAccount). Front-loading them here on IO means composition hits fully-built lazies
            // instead of triggering this chain -- and blocking on it -- on the UI thread.
            tmdbApi
            repository
            liveCatalog
            magisHomeCatalog
            // Before `contentSource` (which forces pluginRegistry's first reload()): see
            // reconcilePluginSecrets's KDoc for why this order matters. The explicit reload() right
            // after (fix round 3, "also fold in") picks up the just-reconciled config.json even if
            // `pluginRegistry` had ALREADY been touched earlier (its lazy only reloads once, on
            // first access) -- without it, an early access from elsewhere could win the race and
            // this reconciliation would sit unread until some LATER reload().
            // Guarded: a plugin Keystore failure must never skip Xuper's lazies below.
            runCatching { reconcilePluginSecrets() }
                .onFailure { android.util.Log.w("KinoPlugin", "warm-up: plugin secrets not reconciled: ${it.javaClass.simpleName}") }
            pluginRegistry.reload()
            // One-time migration (Task 12), for UPDATING devices only (the onboarding kind recorded
            // above is LEGACY): a person activated before this app version shipped never "installed"
            // anything -- Xuper was simply always there. A new device picks its own sources instead.
            // Guarded exactly like reconcilePluginSecrets() above: no network right now (or GitHub
            // unreachable) must never abort the rest of warm-up, and this isn't gated behind a "did we
            // already try" flag, so the next cold start just tries again. An unrecorded kind (its
            // write failed) skips it this start only: the next start records it and runs it.
            // See autoInstallXuperPluginIfNeeded.
            runCatching {
                autoInstallXuperPluginIfNeeded(
                    com.arkiv.player.data.onboarding.Onboarding.runsXuperMigration(settings.onboardingKind),
                    credentialsStore, pluginRegistry, pluginAdmin,
                )
            }
                .onFailure { android.util.Log.w("KinoPlugin", "warm-up: Xuper plugin not auto-installed: ${it.javaClass.simpleName}") }
            // The migration step ran (or failed): an updating device's picker decision may be made now.
            _pickerDecisionReady.value = true
            // Start the live gate's watcher now (it's eager but lazily built): from here on,
            // switching the Xuper plugin off drops the live sessions even before a screen reads it.
            xuperLive
            // The En vivo module's provider list follows the registry from here on too.
            liveModule
            contentSource
            magisAccount
            built = true
        } catch (_: Throwable) {
            // The UI must never hang on a warm-up failure; it will retry the chain on demand.
        } finally {
            _pickerDecisionReady.value = true
            _warmedUp.value = true
            // Telemetry: a warm-up this slow is what makes a weak device risk an ANR at startup
            // (the splash waits for it -- see MainActivity). Report the duration so we can see it.
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (built && ms >= SLOW_WARMUP_MS) {
                // Constant message, the duration as an extra: with the number in the message every
                // distinct value was its own GlitchTip issue (80 of the board's latest 100 were these).
                com.arkiv.player.crash.Crash.report(
                    com.arkiv.player.crash.SlowStartup("credential/Magis warm-up slow"),
                    "slow-startup",
                    extras = mapOf("duration_ms" to ms.toString(), "model" to android.os.Build.MODEL),
                )
            }
        }
    }

    /**
     * The link with Magis as seen from "Settings → Account" (phone and TV) and the prompt on
     * entering the TV (Task 8, sub-project 2B): all three screens stopped using `AccountManager`
     * for this -it no longer depends on any Kino session, see the KDoc on
     * [com.arkiv.player.data.magis.MagisAccount]-. `AccountManager` itself was deleted entirely in
     * Task 9 (sub-project 2B), along with the rest of the accounts subsystem.
     */
    internal val magisAccount: com.arkiv.player.data.magis.MagisAccount by lazy {
        val creds = credentialsStore.read()!! // never null here: nothing reaching magisAccount is reachable before activation
        com.arkiv.player.data.magis.MagisAccount(magisSession, creds.fallbackMagisEmail, creds.fallbackMagisPassword)
    }

    private val magisCatalog: com.arkiv.player.data.magis.MagisCatalog by lazy {
        com.arkiv.player.data.magis.MagisCatalog(magisPortal, magisSession)
    }

    /** Magis playback resolution for [magisPluginBridge] (one CDN-info cache). */
    private val magisResolve: com.arkiv.player.data.magis.MagisResolve by lazy {
        val creds = credentialsStore.read()!!
        com.arkiv.player.data.magis.MagisResolve(
            magisPortal, magisSession,
            appId = creds.iptvAppId,
            apkVersion = creds.iptvApkVersion,
        )
    }

    /**
     * The Magis side of the privileged `kino.xuper.*` host functions (see `DefaultPrivilegedXuperHost`),
     * over the portal's catalog, resolver and TMDB. One per process, so its caches outlive a
     * plugin runtime's idle close. A plain [Lazy], not a `by lazy` property: it is handed to every
     * plugin runtime's host selection, and only the privileged host ever reads it.
     */
    private val magisPluginBridge: Lazy<com.arkiv.player.data.magis.MagisPluginBridge> = lazy {
        com.arkiv.player.data.magis.MagisPluginBridge(
            catalog = magisCatalog,
            vodResolver = magisResolve,
            tmdb = tmdbApi,
            vodStore = com.arkiv.player.data.magis.VodSearchStore(database.vodSearchCacheDao()),
            streams = xuperStreams,
            homeCatalog = magisHomeCatalog,
        )
    }

    /**
     * The streams [magisPluginBridge] resolved, with their CDN headers kept out of the plugin's
     * script: written by the bridge, read by the Xuper plugin's [PluginContentSource] (and only
     * its: see [XuperStreams]). Cheap and credential-free, so not lazy.
     */
    private val xuperStreams = XuperStreams()

    // --- Direct Caracol (Ditu) -----------------------------------------------------------------
    //
    // The whole Caracol protocol lives in `data/ditu`. No account or session: the free content is
    // requested and served as-is (see the KDoc on `DituClient`).

    private val dituClient: com.arkiv.player.data.ditu.DituClientLike by lazy {
        com.arkiv.player.data.ditu.DituClient()
    }

    /** Caracol as a title source. `internal` in addition to being inside [contentSource]:
     *  the channels and the full catalog aren't part of the common contract. */
    internal val dituSource: com.arkiv.player.data.ditu.DituSource by lazy {
        com.arkiv.player.data.ditu.DituSource(
            catalog = com.arkiv.player.data.ditu.DituCatalog(dituClient),
            episodes = com.arkiv.player.data.ditu.DituEpisodes(dituClient),
            resolver = com.arkiv.player.data.ditu.DituResolve(dituClient),
            tmdb = tmdbApi,
        )
    }

    /**
     * Where the titles the app searches and plays come from: Caracol and the installed plugins
     * (Xuper among them) behind a single object. To resolve and list episodes it dispatches by
     * `ref` (each source recognizes its own); to search, it merges them. See
     * [com.arkiv.player.data.gateway.CompositeSource]. Xuper refs saved before Xuper became a
     * plugin are claimed by [LegacyXuperRefSource] and forwarded to the Xuper plugin.
     * Every usable installed plugin is read on EACH call: installing, disabling or
     * uninstalling a plugin applies to the next search/resolve with no restart. A ref of a plugin
     * that isn't usable falls through to [UnusablePluginSource], last, which answers with the
     * registry's reason ("Activa el plugin X…") instead of "no source can open this".
     */
    val contentSource: com.arkiv.player.data.gateway.ContentSource by lazy {
        val unusablePlugins = UnusablePluginSource(pluginRegistry)
        com.arkiv.player.data.gateway.CompositeSource {
            val pluginSources = pluginRegistry.usable().map { pluginContentSource(it) } +
                unusablePlugins
            // Xuper refs saved before Xuper became a plugin (`magis1:`), forwarded to the installed
            // Xuper plugin through the SAME plugin sources below. In MagisSource's old slot: first.
            val legacyXuper = LegacyXuperRefSource(
                pluginRegistry.plugins.value,
                com.arkiv.player.data.gateway.CompositeSource(pluginSources),
            )
            listOf(legacyXuper, dituSource) + pluginSources
        }
    }

    /**
     * Whether the INSTALLED plugin with manifest id [pluginId] is the recognized Xuper install
     * ([XuperPrivilege.grants] on its record, never the id itself). Its titles download through
     * their own privileged strategy (`DownloadSource.XUPER`): see `DownloadSource.sourceFor`.
     */
    fun isXuperPlugin(pluginId: String): Boolean = pluginRegistry.isXuper(pluginId)

    /**
     * Whether the INSTALLED plugin [pluginId] declared the `download` capability (apiVersion 2) and
     * is usable right now (`PluginRegistry.offersDownloads`): its titles get the download button and
     * the generic plugin strategy (`DownloadSource.PLUGIN_DOWNLOAD`). Asked after [isXuperPlugin].
     */
    fun pluginDownloads(pluginId: String): Boolean = pluginRegistry.offersDownloads(pluginId)

    // --- Plugins (docs/superpowers/specs/2026-09-24-plugin-sources-design.md) ---

    val pluginStore: PluginStore by lazy {
        PluginStore(java.io.File(appContext.filesDir, "plugins"), java.io.File(appContext.filesDir, "plugin-data"))
            .also { it.cleanStaging() }
    }

    /** Base client for plugin traffic; each plugin derives its own (cookie jar, host gate) in PluginHttp. */
    private val pluginBaseHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            // raw.githubusercontent.com (install, updates, catalog art) resolves like every other host.
            .dns(com.arkiv.player.data.net.DohDns)
            .build()
    }

    /**
     * Each plugin's settings: `config.json` in its data dir, passwords in the Keystore-backed
     * [EncryptedSecretStore] (opened lazily, off the main thread). See PluginConfigStore.
     */
    val pluginConfigStore: PluginConfigStore by lazy {
        PluginConfigStore({ id -> pluginStore.dataDir(id) }, EncryptedSecretStore(appContext))
    }

    /** Reads each plugin's `config.json` (never the Keystore) for its typed servers and "Falta configurar". */
    val pluginRegistry: PluginRegistry by lazy {
        PluginRegistry(pluginStore) { p -> pluginConfigStore.setupState(p.manifest.id, p.manifest.settings) }.also { it.reload() }
    }

    /** Bridges a plugin's undeclared-host prompt to the dialog `MainActivity` collects from [HostApprovalCenter.pending]. */
    val hostApprovalCenter: HostApprovalCenter by lazy { HostApprovalCenter() }

    /** The X25519 step of opening plugin seals ([SealedSecrets]); the private key stays native. */
    val sealAgreement: X25519Agreement by lazy { com.arkiv.player.data.credentials.NativeSealAgreement }

    /**
     * The ONLY place `reload()`'s Keystore correctness (finding 5) actually touches the Keystore
     * (fix round 2, new breakage 1): `warmUpCredentials()` calls this on IO, BEFORE `contentSource`
     * or `pluginRegistry` are touched (both force `pluginRegistry`'s first `reload()`), so that
     * first reload -- and every one after it, from ANY thread, `reload()` itself never does
     * Keystore IO -- already sees `config.json`'s "secrets" lists telling the truth. Also run once
     * per [checkPluginUpdates] cycle, so a Keystore loss mid-session (not just across a restart)
     * eventually self-corrects too. See `PluginConfigStore.reconcileSecrets`'s KDoc.
     */
    private fun reconcilePluginSecrets() {
        pluginConfigStore.reconcileAllSecrets(pluginStore.list().map { it.manifest.id to it.manifest.settings }) { id, e ->
            android.util.Log.w("KinoPlugin", "secrets of $id not reconciled: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The player's client for one plugin stream, gated to [hosts] (the approved ones plus the
     * servers typed in its settings, carried in `PlayerData.pluginHosts`) on every request and
     * redirect hop. See PluginStreamHttp.
     *
     * [strictOrigins]: the side-loaded subtitle/audio URLs, gated strictly on every hop when [hosts]
     * are relaxed for a live channel (see PluginStreamHttp.client).
     *
     * [xuper] (`PlayerData.pluginXuper`, from `PluginAccess.Ready.xuper`: [XuperPrivilege.grants] on
     * the installed record) hands the gate the SAME [xuperStreams] the bridge writes and
     * [PluginContentSource] reads -- never a second table, or a URL accepted at resolve time would
     * be refused at playback. False for every other plugin: the gate is then exactly as before.
     */
    fun pluginStreamClient(
        hosts: EffectiveHosts,
        xuper: Boolean = false,
        strictOrigins: Collection<String> = emptySet(),
        /** The player's stream client only: that plugin's askable misses become `UndeclaredPlaybackHostException`. */
        askAboutFor: String? = null,
    ): okhttp3.OkHttpClient =
        PluginStreamHttp.client(
            pluginBaseHttp, hosts, xuper = xuperStreams.takeIf { xuper }, strictOrigins = strictOrigins, askAboutFor = askAboutFor,
            // Every server the player reaches only through the broad video permission: host only, once per stream.
            onAnyVideoHost = askAboutFor?.let { id -> { host -> android.util.Log.i("KinoPlugin", "[$id] player: $host allowed by the broad video permission") } },
        )

    /** The live PluginHttp of each open runtime, so the pool can reset its per-call request budget. */
    private val pluginHttps = java.util.concurrent.ConcurrentHashMap<String, PluginHttp>()

    /** The live cookie jar of each open runtime: a settings change retires it (see forgetPluginSession).
     *  See [PluginJarRegistry]'s KDoc for why a runtime's own close() must never touch this. */
    private val pluginJars = PluginJarRegistry()

    /**
     * Bumped by [forgetPluginHomeCache] AND once more, separately, right after a settings change's
     * runtime close actually removes the pool's slot (`afterSessionClosed`, wired into
     * [pluginAdmin]) — lets [pluginHomeRows] discard a `home()` answer that belongs to a session
     * already forgotten, even one that started before the forget and returns after both bumps (fix
     * round 1 finding 3, hardened in round 2: a single bump left a narrow gap a call could still
     * slip through — see [PluginAdmin.saveSettings]'s own comment for the exact race).
     *
     * This is DELIBERATELY separate from [InstalledPlugin.configRevision] (persisted, drives
     * [pluginsChanged] below AND, since fix round 3, also stamped into the Home cache file
     * alongside this value — see [PluginHomeRows]'s KDoc): that one exists so the registry's
     * `StateFlow` visibly changes on a save (finding 4) and so staleness is still caught after a
     * restart (finding 3a) — a value that resets every process start, like this one, can never do
     * either. This one exists to identify which RUNTIME INSTANCE a live call actually ran against,
     * which is inherently a live/in-memory question, not a disk one — process-lifetime only,
     * nothing here needs to (or should) survive a restart.
     */
    private val pluginSessionRevisions = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun pluginSessionRevision(id: String): Int = pluginSessionRevisions.getOrDefault(id, 0)
    private fun bumpPluginSessionRevision(id: String) { pluginSessionRevisions.merge(id, 1, Int::plus) }

    /**
     * Every plugin call goes through here: a plugin whose required settings are empty is refused
     * with `auth_required` before its runtime is even opened (spec §1.3).
     */
    val pluginCaller: PluginCaller by lazy {
        // Outermost, so every failed call is seen once (search, Home, browse, resolve, episodes, live):
        // PluginTelemetry decides what is worth the owner's error board.
        com.arkiv.player.data.plugin.ReportingPluginCaller(
            SetupGatedCaller({ id -> pluginRegistry.find(id)?.needsSetup == true }, pluginRuntimes),
        )
    }

    /** `owner/repo` (lowercase) of every recommended plugin, for [pluginFacts]' origin; read once, off the main thread. */
    @Volatile private var pluginCatalogRepos: Set<String>? = null

    private fun warmPluginCatalogRepos() {
        if (pluginCatalogRepos != null) return
        pluginCatalogRepos = runCatching { pluginCatalog.cachedOrSeed().catalog.entries.map { it.repo.lowercase() }.toSet() }.getOrNull()
    }

    /** What the error board may know about installed plugin [id] (see PluginFacts): memory only, safe on any thread. */
    private fun pluginFacts(id: String): com.arkiv.player.data.plugin.PluginFacts? {
        val p = pluginRegistry.find(id) ?: return null
        val r = p.record
        val repos = pluginCatalogRepos
        val origin = when {
            r.nuvioScraperId != null -> "nuvio"
            repos == null -> "unknown"
            PluginAddress.parse(r.address)?.let { "${it.owner}/${it.repo}".lowercase() in repos } == true -> "catalog"
            else -> "community_or_manual"
        }
        return com.arkiv.player.data.plugin.PluginFacts(
            version = r.version, apiVersion = p.manifest.apiVersion, origin = origin,
            nuvioRepo = r.nuvioRepo, nuvioScraperId = r.nuvioScraperId,
            // The person's own servers (URL settings) and every host they approved themselves at
            // playback (installed hosts the manifest never declared): never named in a report.
            privateHosts = p.userHosts.map { it.host }.toSet() + (r.hosts - p.manifest.hosts.toSet()),
        )
    }

    /** Every text the person typed into plugin [id]'s settings (the Keystore too): read in the background, only to be removed from a report. */
    private fun pluginSettingValues(id: String): List<String> {
        val p = pluginRegistry.find(id) ?: return emptyList()
        return pluginConfigStore.read(id, p.manifest.settings).values.values.flatMap { v ->
            when (v) {
                is String -> listOf(v)
                is Map<*, *> -> v.values.filterIsInstance<String>()
                is Iterable<*> -> v.flatMap { e -> if (e is Map<*, *>) e.values.filterIsInstance<String>() else listOfNotNull(e as? String) }
                else -> emptyList()
            }
        }
    }

    /** "Ver más" talks to one plugin directly; null when it isn't usable any more. */
    fun pluginSource(id: String): PluginContentSource? =
        pluginRegistry.find(id)?.takeIf { it.isUsable }?.let(::pluginContentSource)

    /**
     * [plugin]'s hosts as the registry holds them NOW, not when a source/row pass was built: a
     * plugin's output is checked right after its call returns, and a host the person approved
     * during that call (reactive approval -> `PluginRegistry.addApprovedHost`, written before the
     * call's retried `kino.fetch` goes out) must already count, or the stream/poster the call just
     * fetched from it would be dropped as undeclared.
     */
    private fun currentPluginHosts(plugin: InstalledPlugin): EffectiveHosts = pluginRegistry.find(plugin.id)?.hosts ?: plugin.hosts

    private fun pluginContentSource(plugin: InstalledPlugin) =
        PluginContentSource(
            plugin, pluginCaller, plugin.hosts, xuperStreams,
            currentHosts = { currentPluginHosts(plugin) },
            streamHostApproval = streamHostApproval,
            anyVideoHostGranted = { pluginRegistry.find(plugin.id)?.record?.videoFromAnyHost == true },
        )

    /**
     * Reactive host approval for a returned Stream's URLs (the player's resolve only, see
     * `InteractivePluginCall`) and for the hosts the player itself reaches while a plugin stream plays
     * (`PlayerViewModel.onPluginHostRefused`): the same dialog and registry writes (no cap) as
     * [openPluginRuntime]'s fetch-time approval, and the plugin's open runtime, if any, learns the
     * answer too.
     */
    val streamHostApproval: StreamHostApproval by lazy {
        StreamHostApproval(hostApprovalCenter, pluginRegistry, openRuntimeHttp = { id -> pluginHttps[id] })
    }

    val pluginRuntimes: PluginRuntimePool by lazy {
        PluginRuntimePool(
            open = { id -> openPluginRuntime(id) },
            // F5: on 3 consecutive timeouts the runtime discards itself internally (see
            // PluginRuntime.call/close KDoc) without the pool ever calling close() on it, so the
            // decorator below never runs for this path -- drop the stale PluginHttp here too.
            onUnresponsive = { id -> pluginRegistry.markUnresponsive(id); pluginHttps.remove(id) },
            scope = applicationScope,
            beforeCall = { id -> pluginHttps[id]?.beginCall() },
            // Same dir as PluginStore's data root: uninstall deletes the markers with the rest.
            sentinel = PluginCrashSentinel(java.io.File(appContext.filesDir, "plugin-data")),
        )
    }

    private suspend fun openPluginRuntime(id: String): ScriptRuntime {
        val plugin = pluginRegistry.find(id) ?: throw PluginScriptException("El plugin no está instalado")
        val script = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pluginStore.readVerifiedScript(id) }
        } catch (e: PluginDamagedException) {
            pluginRegistry.markDamaged(id)
            throw e
        }
        // The APPROVED hosts from installed.json (never the manifest's: they're what the person
        // accepted) plus the servers typed in its settings, as the registry read them -- ONE live
        // instance shared by the jar, kino.fetch and kino.cookies.get, so a host the person approves
        // mid-call (reactive approval) reaches all three at once (see LiveHosts).
        // A Nuvio plugin approved for fetchHosts "any" reaches any public host from kino.fetch (never
        // the player, a license or a download: those read the registry's hosts, not this instance).
        val hosts = LiveHosts(plugin.hosts.copy(anyPublicFetchHost = plugin.record.fetchFromAnyHost))
        val dataDir = pluginStore.dataDir(id)
        // Config (passwords from the Keystore) is read on IO, never on Main.
        val config = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pluginConfigStore.read(id, plugin.manifest.settings)
        }
        // The jar persists in the plugin's data dir: a login survives the idle close and app restarts.
        val cookies = PluginCookies(java.io.File(dataDir, PluginCookies.FILE_NAME), hosts)
        // Whatever jar this replaces is stopped from writing (see PluginJarRegistry's KDoc).
        pluginJars.put(id, cookies)
        // The runtime publishes its running call here; the http reads it to ask the person only
        // while someone waits on that call, with the call's clock paused (see PluginCall).
        val calls = PluginCallTracker()
        val http = PluginHttp(
            pluginBaseHttp, id, hosts, BuildConfig.VERSION_NAME, cookies = cookies,
            // The person's DNS setting (DoH by default), like the player's plugin client and every
            // other client of the app. With the system resolver kino.fetch lookups took 5 s on the
            // KALLEY R3 (measured: dns-ok@5032ms, @5087ms) and up to the whole 15 s request limit.
            delegateDns = com.arkiv.player.data.net.DohDns,
            reactiveApproval = PluginHttp.ReactiveApproval(
                pluginName = plugin.manifest.name,
                requester = hostApprovalCenter,
                onApproved = { host ->
                    if (!pluginRegistry.addApprovedHost(id, host)) {
                        android.util.Log.w("KinoPlugin", "[$id] approved host $host not saved: already there")
                    }
                },
                onRejected = { host -> pluginRegistry.rejectHost(id, host) },
                rejectedHosts = plugin.record.rejectedHosts.toSet(),
            ),
            calls = calls,
            maxRequestsPerCall = PluginHttp.requestBudgetFor(plugin.record),
        )
        pluginHttps[id] = http
        val storage = PluginStorage(java.io.File(dataDir, "storage.json"))
        // Only the one recognized Xuper source gets the extra kino.xuper.* host functions -- see
        // pluginHostFor's KDoc and XuperPrivilege.grants for the gate itself.
        // Sealed secrets (a manifest's `secrets`): markers for kino.secret, opened in this runtime
        // when a request first carries one or the first text is redacted, sent only toward the
        // manifest's own hosts (PluginSecrets); none at all for an address with an explicit @ref.
        val secrets = pluginSecretsFor(plugin, sealAgreement)
        val host = pluginHostFor(plugin, http, storage, config, cookies, magisPluginBridge, secrets)
        val runtime = PluginRuntime.open(id, script, host, PluginEnv(appVersion = BuildConfig.VERSION_NAME), calls)
        // F5: drop this plugin's PluginHttp the moment its runtime is closed -- idle timeout, or an
        // explicit pool.close() from DefaultPluginAdmin's disable/update/uninstall -- so pluginHttps
        // never keeps a stale, no-longer-approved host list around after the runtime that used it is
        // gone. remove(id, http) only clears OUR entry: if a newer runtime already replaced it, this
        // deferred close (see PluginRuntime.close KDoc) must not delete that live one instead.
        //
        // pluginJars is deliberately NOT touched here (fix round 1, finding 1): it used to also do
        // `pluginJars.remove(id, cookies)`, which raced forgetPluginSession -- an async close that
        // won that race removed the jar WITHOUT retiring it, leaving it free to write the old
        // session's cookies back after "forget" had already deleted them. See PluginJarRegistry's
        // KDoc: only put() (superseded) or forget() (a settings change) may ever remove an entry.
        return object : ScriptRuntime by runtime {
            override fun close() {
                runtime.close()
                pluginHttps.remove(id, http)
            }
        }
    }

    /**
     * Debug builds only: `src/debug`'s PluginSideloadProbe points this at plugin folders copied
     * into the app's files dir, so the emulator can install a plugin that isn't on GitHub yet.
     * Nothing in `src/main` ever sets it; a release APK has no code that can.
     */
    @Volatile var debugPluginFetcher: PluginFetcher? = null

    val pluginInstaller: PluginInstaller by lazy {
        val github = RawGithubFetcher(pluginBaseHttp)
        PluginInstaller(
            store = pluginStore,
            fetcher = PluginFetcher { url, max -> (debugPluginFetcher ?: github).fetch(url, max) },
            probe = { script ->
                val runtime = PluginRuntime.open("probe", script, ProbePluginHost, PluginEnv(appVersion = BuildConfig.VERSION_NAME))
                try { runtime.exports } finally { runtime.close() }
            },
            sealAgreement = sealAgreement,
        )
    }

    /** Nuvio-origin plugins' preview/install/update (Task 5): same fetcher wiring as [pluginInstaller], TMDB key from the same place [tmdbApi] reads it. */
    val nuvioPluginInstaller: NuvioPluginInstaller by lazy {
        val github = RawGithubFetcher(pluginBaseHttp)
        NuvioPluginInstaller(
            pluginInstaller,
            PluginFetcher { url, max -> (debugPluginFetcher ?: github).fetch(url, max) },
            tmdbApiKey = credentialsStore.read()!!.tmdbApiKey,
        )
    }

    /** Routes [checkPluginUpdates] and the manual "Buscar actualizaciones" button by each plugin's origin (Task 6). */
    val pluginUpdateCoordinator: PluginUpdateCoordinator by lazy {
        PluginUpdateCoordinator(pluginStore, pluginInstaller, nuvioPluginInstaller)
    }

    /**
     * The recommended-plugins catalog. Its own plain client, NOT [pluginBaseHttp]: that one carries plugin
     * traffic; the catalog repository derives from this a redirect-following, time-bounded client of its own.
     */
    val pluginCatalog: com.arkiv.player.data.plugin.catalog.CatalogProvider by lazy {
        com.arkiv.player.data.plugin.catalog.CatalogRepository(
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            cacheFile = java.io.File(appContext.filesDir, "plugins/catalog.json"),
            seed = { appContext.assets.open("plugin-catalog-seed.json").bufferedReader().use { it.readText() } },
        )
    }

    /**
     * The art (colour and icon) each recommended plugin ships in its own repo, for the cards of the two
     * Plugins screens (phone and TV). ONE instance for the whole app: its per-repo de-duplication locks and its
     * negative cache live in the instance, so a second one would download the same icon twice. It reads
     * through the same client the installer's fetcher uses (raw.githubusercontent.com, no new host) but
     * NOT through the installer's fetcher itself, so [debugPluginFetcher] never redirects it. Its folder
     * is outside `plugins/`, so it can never collide with a plugin's id folder.
     */
    val catalogArt: com.arkiv.player.data.plugin.catalog.CatalogArtProvider by lazy {
        com.arkiv.player.data.plugin.catalog.CatalogArtRepository(
            fetcher = RawGithubFetcher(pluginBaseHttp),
            dir = java.io.File(appContext.filesDir, "plugin-catalog-art"),
        )
    }

    /**
     * Community plugins found on GitHub (topic `kino-plugin`) for Recomendados and "Elige tus fuentes".
     * ONE instance: its single-flight lock and its minute spacing live in it. The search goes through
     * its own gated client (see GithubApi); manifests through the installer's host, never
     * [debugPluginFetcher]. Its folder is outside `plugins/`, so it cannot collide with a plugin's id.
     */
    val pluginDiscovery: com.arkiv.player.data.plugin.discovery.PluginDiscoveryProvider by lazy {
        val discoveryHttp = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .dns(com.arkiv.player.data.net.DohDns)
            .build()
        com.arkiv.player.data.plugin.discovery.PluginDiscovery(
            transport = com.arkiv.player.data.plugin.discovery.OkHttpGithubTransport(
                discoveryHttp,
                userAgent = "Kino/${BuildConfig.VERSION_NAME}",
            ),
            fetcher = RawGithubFetcher(pluginBaseHttp),
            cacheFile = java.io.File(appContext.filesDir, "plugin-discovery/discovery.json"),
            // When GitHub cannot answer: the static list next to the catalog (same hosts, see CommunityListRepository).
            fallback = com.arkiv.player.data.plugin.discovery.CommunityListRepository(discoveryHttp),
        )
    }

    val pluginAdmin: PluginAdmin by lazy {
        DefaultPluginAdmin(
            pluginRegistry, pluginInstaller, pluginUpdateCoordinator, pluginRuntimes, pluginConfigStore,
            forgetHomeCache = ::forgetPluginHomeCache,
            forgetSession = ::forgetPluginSession,
            afterSessionClosed = ::bumpPluginSessionRevision,
            closeLive = { id -> liveModule.forget(id) },
            forgetLiveChannels = { id ->
                applicationScope.launch {
                    runCatching { database.liveChannelCacheDao().clearProvider(com.arkiv.player.data.gateway.LiveChannelKeys.pluginProvider(id)) }
                }
            },
        )
    }

    /**
     * After a settings change (spec §1.3), the Home-cache half: the session revision is bumped
     * (the FIRST of two -- see [pluginSessionRevisions]'s KDoc and
     * [DefaultPluginAdmin.saveSettings]) and `home.json` (rows of the old account) is deleted.
     * Split from [forgetPluginSession] and called FIRST, before `registry.reload()` (fix round 3,
     * "new breakage 1") -- see [DefaultPluginAdmin.saveSettings]'s own comment for exactly why:
     * unlike jar retirement, nothing about the Home cache needs the registry to have reloaded
     * first, and a re-fetch reload() itself can trigger (finding 4) must never be able to observe
     * the pre-forget state.
     */
    private fun forgetPluginHomeCache(id: String) {
        bumpPluginSessionRevision(id)
        java.io.File(pluginStore.dataDir(id), "home.json").delete()
    }

    /**
     * After a settings change (spec §1.3), the session/jar half: a new user or server must not
     * inherit the old session, so the live cookie jar is retired (a call still finishing can't
     * write it back) and its file deleted. Runs on IO (DefaultPluginAdmin.saveSettings) -- see
     * that method for why this must run AFTER `registry.reload()` but BEFORE the runtime is
     * actually closed.
     */
    private fun forgetPluginSession(id: String) {
        pluginJars.forget(id)
        java.io.File(pluginStore.dataDir(id), PluginCookies.FILE_NAME).delete()
    }

    val pluginHomeRows: PluginHomeRows by lazy {
        PluginHomeRows(
            plugins = { pluginRegistry.usable() },
            caller = pluginCaller,
            // In the plugin's data dir: uninstalling deletes it with the rest.
            cacheFileFor = { id -> java.io.File(pluginStore.dataDir(id), "home.json") },
            sessionRevision = ::pluginSessionRevision,
            currentHosts = ::currentPluginHosts,
        )
    }

    /**
     * Emits when the set (or versions, settings state or config revision) of usable plugins
     * changes: Home re-asks for rows then — right after a plugin is configured or its server
     * changes, AND after any OTHER settings save (e.g. only the user/password), via
     * `InstalledPlugin.configRevision` inside `changeKey()` (finding 4: a save that only changed
     * the account used to leave every other field bit-for-bit equal, so `registry.plugins` itself
     * never emitted and this never re-asked — see `InstalledPlugin`'s own KDoc for the root cause).
     */
    val pluginsChanged: kotlinx.coroutines.flow.Flow<List<Pair<String, String>>>
        get() = pluginRegistry.plugins
            .map { list -> list.filter { it.isUsable }.map { it.id to it.changeKey() } }
            .distinctUntilChanged()

    /** UpdateWorker's plugin step: each plugin at most once per 24 h, routed by origin; see PluginUpdateCoordinator.checkDueUpdates. */
    suspend fun checkPluginUpdates() {
        val outcomes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Catches a Keystore loss that happened mid-session too. Guarded: a failure here must
            // never cancel every plugin's update check.
            runCatching { reconcilePluginSecrets() }
                .onFailure { android.util.Log.w("KinoPlugin", "update check: plugin secrets not reconciled: ${it.javaClass.simpleName}") }
            pluginUpdateCoordinator.checkDueUpdates()
        }
        // Reload before closing, as in DefaultPluginAdmin: never a new script with the old hosts.
        pluginRegistry.reload()
        outcomes.filter { it.second is UpdateOutcome.Applied }.forEach { pluginRuntimes.close(it.first) }
    }

    internal val magisLive: com.arkiv.player.data.magis.MagisLive by lazy {
        com.arkiv.player.data.magis.MagisLive(
            magisPortal, magisSession,
            apkVersion = credentialsStore.read()!!.iptvApkVersion,
        )
    }

    /** Live categories and channels + the catalog's section tree, straight from the portal. */
    internal val liveCatalog: com.arkiv.player.data.magis.MagisLiveCatalog by lazy {
        com.arkiv.player.data.magis.MagisLiveCatalog(magisCatalog, magisPortal, magisSession)
    }

    /** The home's rows, from the Magis catalog (see [com.arkiv.player.ui.home.MagisHomeCatalog]).
     *  Backed by the persistent [com.arkiv.player.ui.home.HomeCatalogStore] so a cold start paints
     *  the last-known home instantly. */
    val magisHomeCatalog: com.arkiv.player.ui.home.MagisHomeCatalog by lazy {
        com.arkiv.player.ui.home.MagisHomeCatalog(
            tree = { root -> liveCatalog.tree(root) },
            store = com.arkiv.player.ui.home.HomeCatalogStore(database.homeCatalogCacheDao()),
            freshTree = { root -> liveCatalog.tree(root, force = true) },
        )
    }

    /**
     * Local HLS proxy for the live channel. Each segment's signature is computed ON THE DEVICE and
     * has no fallback: the fallback used to be asking the gateway for it, which doesn't exist on
     * this branch. If Magis ever changes the algorithm, it gets fixed by shipping an APK (it used
     * to get fixed by redeploying the server, which is exactly the dependency this branch removes).
     */
    val liveHlsProxy: com.arkiv.player.playback.LiveHlsProxy get() = liveHlsProxyLazy.value

    /** Kept as a [Lazy] so [closeXuperLive] can stop the proxy only if it was ever built. */
    private val liveHlsProxyLazy = lazy {
        com.arkiv.player.playback.LiveHlsProxy(
            com.arkiv.player.playback.LocalSignature(),
            // After an unrecoverable double 403 (expired session, no signature): invalidates THAT
            // channel's cached session so the next abrir()/precalentar() resolves against the
            // gateway again instead of reusing the one we already know is dead for up to 300s more.
            onSessionDead = { channel -> liveController.invalidate(channel) },
            onPlaylistConflict = { channel, license -> onLiveConflict(channel, license) },
        )
    }

    /** Which seed each live channel resolves with after a conflict. In memory only: see [LiveSeedRotation]. */
    private val liveSeedRotation = com.arkiv.player.data.magis.LiveSeedRotation()

    /**
     * The CDN said `409 Conflict` to a channel's playlist: this session's license looks in use elsewhere.
     *
     * Only a shared seed can be in use twice by other devices, so only a device whose stored session IS a seed rotates:
     * never a linked account (its session is the person's own) and never an anonymous device that minted its own.
     * The stored session is not touched (films and series keep it); the channel just resolves with another seed from the
     * pool the next time it opens, which the reopen after the error does.
     */
    private fun onLiveConflict(channel: String, license: String) {
        val kind = magisSession.sessionKind()
        if (kind != "seed") {
            com.arkiv.player.playback.LiveLog.i("409 on $channel: session kind is '$kind', only a shared seed rotates")
            return
        }
        val before = liveSeedRotation.activeSeed(channel)?.sn
        val current = before ?: magisSession.currentSn()
        val pool = magisSession.seedPool()
        val moved = liveSeedRotation.onRefused(channel, current, pool, refusedKey = license)
        liveController.invalidate(channel)
        val after = liveSeedRotation.activeSeed(channel)?.sn
        // How much room is left to rotate: whether the pool is the limit (tiny, or every seed already refused) is what
        // the reports could not say before.
        val tried = liveSeedRotation.triedCount(channel)
        val budget = "pool=${pool.size} tried=$tried/${com.arkiv.player.data.magis.LiveSeedRotation.MAX_ROTATIONS + 1}"
        com.arkiv.player.playback.LiveLog.w(
            if (moved) "seed rotation: 409 on $channel → the next open uses another seed (${after?.take(6)}…) · $budget"
            else "seed rotation: 409 on $channel and no seed left to try → back to the device's own session · $budget",
        )
        // Telemetry only on a genuine state change: the player retries a stuck playlist for a while and every one
        // of those retries reaches this same 409 (deduped by license inside LiveSeedRotation), which would report
        // the SAME rotation again and again if this didn't check it actually moved.
        if (after == before) return
        com.arkiv.player.crash.Crash.report(
            com.arkiv.player.crash.LiveSeedRotated("live seed rotated after a conflict"),
            "live-seed-rotation",
            extras = mapOf(
                "channel" to channel,
                "outcome" to if (moved) "rotated" else "exhausted",
                "pool_size" to pool.size.toString(),
                "tried" to tried.toString(),
            ),
        )
    }

    /** Resolves a live channel with the seed it was rotated to, if any; a seed that cannot even resolve is skipped. */
    private suspend fun resolveLive(code: String): com.arkiv.player.data.gateway.LiveSession {
        var seed = liveSeedRotation.activeSeed(code) ?: return magisLive.resolveOrThrow(code)
        com.arkiv.player.playback.LiveLog.i(
            "seed rotation: opening $code with seed ${seed.sn.take(6)}… (tried ${liveSeedRotation.triedCount(code)})",
        )
        repeat(com.arkiv.player.data.magis.LiveSeedRotation.MAX_ROTATIONS + 1) {
            try {
                return magisLive.resolveOrThrow(code, seed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.arkiv.player.playback.LiveLog.w("seed rotation: seed ${seed.sn.take(6)}… could not resolve $code (${e.message}) → next")
                val before = seed.sn
                val moved = liveSeedRotation.onRefused(code, seed.sn, magisSession.seedPool(), refusedKey = "resolve:${seed.sn}")
                val next = liveSeedRotation.activeSeed(code)
                if (next?.sn != before) {
                    com.arkiv.player.crash.Crash.report(
                        com.arkiv.player.crash.LiveSeedRotated("live seed rotated after a conflict"),
                        "live-seed-rotation",
                        extras = mapOf("channel" to code, "outcome" to if (moved) "rotated_after_resolve_failure" else "exhausted"),
                    )
                }
                seed = next ?: return magisLive.resolveOrThrow(code)
                if (!moved) return magisLive.resolveOrThrow(code)
            }
        }
        return magisLive.resolveOrThrow(code)
    }

    /** Opens live channels: resolves against the portal and hands the player the URL from
     *  [liveHlsProxy]. */
    val liveController: com.arkiv.player.ui.live.LiveController get() = liveControllerLazy.value

    /** Kept as a [Lazy] so [closeXuperLive] only touches a controller that was ever built. The
     *  [XuperLiveGate] check is the hard stop: with the Xuper plugin off, no channel resolves
     *  (no portal call) and no cached session is handed out, whatever surface asked. */
    private val liveControllerLazy = lazy {
        com.arkiv.player.ui.live.LiveController(
            resolver = { code -> resolveLive(code) },
            urlFor = { session -> liveHlsProxy.urlFor(session) },
            gate = { XuperLiveGate.blockedMessage(pluginRegistry.plugins.value) },
        )
    }

    /**
     * Whether the native Xuper live channels are on ([xuperLiveAllowed]): the recognized Xuper
     * plugin is installed, enabled and not damaged. Every live surface (phone tab and Home row, TV
     * nav button, Home row and routes, the player's drawer) follows it without a restart, since
     * [PluginRegistry.plugins] re-emits on install/enable/disable/uninstall/update. Eager, and the
     * moment it turns false the resolved sessions are dropped and the proxy stops (a channel being
     * cast stops too); the player stops what's on screen itself (see `PlayerViewModel`).
     *
     * Favourites, recents and the channel cache stay in Room: re-enabling brings them all back.
     */
    val xuperLive: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        val registry = pluginRegistry
        val state = registry.plugins
            .map { xuperLiveAllowed(it) }
            .distinctUntilChanged()
            .stateIn(applicationScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, xuperLiveAllowed(registry.plugins.value))
        applicationScope.launch { state.collect { on -> if (!on) closeXuperLive() } }
        state
    }

    /**
     * The En vivo module (generic live TV, spec 2026-09-27): Xuper while [xuperLive] is on, plus
     * every plugin that declares `channels`. Every live surface reads it: phone tab and Home row,
     * TV nav button/guide/drawer, the player's zapping. Xuper's own lifecycle (`closeXuperLive`
     * when its gate closes) stays step 1's. A plugin provider is rebuilt (the old one closed,
     * its work cancelled) whenever the plugin's `changeKey()` changes.
     */
    val liveModule: com.arkiv.player.data.live.LiveCatalog by lazy {
        com.arkiv.player.data.live.LiveCatalog(
            plugins = pluginRegistry.plugins,
            scope = applicationScope,
            xuperProvider = { com.arkiv.player.data.live.XuperLiveProvider(liveCatalog, liveController) },
            pluginProvider = { p ->
                val provider = com.arkiv.player.data.gateway.LiveChannelKeys.pluginProvider(p.id)
                com.arkiv.player.data.live.PluginLiveProvider(
                    p, pluginCaller,
                    cached = { code -> database.liveChannelCacheDao().byCodes(provider, listOf(code)).firstOrNull() },
                    // Playlists/EPGs: the STRICT gate (declared hosts + typed servers), never liveStreamHosts "any".
                    fetcher = com.arkiv.player.data.live.PluginPlaylistFetcher(
                        com.arkiv.player.data.plugin.PluginStreamHttp.client(pluginBaseHttp, p.hosts),
                    ),
                    // Its live/ dir: uninstalling deletes it with the rest of the plugin's data (PluginStore.remove).
                    cacheDir = pluginStore.dataDir(p.id),
                    allCachesRoot = pluginStore.dataDir(p.id).parentFile,
                    syncCache = { rows -> database.liveChannelCacheDao().replacePlaylistRows(provider, rows) },
                    currentHosts = { currentPluginHosts(p) },
                )
            },
            ownProvider = {
                val root = java.io.File(appContext.cacheDir, "own-live")
                com.arkiv.player.data.live.OwnLiveProvider(
                    sources = { database.ownLiveSourceDao().all() },
                    // The gated stream client with the own-hosts policy: public hosts only, http or https,
                    // every redirect hop checked, a name that resolves into the LAN refused (PluginDns).
                    fetcher = com.arkiv.player.data.live.PluginPlaylistFetcher(
                        com.arkiv.player.data.plugin.PluginStreamHttp.client(pluginBaseHttp, com.arkiv.player.data.live.OwnLive.hosts),
                    ),
                    cacheDir = java.io.File(root, "own"),
                    allCachesRoot = root,
                    syncCache = { rows -> database.liveChannelCacheDao().replacePlaylistRows(com.arkiv.player.data.live.OwnLive.PROVIDER, rows) },
                )
            },
        )
    }

    /** "Probar" for the add dialog: the same gated client the own provider downloads with. */
    val ownProbe: suspend (com.arkiv.player.data.live.OwnSourceForm) -> com.arkiv.player.data.live.OwnProbe by lazy {
        val fetcher = com.arkiv.player.data.live.PluginPlaylistFetcher(
            com.arkiv.player.data.plugin.PluginStreamHttp.client(pluginBaseHttp, com.arkiv.player.data.live.OwnLive.hosts),
        )
        val probe: suspend (com.arkiv.player.data.live.OwnSourceForm) -> com.arkiv.player.data.live.OwnProbe = { f ->
            val headers = buildMap {
                if (f.userAgent.isNotEmpty()) put("User-Agent", f.userAgent)
                if (f.referer.isNotEmpty()) put("Referer", f.referer)
            }
            com.arkiv.player.data.live.OwnSourceProbe.run(f.kind, f.url.trim(), headers, fetcher)
        }
        probe
    }

    /**
     * Whether the person has ANY live source: a provider from Xuper or a plugin, or at least one channel or list
     * of their own. Home's empty state (and its "Agregar plugin" onboarding) depends on this and not on
     * `liveModule.available`, which "Mis canales" keeps true for everyone.
     */
    val hasLiveSources: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        kotlinx.coroutines.flow.combine(
            liveModule.hasSourceProviders,
            database.ownLiveSourceDao().flowAll().map { it.isNotEmpty() },
        ) { providers, own -> providers || own }
            .stateIn(applicationScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, liveModule.hasSourceProviders.value)
    }

    /**
     * The browsable Home rows of every plugin but Xuper, as Categorías tiles ([genreTilesOf]). Shared: the tab's
     * availability and its screens read this one flow, so the plugins' `home()` answers (cached 6 h) are asked once.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val genreTiles: kotlinx.coroutines.flow.StateFlow<List<com.arkiv.player.ui.home.GenreTile>> by lazy {
        pluginRegistry.plugins
            .flatMapLatest { plugins ->
                val xuperId = com.arkiv.player.ui.home.CategoriesViewModel.xuperPluginId(plugins)
                pluginHomeRows.rows().map { rows -> com.arkiv.player.ui.home.genreTilesOf(rows, xuperId) }
            }
            .stateIn(applicationScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }

    /** The person's own live sources ("Mis canales"). */
    val ownLiveStore: com.arkiv.player.data.live.OwnLiveStore by lazy {
        com.arkiv.player.data.live.OwnLiveStore(database.ownLiveSourceDao())
    }

    /** The message the player shows for a Xuper channel while [xuperLive] is off, else null. */
    val xuperLiveBlocked: kotlinx.coroutines.flow.StateFlow<String?> by lazy {
        val registry = pluginRegistry
        registry.plugins
            .map { XuperLiveGate.blockedMessage(it) }
            .distinctUntilChanged()
            .stateIn(applicationScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, XuperLiveGate.blockedMessage(registry.plugins.value))
    }

    private fun closeXuperLive() {
        if (liveControllerLazy.isInitialized()) runCatching { liveController.close() }
        if (liveHlsProxyLazy.isInitialized()) runCatching { liveHlsProxy.stop() }
    }

    val pendingUpdateStore: com.arkiv.player.data.update.PendingUpdateStore by lazy {
        com.arkiv.player.data.update.PendingUpdateStore(appContext)
    }

    // Staggered rollout: after an update is DETECTED we don't prompt right away. We wait a base delay
    // plus a random jitter -- rolled once at detection and persisted -- so a fleet of devices doesn't
    // all prompt (and download) at the same instant, and archive.org has time to finish publishing
    // the APK. The prompt then surfaces on this session's timer or on
    // the next app open, once that randomized time has passed.
    private val updateBaseDelayMs = java.util.concurrent.TimeUnit.HOURS.toMillis(1)
    private val updateJitterMs = java.util.concurrent.TimeUnit.HOURS.toMillis(6)

    /**
     * OTA check: called by [com.arkiv.player.data.update.UpdateWorker] and on app startup. Records a
     * newer version as a PENDING update (with its one-time staggered promote time) instead of
     * prompting immediately, then surfaces it via [promoteDueUpdate] if its time has already passed.
     * A failed check (no source reachable) changes nothing: the next run (every 3 h) tries again.
     */
    suspend fun checkForUpdate() {
        val result = updateChecker.check(BuildConfig.VERSION_CODE)
        if (result is com.arkiv.player.data.update.UpdateCheckResult.Available) {
            val pending = pendingUpdateStore.putIfNew(
                result.info, System.currentTimeMillis(), updateBaseDelayMs, updateJitterMs,
            )
            scheduleUpdatePromotion(pending)
        }
        promoteDueUpdate()
    }

    /**
     * Surfaces the pending update (sets [updateInfo], which the UI observes) if its staggered time
     * has passed and it wasn't dismissed. Clears a pending update the installed version already
     * caught up to. Safe offline -- reads only the local store.
     */
    fun promoteDueUpdate() {
        val pending = pendingUpdateStore.read() ?: return
        if (pending.info.versionCode <= BuildConfig.VERSION_CODE) {
            pendingUpdateStore.clear()
            return
        }
        if (pending.isDue(System.currentTimeMillis())) _updateInfo.value = pending.info
    }

    /**
     * While the app stays open (e.g. a TV left on), surface the pending update when its randomized
     * time arrives, without needing a reopen. Best-effort: process death before then is covered by
     * [promoteDueUpdate] on the next launch.
     */
    private fun scheduleUpdatePromotion(pending: com.arkiv.player.data.update.PendingUpdate) {
        if (pending.dismissed || pending.info.versionCode <= BuildConfig.VERSION_CODE) return
        val wait = pending.promoteAtMillis - System.currentTimeMillis()
        applicationScope.launch {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            promoteDueUpdate()
        }
    }

    /**
     * "Later": stop the automatic prompt for the current pending update and hide the dialog. The
     * update is not forgotten -- Settings' manual check still offers it.
     */
    fun dismissPendingUpdate() {
        pendingUpdateStore.markDismissed()
        _updateInfo.value = null
    }

    /**
     * Manual "check now" from Settings: returns a newer version immediately, BYPASSING the staggered
     * deferral (the person asked explicitly, so it shouldn't wait out the random delay). Does not
     * touch the pending-update state used by the automatic flow.
     */
    suspend fun checkForUpdateNow(): com.arkiv.player.data.update.UpdateCheckResult =
        updateChecker.check(BuildConfig.VERSION_CODE)

    /** Downloads [info]'s APK, falling back to the mirrors' copies of the same release (see [ApkDownloader]). */
    fun downloadUpdate(info: UpdateInfo): kotlinx.coroutines.flow.Flow<com.arkiv.player.data.update.DownloadState> =
        apkDownloader.download(info) { updateChecker.mirrorApkUrls(info) }

    /**
     * Silent periodic refresh (see [com.arkiv.player.data.update.UpdateWorker]): only re-applies
     * credentials for a device that already activated once -- never prompts, never activates a
     * fresh install on its own. Recovers a lost/corrupted local copy or a same-version blob fix;
     * does NOT survive an actual credential-value rotation (see the spec's "Consequence accepted"
     * section -- that needs a new app release).
     *
     * Re-downloads from the same archive item the device was activated with. A device activated
     * before activation codes existed has none stored, and skips the refresh.
     */
    suspend fun refreshCredentialsIfActivated() {
        val existing = credentialsStore.read() ?: return
        val code = existing.activationCode?.takeIf { it.isNotBlank() } ?: return
        // Blob-only refresh (recovers a corrupted or same-version-fixed blob). Keeps the existing
        // seed pool -- pool freshness is owned by the gated [refreshSeedsIfNeeded], so a device that
        // doesn't need seeds never downloads them here.
        val refreshed = credentialsActivator.activate(code, includeBackupPool = false) ?: return
        credentialsStore.save(refreshed.copy(backupSessions = existing.backupSessions))
    }

    // --- Downloads to the device itself (see docs/superpowers/specs/2026-08-07-...) ---

    /** The client every file download goes through; a plugin's download derives its gated client from it. */
    private val downloadHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            // HEADS UP: OkHttp's `readTimeout` is per socket read, not per whole request — it
            // only fires if this long passes without a SINGLE byte arriving. That's why a
            // multi-GB download that's crawling never gets cut off: every chunk that arrives
            // resets the clock. It used to be 0 (disabled), on the thinking that it protected
            // long downloads, but that also disables protection against a server that stops
            // sending data without closing the socket — the read hangs forever. Since the queue
            // processes one at a time, THAT hang doesn't jam a single download: it jams ALL of
            // them (the worker never returns, never re-queues). 60s works as a stall watchdog,
            // same idea as the stall cutoff `TorrentDownloadStrategy` used to have
            // (POLL_MS/STALL_TIMEOUT_MS) before it was removed in this branch's pruning,
            // without risking a legitimate download that's still coming in.
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val httpRangeDownloader: com.arkiv.player.data.local.HttpRangeDownloader by lazy {
        com.arkiv.player.data.local.HttpRangeDownloader(downloadHttp)
    }

    /**
     * The downloader a third-party plugin's file comes through: [downloadHttp] behind the same host
     * gate the player applies to that plugin's VOD stream (`PluginStreamHttp` over its `videoHosts`:
     * its approved hosts or typed servers, or any public host when the person granted the broad video
     * permission / approved `streamHosts: "any"`; every redirect hop checked, never the home network).
     * A plugin that vanished between the queue and the download gets an empty host list, which
     * refuses everything.
     */
    private fun pluginDownloaderFor(pluginId: String): com.arkiv.player.data.local.HttpRangeDownloader {
        val hosts = pluginRegistry.find(pluginId)?.videoHosts ?: com.arkiv.player.data.plugin.EffectiveHosts(emptyList())
        return com.arkiv.player.data.local.HttpRangeDownloader(
            com.arkiv.player.data.plugin.PluginStreamHttp.client(downloadHttp, hosts),
        )
    }

    val localDownloads: com.arkiv.player.data.local.LocalDownloadManager by lazy {
        com.arkiv.player.data.local.LocalDownloadManager(
            appContext, database,
            wakeWorker = { com.arkiv.player.data.local.LocalDownloadWorker.schedule(it) },
            restartWorker = { com.arkiv.player.data.local.LocalDownloadWorker.restart(it) },
            // By lambda: `downloadStrategies` needs `repository`, which gets built after this.
            // Evaluating it here would close the circle and blow up on startup.
            strategies = { downloadStrategies },
            // Downloads are never allowed on a TV (see `DownloadAvailability`).
            isTelevision = { com.arkiv.player.DeviceType.isTelevision(appContext) },
            isXuperPlugin = ::isXuperPlugin,
            pluginDownloads = ::pluginDownloads,
        )
    }

    /** What Kino takes up and the way to free it, for the Settings screens. See `AppStorage`. */
    val appStorage: com.arkiv.player.data.local.AppStorage by lazy {
        com.arkiv.player.data.local.AppStorage(
            appContext,
            downloadsDir = { localDownloads.targetDir() },
            // Through Coil's own API: deleting its files from under it would corrupt its journal.
            clearImageCaches = {
                coil.Coil.imageLoader(appContext).let { loader ->
                    loader.diskCache?.clear()
                    loader.memoryCache?.clear()
                }
            },
            clearRemux = { tsRemuxer.clear() },
            removeAllDownloads = { localDownloads.removeAll() },
        )
    }

    val localLibrary: com.arkiv.player.data.local.LocalLibrary by lazy {
        com.arkiv.player.data.local.LocalLibrary(database)
    }

    /**
     * On-disk folder for the frame JPEGs, a single point so that whoever writes
     * ([frameCapturer]) and whoever reads ([frameStore], from the repository) ALWAYS use the
     * same path.
     */
    private val framesDir: java.io.File by lazy { java.io.File(appContext.filesDir, "frames") }

    val frameStore: com.arkiv.player.thumbnails.FrameStore by lazy {
        com.arkiv.player.thumbnails.FrameStore(framesDir)
    }

    /** Best-effort capture of the frame currently playing, for each episode's thumbnail. */
    val frameCapturer: com.arkiv.player.thumbnails.FrameCapturer by lazy {
        com.arkiv.player.thumbnails.FrameCapturer(
            store = frameStore,
            dao = database.episodeFrameDao(),
            playbackDao = database.playbackDao(),
        )
    }

    /**
     * The only point that knows how to delete a frame (file + row), and a single instance for
     * everyone: it's passed by constructor to [repository] (manual toggle, progress at 60%, and
     * removing an item from the library) -it used to also go to `LibraryWiper` (logout), deleted in
     * Task 9 along with the rest of accounts- — same [frameStore], same `episodeFrameDao` as
     * [frameCapturer].
     */
    val frameDestroyer: com.arkiv.player.thumbnails.FrameDestroyer by lazy {
        com.arkiv.player.thumbnails.FrameDestroyer(frameStore, database.episodeFrameDao())
    }

    /** Serves the local file over HTTP so it can be cast (a file:// doesn't reach the Chromecast). */
    val localFileServer: com.arkiv.player.playback.LocalFileServer by lazy {
        com.arkiv.player.playback.LocalFileServer(lanIp = { lanIp() })
    }

    /**
     * Rewrites an MPEG-TS into an MP4 so the Cast receiver gets explicit per-sample timing instead
     * of having to derive it from PTS/DTS. Nothing is re-encoded. See [TsRemuxer].
     *
     * In `cacheDir` on purpose: a remux is a derived copy and Android may reclaim it, which is
     * exactly the right outcome -- it is rebuilt on demand and never holds the only copy of
     * anything.
     */
    /**
     * Serves the remuxed CHUNKS, several at once.
     *
     * [localFileServer] cannot: it holds one file and restarts its socket when that file changes.
     * A title cast as a queue needs every queued chunk reachable at the same time.
     */
    val chunkServer: com.arkiv.player.playback.ChunkServer by lazy {
        com.arkiv.player.playback.ChunkServer(
            folder = java.io.File(appContext.cacheDir, com.arkiv.player.playback.RemuxPolicy.FOLDER),
            lanIp = { lanIp() },
        )
    }

    val tsRemuxer: com.arkiv.player.playback.TsRemuxer by lazy {
        com.arkiv.player.playback.TsRemuxer(appContext, appContext.cacheDir, applicationScope)
    }

    /** The device's LAN IP (see [com.arkiv.player.playback.LanIp]): needed by the live channel
     *  proxy and the transcoded cast so a renderer on the LAN can reach them. */
    fun lanIp(): String? = com.arkiv.player.playback.LanIp.current(appContext)

    /**
     * One strategy per `source` in the `downloads` table. No entry for "web" on purpose: the web
     * source was deleted on this branch (branch rule, "zero self-hosted server") and
     * `NucStagedStrategy` (which only existed to serve it, talking to the NUC/arkiv-offline server)
     * was deleted in the NUC pruning (Task 8) — an old row with `source="web"` (from before this
     * branch) now fails gracefully instead of triggering that network call (see
     * `LocalDownloadWorker.doWork()`, which already treats a missing entry as "unsupported source").
     *
     * No entry for "archive" either: `ArchiveDownloadStrategy` was deleted along with the rest of
     * archive.org in this pruning (it called `ArchiveUrls.download`, direct network to archive.org —
     * against the branch rule). An old row with `source="archive"` falls into the same graceful path
     * as "web".
     *
     * Nor for "ditu": Caracol came back with a direct client (`data/ditu`), but its video comes
     * Widevine-encrypted and there's no way to download it; `DituDownloadStrategy` was deleted in
     * the pruning and never came back. A row with `source="ditu"` falls into the same graceful path.
     *
     * The screens don't offer downloading what has no entry here: that's decided by
     * `DownloadSource.canDownload`/`hasStrategy` using this map's keys.
     */
    val downloadStrategies: Map<String, com.arkiv.player.data.local.DownloadStrategy> by lazy {
        // One strategy for both Xuper keys: it resolves whatever ref the chapter saved through
        // `contentSource`, a legacy `magis1:` one (via `LegacyXuperRefSource`) or the plugin's `plg1:`.
        val xuper = com.arkiv.player.data.local.MagisDownloadStrategy(repository, contentSource, httpRangeDownloader)
        mapOf(
            "magis" to xuper,
            // The recognized Xuper plugin's chapters, and no other plugin's: `DownloadSource.sourceFor`
            // only maps a plugin episode here when `isXuperPlugin`, and the wrapper checks it again
            // when the download runs. Its path predates third-party downloads and stays as it was.
            com.arkiv.player.data.local.DownloadSource.XUPER to
                com.arkiv.player.data.local.XuperPluginDownloadStrategy(xuper, ::isXuperPlugin),
            // Any other plugin that declared the `download` capability (apiVersion 2), while it is
            // usable: `DownloadSource.sourceFor` maps its episodes here when `pluginDownloads`, and
            // the strategy re-checks that when the download runs. It resolves the saved ref through
            // the plugin's own `resolve()` (via `contentSource`) and saves the Stream as one file
            // with the Stream's headers, through the plugin's host-gated client. A plugin without
            // the capability stays on "plugin", which has no entry: no button, as before.
            com.arkiv.player.data.local.DownloadSource.PLUGIN_DOWNLOAD to
                com.arkiv.player.data.local.PluginDownloadStrategy(
                    refForEpisode = repository::magisRefForEpisode,
                    source = contentSource,
                    downloaderFor = ::pluginDownloaderFor,
                    offersDownloads = ::pluginDownloads,
                ),
            // Caracol. With this key present, `DownloadSource.canDownload` starts saying yes for
            // its episodes and the UI shows the button on its own -- that's exactly the contract
            // this documents: a source with no strategy stays hidden, one with a strategy shows up.
            "ditu" to com.arkiv.player.data.local.DituDownloadStrategy(
                repository, contentSource, caracolStore,
            ),
        )
    }

    /**
     * Where downloaded Caracol episodes live. Only one per process: `SimpleCache` won't let the
     * same folder be opened twice, and here it's shared between the download and the player.
     *
     * Hangs off the same directory as regular downloads so the free space `LocalDownloadManager`
     * measures is the same disk that actually fills up.
     */
    val caracolStore: com.arkiv.player.data.caracol.CaracolStore by lazy {
        com.arkiv.player.data.caracol.CaracolStore(
            appContext,
            java.io.File(localDownloads.targetDir(), "caracol"),
        )
    }

    val dlna: DlnaController by lazy {
        DlnaController(
            appContext,
            tsRemuxer = tsRemuxer,
            // Its OWN server, not [localFileServer]: that one is single-file and would have its
            // socket stolen out from under it by whichever of DLNA/Chromecast casts second.
            localFileServer = com.arkiv.player.playback.LocalFileServer(lanIp = { lanIp() }),
        )
    }

    /**
     * Phone<->TV LAN companion link (discovery/pairing/transport; `com.arkiv.player.companion`).
     * `by lazy`: nothing starts until the Connect screen calls `startHost()`/`startBrowsing()`.
     */
    val companion: CompanionManager by lazy { CompanionManager(appContext) }

    /**
     * Companion LAN sync (Task 7): DB/prefs-only, so touching these three never forces the
     * credential lazies above (`magisPortal`/`magisSession`/... all read `credentialsStore.read()!!`
     * and crash pre-activation) -- `wireCompanionLifecycle` relies on that to run before activation.
     */
    val roomSyncSource: com.arkiv.player.data.sync.RoomSyncSource by lazy {
        com.arkiv.player.data.sync.RoomSyncSource(database)
    }
    val syncApply: com.arkiv.player.data.sync.SyncApply by lazy {
        com.arkiv.player.data.sync.SyncApply(database)
    }
    val syncCursorStore: com.arkiv.player.data.sync.SyncCursorStore by lazy {
        com.arkiv.player.data.sync.SyncCursorStore(appContext)
    }

    val repository: ArkivRepository by lazy {
        ArkivRepository(
            database, tmdbApi,
            frameStore = frameStore,
            frameDestroyer = frameDestroyer,
        ).also { repo ->
            // "For you" only exists on the TV home: on the phone there's no row to fill, and every
            // generation pass asks Kilo several times.
            if (DeviceType.isTelevision(appContext)) {
                repo.onEpisodeFinished = { applicationScope.launch { forYouGenerator.generateIfDue() } }
            }
        }
    }
    val aniListApi: AniListApi by lazy { AniListApi() }
    val animeMappingRepository: AnimeMappingRepository by lazy {
        AnimeMappingRepository(cacheDir = appContext.filesDir)
    }
    /**
     * Task 9 (sub-project 2B): it used to take `httpGatewayCorto`, a client derived from
     * `httpGateway.newBuilder()` only to share its connection pool -and which for that reason
     * inherited its `callTimeout(45s)`-. Without that shared client (see [portalHttp], which is
     * now only for the Magis portal), `TmdbApi` goes back to its own default `OkHttpClient`, which
     * now also carries that same `callTimeout(45s)` -see its constructor's default- so as not to
     * lose it: TMDB is a DIFFERENT host, so sharing a pool with the portal never brought any real
     * benefit, but the cap on the whole call was still needed.
     */
    val tmdbApi: TmdbApi by lazy {
        TmdbApi(apiKey = credentialsStore.read()!!.tmdbApiKey, language = "es-MX")
    }
    val subtitlePrefs: com.arkiv.player.data.subtitles.SubtitlePrefs by lazy {
        com.arkiv.player.data.subtitles.SubtitlePrefs(appContext)
    }
    /**
     * Watches for network changes so [archiveCacheProxy] abandons connections that stayed tied to
     * the previous network. The reference is kept even though nobody uses it: the watchdog lives
     * as long as the process, same as the proxy, and keeping it on hand leaves the option to stop
     * it someday if needed. See [com.arkiv.player.playback.NetworkChange].
     */
    private var networkWatchdog: com.arkiv.player.playback.NetworkWatchdog? = null

    val archiveCacheProxy: com.arkiv.player.playback.ArchiveCacheProxy by lazy {
        com.arkiv.player.playback.ArchiveCacheProxy(
            java.io.File(appContext.cacheDir, com.arkiv.player.data.local.AppStorage.ARCHIVE_CACHE_DIR),
        )
            .also { proxy ->
                networkWatchdog = com.arkiv.player.playback.NetworkWatchdog(appContext) { reason ->
                    proxy.abandonConnections(reason)
                }.apply { start() }
            }
    }
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** The client for Kilo's free models (sub-project 4). No key: see its KDoc. */
    internal val aiClient: com.arkiv.player.data.ai.AiClient by lazy {
        com.arkiv.player.data.ai.AiClient(
            memory = com.arkiv.player.data.ai.ModelMemory(
                com.arkiv.player.data.ai.PreferencesStore(appContext),
            ) { System.currentTimeMillis() },
        )
    }

    /** Kinobot: the movie/series/anime chat, streaming on top of [aiClient]. */
    internal val kinobotClient: com.arkiv.player.data.ai.KinobotClient by lazy {
        com.arkiv.player.data.ai.KinobotClient { aiClient.streamChat(it) }
    }

    /** The player's fun fact (sub-project 4): Kilo, from the device, a month of caching. */
    internal val triviaFacts: com.arkiv.player.data.trivia.TriviaFacts by lazy {
        com.arkiv.player.data.trivia.TriviaFacts(
            ia = { aiClient.ask(it) },
            cache = com.arkiv.player.data.trivia.DiskTriviaCache(
                java.io.File(appContext.filesDir, "trivia-facts"),
            ) { System.currentTimeMillis() },
        )
    }

    /**
     * "For you", generated on the device with Kilo (sub-project 4). Verifies against TMDB and
     * against the composite source (Magis and Caracol). Every network step catches its own
     * failures so a broken candidate doesn't take down the others; cancellation is always rethrown.
     */
    internal val forYouGenerator: ForYouGenerator by lazy {
        val verification = ForYouVerification(
            tmdb = TmdbSearcher { type, title ->
                try {
                    tmdbApi.search(type, title).firstOrNull()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            },
            sources = SourceSearcher { title, type, _, tmdbId ->
                try {
                    contentSource
                        .search(com.arkiv.player.data.gateway.GatewaySearchQuery(q = title, type = type, tmdbId = tmdbId))
                        .filterIsInstance<com.arkiv.player.data.gateway.SearchEvent.ResultEvent>()
                        // The `kind` the source builds is the type that was SEARCHED FOR, not the
                        // item's own (see the KDoc on `withRealKind`): fixed here, before the
                        // referee sees the list.
                        .map { withRealKind(it.item) }
                        .take(25)
                        .toList()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            },
            referee = AiReferee { aiClient.ask(it) },
        )
        ForYouGenerator(
            ia = { aiClient.ask(it) },
            history = { HistorySignals.of(database.playbackDao().recentHistory(100)) },
            alreadySeen = {
                database.itemDao().getAllItems().filter { !it.deleted }.flatMap { item ->
                    listOfNotNull(
                        item.tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" },
                        NormalizeTitle.of(item.title).takeIf { it.isNotEmpty() },
                        item.tituloCanonico?.let { NormalizeTitle.of(it) }?.takeIf { it.isNotEmpty() },
                    )
                }.toSet()
            },
            verify = { candidates, seen -> verification.verify(candidates, seen) },
            save = { database.recommendationDao().replace(it, System.currentTimeMillis()) },
            hasActive = { database.recommendationDao().countActive() > 0 },
            readMarks = { settings.forYouLastAttemptMs to settings.forYouLastAttemptWasModelFailure },
            writeMarks = { t, f -> settings.markForYouAttempt(t, f) },
        )
    }

    /**
     * Adds to the library whatever is picked from the home's "For you" row. Needs
     * `contentSource` in addition to the repository: a series recommendation carries the
     * season's ref, and the episodes have to be requested from the portal (`MagisCatalog.detail`).
     */
    val recommendationAggregator by lazy {
        com.arkiv.player.data.recommendations.RecommendationAggregator(
            repo = repository,
            gateway = contentSource,
        )
    }

    private val newChapterFinder by lazy {
        com.arkiv.player.data.newcontent.NewChapterFinder(
            repo = repository,
            itemDao = database.itemDao(),
            gateway = contentSource,
        )
    }

    /**
     * Looks for new episodes of the series currently being watched, with a repeat-throttle.
     *
     * The throttle is needed because `Application.onCreate` runs many times a day —just leaving
     * the app and coming back does it— and every pass costs network (for web, one search per
     * candidate episode). Once every [HOURS_BETWEEN_SEARCHES] hours is more than enough: episodes
     * don't come out more often than that.
     */
    suspend fun lookForNewChapters() {
        // Before activation there are no credentials, so the search would blow up forcing
        // `repository`/`tmdbApi` -- and it would burn the throttle window below first, silently
        // skipping the first REAL search for up to HOURS_BETWEEN_SEARCHES hours after activating.
        if (credentialsStore.read() == null) return
        val prefs = appContext.getSharedPreferences("arkiv_nuevos", android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_SEARCH, 0L)
        val now = System.currentTimeMillis()
        if (now - last < HOURS_BETWEEN_SEARCHES * 60 * 60 * 1000L) return
        // Sealed BEFORE searching: if the search takes a while and the user closes and reopens the
        // app in the middle, two passes don't start stepping on each other against the same sources.
        prefs.edit().putLong(KEY_LAST_SEARCH, now).apply()
        newChapterFinder.findNewChapters()
    }

    /**
     * Signal to reset the catalog search when entering from another tab. Emitted by the click on
     * the "Catalog" tab (which only exists while on a tab, never inside a detail), so coming back
     * from a detail with "back" does NOT trigger it and the search is preserved.
     */
    val catalogResetSignal = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var _castContext: CastContext? = null
    @Volatile private var _castSession: com.arkiv.player.cast.CastSessionManager? = null
    private val castSessionLock = Any()

    /** Chromecast's CastContext, or null if Google Play Services isn't available OR the async init
     *  below hasn't finished yet. Never blocks: the heavy `getSharedInstance` runs off the main thread. */
    val castContext: CastContext? get() = _castContext

    /**
     * Owner of the CastPlayer with app-long lifetime (without it, leaving the player cuts the cast).
     * Built lazily on the MAIN THREAD -- CastPlayer/CastContext demand it -- the first time it's read
     * with a ready [castContext] and credentials present; null before that. UI reads this on the main
     * thread, so the construction lands there.
     */
    val castSession: com.arkiv.player.cast.CastSessionManager?
        get() {
            _castSession?.let { return it }
            val ctx = _castContext ?: return null
            if (credentialsStore.read() == null) return null
            return synchronized(castSessionLock) {
                _castSession ?: com.arkiv.player.cast.CastSessionManager(
                    ctx, repository, applicationScope,
                    keepAlive = { on, receiver ->
                        if (on) com.arkiv.player.dlna.DlnaCastService.start(appContext, receiver, chromecast = true)
                        else com.arkiv.player.dlna.DlnaCastService.stop(appContext)
                    },
                ).also { _castSession = it }
            }
        }

    init {
        // Cast init is offloaded to a background executor. `CastContext.getSharedInstance` is a heavy,
        // blocking Play-Services call; running it on the UI thread ANRs slow devices (measured on
        // 0.9.29: an ANR inside the player's composition, where PlayerScreen reads `graph.castContext`,
        // and this very startup post used to force it on the main thread too). The async overload does
        // the init off-thread; only the CastSessionManager/CastPlayer construction, which genuinely
        // requires the main thread, is posted back once the context is ready -- and ONLY once
        // credentials exist (pre-activation there's no live session to adopt, and forcing it would
        // pull `repository` -> `tmdbApi` -> `credentialsStore.read()!!`, null before activation).
        runCatching {
            CastContext.getSharedInstance(appContext, java.util.concurrent.Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx ->
                    _castContext = ctx
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (credentialsStore.read() != null) castSession // builds + adopts a live session
                    }
                }
                .addOnFailureListener {
                    // No Play Services / no Cast support on this device: stays null, exactly as before.
                }
        }
        // Plugin failures to the owner's GlitchTip (a no-op where Crash is: debug builds, no DSN).
        // Built and sent on IO: the facts touch the catalog file and the settings the Keystore.
        com.arkiv.player.data.plugin.PluginTelemetry.current = com.arkiv.player.data.plugin.PluginTelemetry(
            facts = ::pluginFacts,
            sink = { e ->
                com.arkiv.player.crash.Crash.report(
                    com.arkiv.player.crash.PluginFailed(e.message), "plugin-failure",
                    extras = e.extras, tags = e.tags, fingerprint = e.fingerprint,
                    // Telemetry, not a crash: never in the local crash store (it would evict real ones).
                    local = false,
                )
            },
            privateValues = ::pluginSettingValues,
            dispatch = { block ->
                applicationScope.launch {
                    warmPluginCatalogRepos()
                    block()
                }
            },
        )
        // Warmed now, not on the first plugin failure: until then the player's reports
        // (`PluginTelemetry.describe`) would all say `plugin_origin=unknown`.
        applicationScope.launch { warmPluginCatalogRepos() }
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        /** At most how often new episodes are looked for. See [lookForNewChapters]. */
        private const val HOURS_BETWEEN_SEARCHES = 6L
        private const val KEY_LAST_SEARCH = "ultima_busqueda_ms"

        /** Warm-up slower than this is reported as a startup-ANR risk (see [warmUpCredentials]). */
        private const val SLOW_WARMUP_MS = 4000L

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}

/**
 * One-time migration (Task 12, `docs/superpowers/specs/2026-09-25-xuper-privileged-plugin-design.md`): someone
 * who activated Xuper before this app version shipped never "installed" anything -- Xuper was
 * simply always there. Once `contentSource` stops special-casing it, their Home/Categorías/search
 * would go blank unless the plugin is installed for them, so this installs it once, silently, with
 * NO consent screen -- the one deliberate exception in the whole plugin system, since activating
 * Xuper already granted everything this plugin needs.
 *
 * "Already there" means present in [pluginRegistry] at all, REGARDLESS of enabled/damaged/
 * unresponsive status -- not [PluginRegistry.usable], which excludes a disabled plugin. A person
 * who deliberately disabled Xuper after this ran once must not have it silently re-previewed and
 * re-installed (a network round-trip) on every single cold start after that; [PluginInstaller]
 * would preserve their `enabled = false` either way (`install()` carries the previous record's
 * `enabled` forward), but repeating the fetch forever for no visible effect is exactly the kind of
 * silent cost this migration must not add. [XuperPrivilege.grants] is the one recognized comparison
 * (exact-string match on [InstalledRecord.address]; see its own KDoc for why nothing should
 * reproduce it) -- called against every installed record, not just an id-keyed lookup, since the
 * Xuper plugin's manifest `id` isn't known ahead of the network fetch [PluginAdmin.preview] does.
 *
 * [pluginAdmin]'s calls are left to throw straight into the caller's `runCatching` (see
 * [AppGraph.warmUpCredentials]): the manual "type an address" install flow
 * ([com.arkiv.player.ui.plugin.PluginsViewModel]) is the only other caller of
 * [PluginAdmin.preview]/[PluginAdmin.install], and it already treats every failure from them
 * (a bad manifest, no network, GitHub unreachable) as an ordinary [InstallException]/[IOException]
 * to show and move on from, never as something to special-case -- this migration does the same,
 * just with nothing to show, so the next cold start simply tries again.
 *
 * Fix round 1: an absent record isn't the only way "not already there" can be true -- a person
 * who explicitly uninstalled the auto-installed Xuper plugin also has no live record, but their
 * uninstall must stick, forever, exactly like it does for every other plugin ([PluginStore]'s
 * `removed.json` tombstone, checked here through [PluginRegistry.wasExplicitlyRemoved], is what
 * makes that stick even across [PluginInstaller.install]'s own `isUpdate=false` path, which has
 * no live record to compare against and so can't apply [PluginStore.finishInstall]'s own
 * `isUpdate` guard). The check has to happen AFTER [PluginAdmin.preview] fetches the manifest,
 * same as [XuperPrivilege.grants] above it: Xuper's real manifest `id` isn't known before that
 * fetch, so there's no id to look up a tombstone for any earlier. This still costs only the one
 * network call [preview] already made -- [install] is simply skipped when the tombstone is there.
 *
 * Xuper moved repos on 2026-09-28: a new install uses [XuperPrivilege.SOURCE_REPO]
 * (`xuper-plugin/kino-plugin-xuper`), while an install from the legacy `kinotvapp` address still counts as
 * "already there" ([XuperPrivilege.grants] accepts both), so nobody gets a second Xuper. The tombstone
 * is keyed by manifest id, which both repos share, so an uninstall stands whichever address it came from.
 *
 * [runsMigration] is `Onboarding.runsXuperMigration(kind)`: only a device that was already activated
 * before this build (an updating one) gets the migration; a new one chooses in "Elige tus fuentes",
 * where Xuper is found through its `kino-plugin` topic.
 */
suspend fun autoInstallXuperPluginIfNeeded(
    runsMigration: Boolean,
    credentialsStore: com.arkiv.player.data.credentials.RemoteCredentialsStore,
    pluginRegistry: PluginRegistry,
    pluginAdmin: PluginAdmin,
) {
    if (!runsMigration) return // a new device picks its own sources (spec 2026-09-28 §4)
    if (credentialsStore.read() == null) return // never activated: nothing to migrate
    val alreadyThere = pluginRegistry.plugins.value.any { XuperPrivilege.grants(it.record) }
    if (alreadyThere) return
    val preview = pluginAdmin.preview(XuperPrivilege.SOURCE_REPO)
    if (pluginRegistry.wasExplicitlyRemoved(preview.manifest.id)) return // person's own uninstall stands
    pluginAdmin.install(preview)
}
