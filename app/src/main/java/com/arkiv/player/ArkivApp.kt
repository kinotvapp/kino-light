package com.arkiv.player

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.launch

class ArkivApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    /**
     * The earliest thing that runs in the process: before the ContentProviders (WorkManager and
     * friends) and before [onCreate]. Error reporting is installed here on purpose, so a crash on
     * startup -including one while building the [AppGraph]- also gets captured.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.arkiv.player.crash.Crash.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Off the first-frame critical path: SentryAndroid.init does ~100-300ms of main-thread work
        // (manifest read, integration wiring, ANR watchdog, outbox disk cache) that used to run
        // before anything else -- costly on a weak device, every launch. Posted so it lands after
        // the first frame is scheduled; still on the main thread (its watchdog/lifecycle need it).
        // Early crashes stay covered by Crash.install's chained handler (attachBaseContext), and
        // AnrV2 reads the PREVIOUS process's exit info on the next launch, so a one-frame delay
        // loses nothing there.
        android.os.Handler(android.os.Looper.getMainLooper()).post { installSentry() }
        graph = AppGraph.from(this)

        // Companion link, app-wide: a TV hosts + receives "play" and a phone auto-connects to its
        // paired TV whenever the app is in the FOREGROUND (any screen), not only on the Conectar
        // tab. Tied to ProcessLifecycleOwner so it stops when the app is backgrounded.
        wireCompanionLifecycle()

        // One-off migration (Task 7, sub-project 2B; rewritten in Task 9): the 18+ lock and the
        // recents purge used to live in `SecureDeviceStore` (encrypted prefs from the accounts
        // subsystem, deleted entirely in this same task). Neither of the two is account data, so
        // [SettingsStore.migrateFromOldAccountsStore] rescues them by reading that file directly,
        // without that class -- a single read, synchronous and BEFORE any screen, so nothing ever
        // reads `graph.settings.adultsUnlocked` before it's migrated. If the file doesn't
        // exist or the Keystore can't decrypt it, it's treated as "there was nothing to migrate"
        // and falls back to the default -- it can never be a reason not to start.
        runCatching {
            graph.settings.migrateFromOldAccountsStore(this)
        }.onFailure { report(it, "startup: migrate encrypted-store prefs") }

        // One-off purge from 2026-08-14: adult channels that stayed logged in "Recents" from
        // BEFORE `abrirCanalActual` stopped logging them. They were showing up in the home's
        // "Live channels" row, in plain view of anyone, with their name and logo.
        //
        // EVERYTHING gets deleted, not just the adult ones, because the device can't know which
        // ones were: recents only store code and name, never the category. And it costs nothing —
        // the cloud ones were already cleaned up by hand, so the next sync repopulates the list
        // with the legitimate ones.
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (!graph.settings.recentsPurged) {
                    graph.database.liveRecentDao().deleteAll()
                    graph.settings.setRecentsPurged(true)
                    android.util.Log.w("ArkivAccount", "recent items purged (adult channel leak)")
                }
            }.onFailure { report(it, "startup: purge recents") }
        }

        graph.startNetworkMonitor()

        // Periodic maintenance every 3 hours + immediate check on startup (OTA, blob refresh, and a
        // gated backup-pool re-download -- see UpdateWorker). UPDATE (not KEEP) so an existing install
        // moves from the old 6h schedule to 3h on the next launch.
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            androidx.work.PeriodicWorkRequestBuilder<com.arkiv.player.data.update.UpdateWorker>(
                3, java.util.concurrent.TimeUnit.HOURS,
            ).setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
            ).build(),
        )
        graph.applicationScope.launch {
            runCatching { graph.checkForUpdate() }.onFailure { report(it, "startup: check for update") }
        }
        // Warm the credential/Magis chain on IO: its first init AES-decrypts the store and runs the
        // native 3DES key resolution, which the O-MVLL VM makes slow (seconds). Doing it here means a
        // later Composable that reads graph.liveCatalog/contentSource/magisAccount finds it already
        // built instead of running that on the UI thread and ANRing. See AppGraph.warmUpCredentials.
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { graph.warmUpCredentials() }
        }

        // Reclaim the Chromecast remux cache (cacheDir/remux/*.mp4) on every cold start: those files
        // reach gigabytes and are otherwise only evicted when a NEW remux pushes over the 4 GB cap,
        // so between casts they just sit and fill the disk (the reported storage bloat + the
        // SQLiteFullException crashes). A cold start = fresh process = no cast in flight, so every
        // file is a regenerable leftover; wiping them all here keeps steady-state usage near zero.
        // Off the main thread; a fresh install is a no-op.
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bytes = graph.tsRemuxer.bytesOnDisk()
                graph.tsRemuxer.clear()
                // Telemetry: how big the remux cache actually got before we swept it. A large value
                // is the storage-bloat / SQLITE_FULL risk made visible.
                if (bytes >= 1_073_741_824L) { // 1 GB
                    com.arkiv.player.crash.Crash.report(
                        com.arkiv.player.crash.StoragePressure("remux cache was ${bytes / 1_048_576L}MB at startup"),
                        "storage-pressure",
                    )
                }
            }
        }

        // TV only: downloads never happen there, but an older version could start them, and what it
        // left unfinished (half-downloaded `.part` files, stuck rows) can never be resumed on a TV
        // and just holds gigabytes on a disk that's already tight. Finished downloads are kept (the
        // TV library lists them so they can be watched or deleted). A no-op on phones/tablets.
        graph.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { graph.localDownloads.discardUnfinishedOnTv() }
        }

        // Proactive telemetry: the backup seed pool ran dry for a device that needs it -> the user
        // can't play, and nothing throws. Report the rising edge so we learn about pool exhaustion
        // (and can re-mint) without a user having to tell us. StateFlow only re-emits on change.
        //
        // CRITICAL: `seedsExhausted` forces `magisSession` -> `magisPortal`, whose init does
        // `credentialsStore.read()!!` -- which is NULL before activation and NPEs (a fresh install
        // would crash here). So wait until the device is actually activated before ever touching it.
        graph.applicationScope.launch {
            while (graph.credentialsStore.read() == null) kotlinx.coroutines.delay(3000)
            graph.seedsExhausted.collect { exhausted ->
                if (exhausted) {
                    com.arkiv.player.crash.Crash.report(
                        com.arkiv.player.crash.SeedPoolExhausted(
                            "backup seed pool exhausted (device needs seeds; every fresh seed came back dead)",
                        ),
                        "seeds-exhausted",
                    )
                }
            }
        }

        // New episodes of the series you're watching. Runs in the background and blocks nothing:
        // it's an opportunistic improvement, not a critical path. The "once every N hours" cap
        // lives inside because app startup happens many times a day (just leaving and coming back
        // does it), and checking every single time would waste network for nothing.
        graph.applicationScope.launch {
            runCatching { graph.lookForNewChapters() }
                .onFailure { report(it, "startup: look for new episodes") }
        }
    }

    /**
     * Coil came with the defaults, and its default memory cache is **25% of the heap limit**. On
     * the Fire TV Stick that's ~48 MB reserved just for bitmaps on a 1.7 GB device that runs with
     * ~48 MB free and swap nearly full (measured with `dumpsys meminfo`). With TMDB's episode
     * stills there are quite a few more images on screen than before, so it's worth setting an
     * explicit ceiling instead of leaving the default percentage.
     *
     * On TV it's trimmed to 10% and RGB_565 is allowed: posters and stills are JPEGs with no
     * transparency, so they drop to half the bytes per bitmap with no visible difference from
     * couch distance. On phone it's left at 20% (generous, but below the default) and full color,
     * which is where it actually shows on a screen 30 cm away.
     */
    /**
     * Every startup task runs in its own `runCatching` so a failure doesn't take down the others
     * — but that also made them silent: if a sync never started, there was no trace of why.
     * Reporting doesn't change the isolation, it just leaves a record.
     */
    /**
     * Foreground-scoped companion link. On a TV: host + receive `play` (turned into playback by
     * [com.arkiv.player.companion.AndroidPlayResolver]). On a phone: auto-connect to the paired TV.
     * Both roles also run [com.arkiv.player.companion.CompanionSyncEngine] (Task 7): library,
     * progress, skip markers and live favorites/recents sync both ways over the same link.
     * Everything starts on ON_START and stops on ON_STOP, so the link is up on every screen while
     * the app is foreground and never runs in the background (a background service is a later pass).
     */
    private fun wireCompanionLifecycle() {
        val companion = graph.companion
        // Sync is symmetric (see CompanionSyncEngine's class KDoc): both host and controller push
        // AND pull, so it starts/stops the same way on both branches below.
        val observer = if (DeviceType.isTelevision(this)) {
            val resolver = com.arkiv.player.companion.AndroidPlayResolver(this, graph)
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    companion.startHost()
                    companion.startReceiving(resolver, resolver::openPlayer)
                    companion.startSync(graph.roomSyncSource, graph.syncApply, graph.syncCursorStore)
                }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    companion.stopSync()
                    companion.stopReceiving()
                    companion.stopHost()
                }
            }
        } else {
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    companion.startAutoConnect()
                    companion.startSync(graph.roomSyncSource, graph.syncApply, graph.syncCursorStore)
                }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    companion.stopSync()
                    companion.stopAutoConnect()
                }
            }
        }
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    private fun report(error: Throwable, label: String) {
        android.util.Log.w("ArkivStartup", "$label: ${error.message}", error)
        com.arkiv.player.crash.Crash.report(error, label)
    }

    /**
     * Runtime error reporting: Sentry SDK -> the self-hosted GlitchTip at
     * errores.comparadorinternet.co (pure HTTP client, no Google Play Services -- this app also
     * runs on Fire OS / no-GMS devices). Complements [com.arkiv.player.crash.Crash], which stays
     * local-only (`adb logcat`); this is the one that actually leaves the device.
     *
     * Manual init on purpose, not the Gradle plugin's auto-install (`autoInstallation.enabled =
     * false` in `app/build.gradle.kts`) and not the SDK's manifest-driven auto-init
     * (`io.sentry.auto-init = false`): DSN, environment and the privacy options below all live
     * here, in one place, instead of split across a manifest and a build file.
     *
     * Guarded twice: [BuildConfig.SENTRY_DSN] empty means a checkout/CI build without the secret
     * configured (see `readEnv("SENTRY_DSN")`) still builds and just never reports; `!DEBUG` means
     * a plain `assembleDebug` -- the one most iterated on -- never sends anything, so local dev
     * noise doesn't pollute the project. Only the shipped release build type reports.
     *
     * Cooperative SDK, not a hook: the JVM/ANR integrations below register a
     * `Thread.UncaughtExceptionHandler` (chaining the previous one, same as
     * [com.arkiv.player.crash.CrashHandler] above it) and Android lifecycle callbacks -- nothing
     * that resembles the instrumentation the native anti-tamper checks watch for (those are in the
     * native module, not the JVM side this touches). The NDK integration is the one exception,
     * gated separately below because it isn't cooperative in the same sense (it installs a native
     * signal handler) -- see that option's own comment.
     *
     * Coverage, maximized on purpose (the user asked for every error source Sentry can reach):
     *  - Unhandled JVM exceptions: [io.sentry.SentryOptions.isEnableUncaughtExceptionHandler].
     *  - ANRs (AnrV2): [io.sentry.android.core.SentryAndroidOptions.isAnrEnabled].
     *  - Handled errors the app already reports by hand: routed from
     *    [com.arkiv.player.crash.Crash.report] (one seam, see its KDoc), not from here.
     *  - Native (NDK) crashes: gated behind [BuildConfig.SENTRY_ENABLE_NDK], default OFF -- see
     *    that option's own comment for why.
     *  - Every error event (not a down-sampled subset): [io.sentry.SentryOptions.sampleRate] = 1.0.
     *    ([io.sentry.SentryOptions.tracesSampleRate] = 0.0 is unrelated: that's performance
     *    transactions, which this integration doesn't do at all.)
     */
    private fun installSentry() {
        if (BuildConfig.SENTRY_DSN.isBlank() || BuildConfig.DEBUG) return
        io.sentry.android.core.SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = BuildConfig.BUILD_TYPE
            // Privacy: never attach device user info (IP, username) -- this app splits/ships
            // credentials natively and is privacy-sensitive by design.
            options.isSendDefaultPii = false
            // No performance monitoring (transactions), no session replay: `io.sentry:sentry-android`
            // pulls `sentry-android-replay` transitively (it's part of the umbrella AAR), but
            // replay only records when its own sample rates are set above 0 -- untouched here, so
            // it stays inert. Nothing in this file configures it and no replay events are sent.
            options.tracesSampleRate = 0.0
            // Error coverage, maximized: capture every error event, don't down-sample (default is
            // already "no sampling", set explicitly so it can't silently change under a future SDK
            // bump), and attach a stacktrace even to hand-captured messages, not just exceptions.
            options.sampleRate = 1.0
            options.isAttachStacktrace = true
            // Explicit confirmation, not reliance on the default: unhandled JVM exceptions still
            // reach Sentry's own chained UncaughtExceptionHandler (it wraps CrashHandler above,
            // doesn't replace it -- both fire, see Crash's class KDoc) and ANR (AnrV2) detection
            // stays on. Both already default to true in this SDK version; set here so a future
            // default change can't silently turn either off without this line also changing.
            options.isEnableUncaughtExceptionHandler = true
            options.isAnrEnabled = true
            // Native (NDK) crash capture: `sentry-android` bundles `sentry-android-ndk`
            // transitively, which installs a native signal handler when this is on -- default is
            // actually `true` upstream, meaning it would otherwise already be silently active.
            // Defaulted OFF here instead (BuildConfig.SENTRY_ENABLE_NDK, from `.env`,
            // `SENTRY_ENABLE_NDK=true` to opt in): this app's native module has its own
            // anti-instrumentation/anti-tamper checks (see kinomorf's O-MVLL RASP layer), and a
            // second, independent native signal handler chaining in is exactly the kind of thing
            // that can interact badly with that -- either a real chaining conflict, or a
            // ptrace/signal-based self-check mistaking Sentry's handler for an attached
            // debugger/hook. That interaction can only be judged on a real device, which this
            // change wasn't verified against. The native `.so` is also stripped (no symbolication
            // upside either way, see Part A's original scope note), so the safer default is off
            // until someone confirms on-device that turning it on doesn't trip the app's own
            // anti-tamper checks or destabilize it.
            options.isEnableNdk = BuildConfig.SENTRY_ENABLE_NDK
            // Scrubbing (by key AND by value) lives in com.arkiv.player.crash.SentryScrubber -- a
            // pure function over SentryEvent, unit-tested on the JVM (SentryScrubberTest), not
            // inline here. Applies to EVERY event this file or Crash.report sends, regardless of
            // source (unhandled, ANR, NDK, or hand-captured) -- beforeSend is one global hook.
            options.setBeforeSend { event, _ -> com.arkiv.player.crash.SentryScrubber.scrub(event) }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val tv = DeviceType.isTelevision(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (tv) 0.10 else 0.20)
                    .build()
            }
            // The disk cache avoids re-downloading the same cover art on every launch; Coil's
            // default (2% of free space) can be huge on a phone with a lot of disk.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(com.arkiv.player.data.local.AppStorage.IMAGE_CACHE_DIR))
                    .maxSizeBytes(if (tv) 64L * 1024 * 1024 else 192L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(tv)
            .build()
    }
}
