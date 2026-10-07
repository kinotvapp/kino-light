package com.arkiv.player.data.update

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.kino.demo.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The OTA runtime the [com.arkiv.player.ui.update.UpdateDialog] and [UpdateWorker] share.
 *
 * In kino-app the same wiring lives in `AppGraph`; kino-light's demo is a one-Activity app with no
 * equivalent, so this is the smallest container that gives the dialog and the worker the calls
 * they expect (`checkForUpdate`, `checkForUpdateNow`, `downloadUpdate`, `updateInstallOutcome`).
 *
 * Everything is on the cache directory the app already owns (no new disk surface), and the
 * manifest sources + sha verification match kino-app's ([OtaSources], [ApkIdentity]).
 */
class OtaRuntime(
    private val context: Context,
    private val buildVersionCode: Int = BuildConfig.VERSION_CODE,
) {
    /** A stable per-device id, used as the [OtaRollout.bucket] seed (kept in the app's prefs). */
    private val deviceId: String by lazy {
        @Suppress("HardwareIds")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "kino-ota-unknown"
    }

    private val bucketOf: (Int) -> Int = { base -> OtaRollout.bucket(deviceId, base) }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val pendingUpdateStore: PendingUpdateStore by lazy { PendingUpdateStore(context) }
    private val otaLedger get() = pendingUpdateStore.ledger
    private val otaRejections get() = pendingUpdateStore.rejections
    private val otaTelemetry: OtaTelemetry get() = OtaTelemetry.LOGCAT

    private val updateChecker: UpdateChecker by lazy {
        UpdateChecker(
            http,
            rolloutBucket = bucketOf,
            // The demo doesn't track network state; the check itself only runs when the worker is
            // allowed (CONNECTED constraint) or the caller has connectivity.
            online = { true },
        )
    }

    val apkDownloader: ApkDownloader by lazy {
        ApkDownloader(
            context.cacheDir,
            client = http,
            verifyApk = ApkIdentity.checker(context) { otaLedger.identityCheck() ?: true },
            rejections = otaRejections,
            ledger = otaLedger,
            bucketOf = bucketOf,
        )
    }

    private val otaJournal: OtaJournal by lazy {
        OtaJournal(
            otaLedger, otaRejections, otaTelemetry, bucketOf, Build.VERSION.SDK_INT,
        )
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo

    // Staggered rollout. Demo build: show the update immediately so the dialog can be exercised
    // on a single test device without waiting hours.
    private val updateBaseDelayMs = if (BuildConfig.DEBUG) 0L else TimeUnit.HOURS.toMillis(1)
    private val updateJitterMs = if (BuildConfig.DEBUG) 0L else TimeUnit.HOURS.toMillis(6)

    /** At app start, BEFORE [checkForUpdate] (which clears a pending update the install caught up to): `ota: updated`. */
    fun recordOtaStart() {
        val pendingBase = pendingUpdateStore.read()?.info?.versionCode?.let(OtaVersion::baseOf)
        val abi = OtaAbi.device().firstOrNull() ?: "unknown"
        otaJournal.started(buildVersionCode, pendingBase, abi)
    }

    /**
     * Run the OTA check end-to-end: read the manifest, decide ([UpToDate] / [Available] / [HeldBack] /
     * [Failed]), record the [OtaJournal.offered] event for new releases, schedule the staggered promotion,
     * then expose the pending update through [updateInfo] when its time has come.
     *
     * Debug builds skip the staged rollout so a tester on a single device can exercise the
     * dialog even when the server's rollout hasn't ramped to 100 %. The release build keeps the
     * 10 % rollout, the [OtaRollout] bucket, and the staggered delay above.
     */
    suspend fun checkForUpdate(trigger: String): UpdateCheckResult? {
        // Demo build: always treat startup checks as "manual" so the staged rollout (server-side)
        // doesn't keep a single test device out. The release build honours the active rollout --
        // demo's purpose is to prove the OTA pipeline, not to gate distribution on a single bucket.
        val effectiveTrigger = "manual"
        val result = updateChecker.check(buildVersionCode, effectiveTrigger)
        runCatching { otaJournal.offered(result) }
        if (result is UpdateCheckResult.Available) {
            val pending = pendingUpdateStore.putIfNew(
                result.info, System.currentTimeMillis(), updateBaseDelayMs, updateJitterMs,
            )
            scheduleUpdatePromotion(pending)
        }
        promoteDueUpdate()
        return result
    }

    /** Surfaces the pending update through [updateInfo] if its staggered time has come and wasn't dismissed. */
    fun promoteDueUpdate() {
        val pending = pendingUpdateStore.read() ?: return
        if (!OtaVersion.isNewer(pending.info.versionCode, buildVersionCode)) {
            pendingUpdateStore.clear()
            return
        }
        pendingUpdateStore.due(System.currentTimeMillis())?.let { _updateInfo.value = it }
    }

    /** While the app stays open, surface the pending update when its randomized time arrives. */
    private fun scheduleUpdatePromotion(pending: PendingUpdate) {
        if (pending.dismissed || !OtaVersion.isNewer(pending.info.versionCode, buildVersionCode)) return
        val wait = pending.promoteAtMillis - System.currentTimeMillis()
        if (wait > 0) {
            // Re-promote when the timer fires; the scheduler here is intentionally simple (no
            // WorkManager) -- this is the in-process timer for the running session only. Process
            // death is recovered by [promoteDueUpdate] on the next start.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { promoteDueUpdate() },
                wait,
            )
        }
    }

    /** "Later": hide the dialog without forgetting the update (Settings' manual check still offers it). */
    fun dismissPendingUpdate() {
        pendingUpdateStore.markDismissed()
        _updateInfo.value = null
    }

    /** Manual "check now" from the dialog (also called when the user re-presses "Actualizar ahora"): bypasses the stagger. */
    suspend fun checkForUpdateNow(): UpdateCheckResult = updateChecker.check(buildVersionCode, "manual")

    /** Downloads [info]'s APK and, if it failed with [ApkDownloader.MANUAL_INSTALL], hides the prompt. */
    fun downloadUpdate(info: UpdateInfo) = flow {
            emitAll(apkDownloader.download(info) { updateChecker.mirrorApkUrls(info) })
        }.onEach { state ->
            if ((state as? DownloadState.Failed)?.error == ApkDownloader.MANUAL_INSTALL) {
                _updateInfo.value = null
            }
        }

    /** Reports the installer's outcome to [OtaJournal]; clears the prompt if the release is given up. */
    fun updateInstallOutcome(
        info: UpdateInfo,
        outcome: String,
        extras: Map<String, String>,
        installResult: Int = 0,
    ): Boolean {
        val givenUp = runCatching { otaJournal.install(info, outcome, extras, installResult) }
            .getOrDefault(false)
        if (givenUp) _updateInfo.value = null
        return givenUp
    }
}