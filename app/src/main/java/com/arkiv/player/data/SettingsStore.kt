package com.arkiv.player.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arkiv.player.data.magis.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Simple settings persisted in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_settings", Context.MODE_PRIVATE)

    // Player's night mode: level of the black scrim over the video, from 0 (normal) to
    // DIM_MAX_LEVEL (full black). Persisted on purpose (not per session): whoever lowers it
    // watches almost always at night. The player clamps the level; this saves it exactly as it
    // arrives.
    private val _dimLevel = MutableStateFlow(prefs.getInt(KEY_DIM_LEVEL, 0))
    val dimLevel: StateFlow<Int> = _dimLevel

    // Has the artwork resolved before TMDB's exact match already been repaired? See
    // ArkivRepository.repairArtworkMatches. Marked ONLY when the whole pass finishes, so a launch
    // with no internet doesn't count it as done and leave titles mismatched forever.
    private val _artworkRematchDone = MutableStateFlow(prefs.getBoolean(KEY_ARTWORK_REMATCH, false))
    val artworkRematchDone: StateFlow<Boolean> = _artworkRematchDone

    // Task 7 (sub-project 2B): the DEVICE's 18+ lock. Used to live in `SecureDeviceStore`, which
    // Task 9 deletes along with the accounts -- it isn't account data, so it's rescued here first.
    // Per device, like [artworkRematchDone] above.
    private val _adultsUnlocked = MutableStateFlow(prefs.getBoolean(KEY_ADULTS_UNLOCKED, false))
    val adultsUnlocked: StateFlow<Boolean> = _adultsUnlocked

    // The code that opens that lock, chosen from Ajustes. `null` = none was ever chosen and the
    // default rules; who decides that is `AdultsLock.effectiveCode`, not this store -- this only
    // saves what the person typed. It's plain text on purpose: the lock stops someone with the
    // remote, not someone with `adb` (see `AdultsLock`'s KDoc), so encrypting it would give a
    // sense of security the rest of the design doesn't back up.
    private val _adultsCode = MutableStateFlow(prefs.getString(KEY_ADULTS_CODE, null))
    val adultsCode: StateFlow<String?> = _adultsCode

    // Whether the player shows "Datos curiosos" (fun facts, sub-project 4). Default ON to keep the
    // current behavior; turning it OFF stops even REQUESTING them (see PlayerViewModel.load), so no
    // model call and nothing on screen -- not just a hidden badge. Per device, like the flags above.
    private val _funFactsEnabled = MutableStateFlow(prefs.getBoolean(KEY_FUN_FACTS_ENABLED, true))
    val funFactsEnabled: StateFlow<Boolean> = _funFactsEnabled

    // Whether the app periodically re-downloads the backup seed pool on its own (every 3h, via
    // UpdateWorker). Only ever ACTED ON for a device that has actually needed seeds -- i.e. one the
    // portal geo-blocked or whose anonymous session died (see MagisRegionBlockStore.geoBlocked); a
    // device that mints its own session never downloads seeds regardless of this flag. Default ON;
    // the toggle in Ajustes -> App lets a seed-dependent device opt out. Manual "Sembrar semillas"
    // stays available either way.
    private val _seedAutoRefreshEnabled = MutableStateFlow(prefs.getBoolean(KEY_SEED_AUTO_REFRESH, true))
    val seedAutoRefreshEnabled: StateFlow<Boolean> = _seedAutoRefreshEnabled

    // "Force TV layout": the manual escape for a TV box that every auto-detection signal misreads as
    // a tablet (see DeviceType). The KEY is DeviceType's, and DeviceType reads this same pref file
    // directly -- so turning it on takes effect for EVERY device-type consumer after the app
    // relaunches (the graph picks the type once per process). This store only persists + mirrors it.
    private val _forceTvDesign = MutableStateFlow(prefs.getBoolean(com.arkiv.player.DeviceType.KEY_FORCE_TV, false))
    val forceTvDesign: StateFlow<Boolean> = _forceTvDesign

    // Decorative motion (the drifting hero backdrop and its crossfade): the person's choice, plus the
    // app's own verdict for "Automático". See [EffectsPolicy]. `effectsAutoReduced` is set once a
    // device has been measured slow on [EffectsPolicy.STRIKES_TO_REDUCE] separate launches, and stays
    // until the person picks "Automático" again (which clears it and the strike count).
    private val _effectsMode = MutableStateFlow(EffectsMode.fromKey(prefs.getString(KEY_EFFECTS_MODE, null)))
    val effectsMode: StateFlow<EffectsMode> = _effectsMode
    private val _effectsAutoReduced = MutableStateFlow(prefs.getBoolean(KEY_EFFECTS_AUTO_REDUCED, false))
    val effectsAutoReduced: StateFlow<Boolean> = _effectsAutoReduced

    // Marker for the 2026-08-14 one-time recents purge (see `ArkivApp.onCreate`). Same rescue as
    // [adultsUnlocked]: if it's lost, the purge simply runs once more -- no StateFlow needed
    // since nothing observes it, it's only read on launch.
    val recentsPurged: Boolean
        get() = prefs.getBoolean(KEY_RECENTS_PURGED, false)

    fun setDimLevel(v: Int) { prefs.edit().putInt(KEY_DIM_LEVEL, v).apply(); _dimLevel.value = v }

    fun setArtworkRematchDone(v: Boolean) {
        if (_artworkRematchDone.value == v) return
        prefs.edit().putBoolean(KEY_ARTWORK_REMATCH, v).apply()
        _artworkRematchDone.value = v
    }


    fun setAdultsUnlocked(v: Boolean) {
        if (_adultsUnlocked.value == v) return
        prefs.edit().putBoolean(KEY_ADULTS_UNLOCKED, v).apply()
        _adultsUnlocked.value = v
    }

    /** `null` deletes the key and returns the lock to its default code. */
    fun setAdultsCode(v: String?) {
        if (_adultsCode.value == v) return
        prefs.edit().apply { if (v == null) remove(KEY_ADULTS_CODE) else putString(KEY_ADULTS_CODE, v) }.apply()
        _adultsCode.value = v
    }

    fun setRecentsPurged(v: Boolean) {
        prefs.edit().putBoolean(KEY_RECENTS_PURGED, v).apply()
    }

    fun setFunFactsEnabled(v: Boolean) {
        if (_funFactsEnabled.value == v) return
        prefs.edit().putBoolean(KEY_FUN_FACTS_ENABLED, v).apply()
        _funFactsEnabled.value = v
    }

    fun setSeedAutoRefreshEnabled(v: Boolean) {
        if (_seedAutoRefreshEnabled.value == v) return
        prefs.edit().putBoolean(KEY_SEED_AUTO_REFRESH, v).apply()
        _seedAutoRefreshEnabled.value = v
    }

    fun setForceTvDesign(v: Boolean) {
        if (_forceTvDesign.value == v) return
        prefs.edit().putBoolean(com.arkiv.player.DeviceType.KEY_FORCE_TV, v).apply()
        _forceTvDesign.value = v
    }

    /**
     * Picking "Automático" wipes the earlier verdict (and the strikes toward it): whoever goes back to
     * Automático wants the device judged again, not the old conclusion kept.
     */
    fun setEffectsMode(mode: EffectsMode) {
        if (_effectsMode.value == mode) return
        prefs.edit().putString(KEY_EFFECTS_MODE, mode.key).apply {
            if (mode == EffectsMode.AUTO) {
                putBoolean(KEY_EFFECTS_AUTO_REDUCED, false)
                putInt(KEY_EFFECTS_SLOW_STRIKES, 0)
            }
        }.apply()
        if (mode == EffectsMode.AUTO) _effectsAutoReduced.value = false
        _effectsMode.value = mode
    }

    /** The app version (code) whose startup profile was already reported: each device reports once per release. See `StartupProfiler`. */
    val startupProfileReportedVersion: Int get() = prefs.getInt(KEY_STARTUP_PROFILE_VERSION, 0)

    fun markStartupProfileReported(versionCode: Int) {
        prefs.edit().putInt(KEY_STARTUP_PROFILE_VERSION, versionCode).apply()
    }

    /** A measured-smooth launch clears the strikes: two slow samples must be close together to count, not scattered over months. */
    fun recordSmoothEffectsSample() {
        if (prefs.getInt(KEY_EFFECTS_SLOW_STRIKES, 0) != 0) prefs.edit().putInt(KEY_EFFECTS_SLOW_STRIKES, 0).apply()
    }

    /**
     * A measured-slow launch. Returns true when this turned the effects off for good: either it was the
     * strike that reached [EffectsPolicy.STRIKES_TO_REDUCE], or the sample was [severe] (see
     * [EffectsPolicy.SEVERE_SHARE]) and needs no confirmation. Otherwise it only counts, so one
     * borderline, possibly contaminated sample (the startup warm-up competing for the CPU) can't
     * condemn a device.
     */
    fun recordSlowEffectsSample(severe: Boolean = false): Boolean {
        val strikes = prefs.getInt(KEY_EFFECTS_SLOW_STRIKES, 0) + 1
        prefs.edit().putInt(KEY_EFFECTS_SLOW_STRIKES, strikes).apply()
        if ((!severe && strikes < EffectsPolicy.STRIKES_TO_REDUCE) || _effectsAutoReduced.value) return false
        prefs.edit().putBoolean(KEY_EFFECTS_AUTO_REDUCED, true).apply()
        _effectsAutoReduced.value = true
        return true
    }

    /** When "For you" was last attempted (0 = never). See `ForYouGate`. */
    val forYouLastAttemptMs: Long get() = prefs.getLong(KEY_FOR_YOU_LAST_ATTEMPT, 0L)

    /** Whether that attempt failed on the model: then it's retried after 15 min, not 24 h. */
    val forYouLastAttemptWasModelFailure: Boolean get() = prefs.getBoolean(KEY_FOR_YOU_MODEL_FAILURE, false)

    fun markForYouAttempt(nowMs: Long, wasModelFailure: Boolean) {
        prefs.edit()
            .putLong(KEY_FOR_YOU_LAST_ATTEMPT, nowMs)
            .putBoolean(KEY_FOR_YOU_MODEL_FAILURE, wasModelFailure)
            .apply()
    }

    /**
     * Pulls the 18+ lock from the device's encrypted store the first time it runs. `fromOldStore`
     * is `null` when that store couldn't be read (see `ArkivApp.onCreate`) -- then it's left with
     * whatever's already here (or the default). Idempotent: on later launches `prefs` already has
     * the key and `migratedValue` respects it without looking at the old store again.
     *
     * Writes straight to `prefs` instead of going through [setAdultsUnlocked]: that setter
     * doesn't write if the value didn't change (to avoid an extra `apply()` overwriting the
     * StateFlow), but here the most common case is exactly that -- the old store was never
     * unlocked and the result matches the in-memory default. If it went through the guard, the
     * key would never end up recorded and this function would look at `SecureDeviceStore` again
     * on every launch, which the comment above says does NOT happen.
     */
    fun migrateAdultsUnlocked(fromOldStore: Boolean?) {
        val migrated = migratedValue(readNullable(KEY_ADULTS_UNLOCKED), fromOldStore, false)
        prefs.edit().putBoolean(KEY_ADULTS_UNLOCKED, migrated).apply()
        _adultsUnlocked.value = migrated
    }

    /** Same rescue as [migrateAdultsUnlocked] for the recents-purge marker. */
    fun migrateRecentsPurged(fromOldStore: Boolean?) {
        setRecentsPurged(migratedValue(readNullable(KEY_RECENTS_PURGED), fromOldStore, false))
    }

    /** `null` if `key` hasn't been written to these settings yet -- different from being `false`. */
    private fun readNullable(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    /**
     * Fires [migrateAdultsUnlocked]/[migrateRecentsPurged] by reading the OLD encrypted
     * file directly (Task 9, sub-project 2B).
     *
     * That file (`arkiv_pb_secure`) belonged to `SecureDeviceStore`, which Task 9 deletes along
     * with the rest of `pocketbase/` -- the two keys that matter (`adultsUnlocked`,
     * `recientesPurgados2026_08_14`) are NOT account data, so they're rescued by reading the same
     * file with the same scheme (`EncryptedSharedPreferences` + `MasterKey` AES256_GCM +
     * AES256_SIV/AES256_GCM) that class used, without resurrecting it. `EncryptedPrefs.openOrRepair`
     * is still alive because `EncryptedMagisCredentialStore` uses it -- reused here for the same
     * problem (a Keystore that no longer decrypts the file).
     *
     * If both keys already migrated, the old file isn't even looked at:
     * `EncryptedSharedPreferences.create` costs Keystore + Tink, and this is called on EVERY
     * launch. And if the file doesn't even exist -a clean install of this branch, which never had
     * `SecureDeviceStore`-, opening it isn't attempted either: see [oldAccountsStoreFileExists].
     */
    fun migrateFromOldAccountsStore(context: Context) {
        if (readNullable(KEY_ADULTS_UNLOCKED) != null && readNullable(KEY_RECENTS_PURGED) != null) return
        val app = context.applicationContext
        val old = if (oldAccountsStoreFileExists(app)) {
            runCatching { openOldAccountsStore(app) }.getOrNull()
        } else {
            null
        }
        // Same text keys as the old file (see the comment next to these constants, further
        // below): `SecureDeviceStore` wrote them verbatim.
        migrateAdultsUnlocked(old.readOldBoolean(KEY_ADULTS_UNLOCKED))
        migrateRecentsPurged(old.readOldBoolean(KEY_RECENTS_PURGED))
        if (old != null) {
            // Both keys that matter are already migrated above: deleting the old file removes the
            // Kino account's email and password that were still living there, from a subsystem
            // that no longer exists. Goes AFTER migrating, never before. If the file was
            // undecryptable, `discardUndecryptable` (see [openOldAccountsStore]) already
            // deleted it and `EncryptedPrefs` retried: `old` ends up pointing at a freshly
            // created, empty file with nothing to migrate from, and this delete removes it again.
            // It's a redundant delete with no consequence -- the end state is the same. This only
            // touches the shared_prefs file -- NEVER the Keystore's master key, which is the SAME
            // one `EncryptedMagisCredentialStore` uses for the Magis session.
            runCatching { app.deleteSharedPreferences(OLD_ACCOUNTS_STORE_FILE) }
        }
    }

    private fun SharedPreferences?.readOldBoolean(key: String): Boolean? =
        this?.let { if (it.contains(key)) it.getBoolean(key, false) else null }

    companion object {
        const val PREFS_NAME = "arkiv_settings"
        private const val KEY_DIM_LEVEL = "dim_level"
        private const val KEY_ARTWORK_REMATCH = "artwork_rematch_done"

        // Task 7: same text keys `SecureDeviceStore` used (`K_ADULTOS`, `K_PURGA_RECIENTES`) for
        // the name, even though the value lives in a different prefs file -- this way the code's
        // history stays searchable by that name. Task 9: [migrateFromOldAccountsStore] reads
        // those same two keys from the original file.
        private const val KEY_ADULTS_UNLOCKED = "adultosDesbloqueado"
        private const val KEY_ADULTS_CODE = "codigoAdultos"
        private const val KEY_RECENTS_PURGED = "recientesPurgados2026_08_14"

        private const val KEY_FOR_YOU_LAST_ATTEMPT = "para_ti_ultimo_intento"
        private const val KEY_FOR_YOU_MODEL_FAILURE = "para_ti_fallo_modelo"
        private const val KEY_FUN_FACTS_ENABLED = "datos_curiosos_habilitados"
        private const val KEY_SEED_AUTO_REFRESH = "semillas_auto_refresco"
        private const val KEY_EFFECTS_MODE = "efectos_modo"
        private const val KEY_EFFECTS_AUTO_REDUCED = "efectos_reducidos_auto"
        private const val KEY_EFFECTS_SLOW_STRIKES = "efectos_muestras_lentas"
        private const val KEY_STARTUP_PROFILE_VERSION = "perfil_arranque_version"

        /** The encrypted file `SecureDeviceStore` used to write (deleted in Task 9). */
        private const val OLD_ACCOUNTS_STORE_FILE = "arkiv_pb_secure"

        /**
         * `true` if the file exists on disk. Checking this BEFORE [openOldAccountsStore] is
         * the difference between reading something and CREATING it: `EncryptedSharedPreferences.create`
         * writes the Tink keyset the first time, so without this check a clean install -which
         * never had `SecureDeviceStore`- would end up generating `arkiv_pb_secure` and touching
         * the Keystore on `Application.onCreate`'s main thread, to rescue a file that never existed.
         */
        private fun oldAccountsStoreFileExists(app: Context): Boolean =
            java.io.File(app.dataDir, "shared_prefs/$OLD_ACCOUNTS_STORE_FILE.xml").exists()

        /**
         * Opens `arkiv_pb_secure` with the same scheme `SecureDeviceStore.cifradas()` used to
         * write it, for [migrateFromOldAccountsStore]. Read-only: nothing is ever written back to
         * it here, so if the Keystore can't decrypt it there's nothing to repair -- deleting the
         * file is enough (NEVER the master key: it's the SAME one
         * `EncryptedMagisCredentialStore` uses for `arkiv_magis_secure`, `MasterKey.Builder(app)`
         * with no alias of its own, so touching it in passing would break the Magis session for no
         * reason) and letting the second attempt open an empty file -- which for a migration is
         * exactly "there was nothing to migrate".
         */
        private fun openOldAccountsStore(app: Context): SharedPreferences? =
            EncryptedPrefs.openOrRepair<SharedPreferences?>(
                create = {
                    EncryptedSharedPreferences.create(
                        app,
                        OLD_ACCOUNTS_STORE_FILE,
                        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                },
                discardUndecryptable = {
                    Log.w(TAG_MIGRATION, "old accounts store undecryptable: abandoning without migrating")
                    runCatching { app.deleteSharedPreferences(OLD_ACCOUNTS_STORE_FILE) }
                },
                unencrypted = { null },
            )

        private const val TAG_MIGRATION = "ArkivMigration"
        // The mirror's `POST /api/refresh` key no longer lives here: that endpoint moved to being
        // requested through the gateway (`/v1/catalog/refresh`), which is the one that supplies
        // the credential. With that, the APK stopped carrying it -- which is what the comment that
        // used to be in this spot said: removing it from git didn't remove it from the binary,
        // and a secret embedded in a distributed client isn't a secret. See `MirrorApiClient.refresh`.
        //
        // Task 8 (Step 3): `DEFAULT_ARKIV_API_KEY`/`ARKIV_API_KEY` (the last remaining build key)
        // left entirely for the same reason -- see `docs/INVENTARIO_DE_LLAVES.md`.
        //
        // Sub-project 2A: `KEY_GATEWAY_CONFIG_SOURCE` (where the gateway's config came from: read
        // by live's error message, which now asks about the Magis account) and `KEY_USE_GATEWAY`
        // (the flag to "fall back to the old path", which no longer exists) are both gone.
    }
}

/**
 * What value is left after moving a preference from the device's encrypted store to these
 * settings. Whatever's already here WINS: if the person changed the value after migrating, the
 * old one can't come back to life on the next launch.
 */
internal fun migratedValue(fromSettings: Boolean?, fromOldStore: Boolean?, default: Boolean): Boolean =
    fromSettings ?: fromOldStore ?: default
