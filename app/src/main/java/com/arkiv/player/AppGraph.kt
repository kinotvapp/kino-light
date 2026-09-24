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
import com.arkiv.player.dlna.DlnaController
import com.arkiv.player.companion.CompanionManager
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
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
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build())
    }

    private val _updateInfo = kotlinx.coroutines.flow.MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: kotlinx.coroutines.flow.StateFlow<UpdateInfo?> = _updateInfo

    private val _hasInternet = kotlinx.coroutines.flow.MutableStateFlow(true)
    val hasInternet: kotlinx.coroutines.flow.StateFlow<Boolean> = _hasInternet

    private val _homeReloads = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Manual "recargar catálogo" pulses from the top bar's reload button; whoever is collecting
     *  (the home, the Categorías screen) refetches the shared catalog, bypassing the 6 h cache. */
    val homeReloads: kotlinx.coroutines.flow.SharedFlow<Unit> = _homeReloads
    fun reloadHomeCatalog() { _homeReloads.tryEmit(Unit) }

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

    val apkDownloader: ApkDownloader by lazy { ApkDownloader(appContext) }

    val credentialsStore: com.arkiv.player.data.credentials.RemoteCredentialsStore by lazy {
        com.arkiv.player.data.credentials.EncryptedRemoteCredentialsStore(appContext)
    }

    val credentialsActivator: com.arkiv.player.data.credentials.CredentialsActivator by lazy {
        com.arkiv.player.data.credentials.CredentialsActivator(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
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

    fun warmUpCredentials() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var built = false
        try {
            if (credentialsStore.read() == null) return // fresh install: nothing to warm
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
            contentSource
            magisAccount
            built = true
        } catch (_: Throwable) {
            // The UI must never hang on a warm-up failure; it will retry the chain on demand.
        } finally {
            _warmedUp.value = true
            // Telemetry: a warm-up this slow is what makes a weak device risk an ANR at startup
            // (the splash waits for it -- see MainActivity). Report the duration so we can see it.
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (built && ms >= SLOW_WARMUP_MS) {
                com.arkiv.player.crash.Crash.report(com.arkiv.player.crash.SlowStartup("credential/Magis warm-up ${ms}ms"), "slow-startup")
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

    /** Magis titles, straight from the portal. Only visible from outside through [contentSource]. */
    private val magisSource: com.arkiv.player.data.gateway.ContentSource by lazy {
        val creds = credentialsStore.read()!!
        com.arkiv.player.data.magis.MagisSource(
            catalog = magisCatalog,
            vodResolver = com.arkiv.player.data.magis.MagisResolve(
                magisPortal, magisSession,
                appId = creds.iptvAppId,
                apkVersion = creds.iptvApkVersion,
            ),
            tmdb = tmdbApi,
            vodStore = com.arkiv.player.data.magis.VodSearchStore(database.vodSearchCacheDao()),
        )
    }

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
     * Where the titles the app searches and plays come from: Magis and Caracol behind a single
     * object. To resolve and list episodes it dispatches by `ref` (each source recognizes its
     * own); to search, it merges both. See [com.arkiv.player.data.gateway.CompositeSource].
     */
    val contentSource: com.arkiv.player.data.gateway.ContentSource by lazy {
        com.arkiv.player.data.gateway.CompositeSource(listOf(magisSource, dituSource))
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
        )
    }

    /**
     * Local HLS proxy for the live channel. Each segment's signature is computed ON THE DEVICE and
     * has no fallback: the fallback used to be asking the gateway for it, which doesn't exist on
     * this branch. If Magis ever changes the algorithm, it gets fixed by shipping an APK (it used
     * to get fixed by redeploying the server, which is exactly the dependency this branch removes).
     */
    val liveHlsProxy: com.arkiv.player.playback.LiveHlsProxy by lazy {
        com.arkiv.player.playback.LiveHlsProxy(
            com.arkiv.player.playback.LocalSignature(),
            // After an unrecoverable double 403 (expired session, no signature): invalidates THAT
            // channel's cached session so the next abrir()/precalentar() resolves against the
            // gateway again instead of reusing the one we already know is dead for up to 300s more.
            onSessionDead = { channel -> liveController.invalidate(channel) },
        )
    }

    /** Opens live channels: resolves against the portal and hands the player the URL from
     *  [liveHlsProxy]. */
    val liveController: com.arkiv.player.ui.live.LiveController by lazy {
        com.arkiv.player.ui.live.LiveController(
            resolver = { code -> magisLive.resolveOrThrow(code) },
            urlFor = { session -> liveHlsProxy.urlFor(session) },
        )
    }

    val pendingUpdateStore: com.arkiv.player.data.update.PendingUpdateStore by lazy {
        com.arkiv.player.data.update.PendingUpdateStore(appContext)
    }

    // Staggered rollout: after an update is DETECTED we don't prompt right away. We wait a base delay
    // plus a random jitter -- rolled once at detection and persisted -- so a fleet of devices doesn't
    // all prompt (and download) at the same instant, and archive.org has time to finish publishing
    // the APK (so the sha256 check passes). The prompt then surfaces on this session's timer or on
    // the next app open, once that randomized time has passed.
    private val updateBaseDelayMs = java.util.concurrent.TimeUnit.HOURS.toMillis(1)
    private val updateJitterMs = java.util.concurrent.TimeUnit.HOURS.toMillis(6)

    /**
     * OTA check: called by [com.arkiv.player.data.update.UpdateWorker] and on app startup. Records a
     * newer version as a PENDING update (with its one-time staggered promote time) instead of
     * prompting immediately, then surfaces it via [promoteDueUpdate] if its time has already passed.
     */
    suspend fun checkForUpdate() {
        val info = updateChecker.check(BuildConfig.VERSION_CODE)
        if (info != null) {
            val pending = pendingUpdateStore.putIfNew(
                info, System.currentTimeMillis(), updateBaseDelayMs, updateJitterMs,
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
    suspend fun checkForUpdateNow(): UpdateInfo? = updateChecker.check(BuildConfig.VERSION_CODE)

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
    val httpRangeDownloader: com.arkiv.player.data.local.HttpRangeDownloader by lazy {
        com.arkiv.player.data.local.HttpRangeDownloader(
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
        mapOf(
            "magis" to com.arkiv.player.data.local.MagisDownloadStrategy(
                repository, contentSource, httpRangeDownloader,
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

    val dlna: DlnaController by lazy { DlnaController(appContext) }

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
                _castSession ?: com.arkiv.player.cast.CastSessionManager(ctx, repository, applicationScope)
                    .also { _castSession = it }
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
