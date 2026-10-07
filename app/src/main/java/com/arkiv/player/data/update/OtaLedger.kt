package com.arkiv.player.data.update

import android.content.SharedPreferences

/**
 * What the OTA telemetry needs to remember across starts, on this device only (in the OTA prefs file, keys `ledger.*`,
 * which [PendingUpdateStore.clear] keeps): the installed versionCode seen last, so the first start after an update
 * reports `updated` once with where the APK came from and how many downloads it took; which releases were already
 * reported as `offered`; and the last value of the remote `otaIdentityCheck` switch.
 */
class OtaLedger(private val prefs: SharedPreferences) {
    /** An update this start just found: [from] the previous versionCode (-1 = unknown, the ledger is newer than it). */
    data class Updated(val from: Int, val to: Int, val source: String, val attempts: Int)

    /**
     * Records [current] as the installed versionCode and returns the update it means, once: the version grew since the
     * last start, or (first start with a ledger) the pending OTA release [pendingBase] is the one now installed.
     */
    fun onStart(current: Int, pendingBase: Int?): Updated? {
        val last = prefs.getInt(K_LAST, -1)
        if (last == current) return null
        val edit = prefs.edit().putInt(K_LAST, current)
        val updated = when {
            last > 0 -> OtaVersion.isNewer(current, last)
            else -> pendingBase != null && pendingBase == OtaVersion.baseOf(current)
        }
        if (!updated) {
            edit.apply()
            return null
        }
        val sameRelease = prefs.getInt(K_DL_BASE, -1) == OtaVersion.baseOf(current)
        val result = Updated(
            from = last,
            to = current,
            source = if (sameRelease) prefs.getString(K_DL_SOURCE, null) ?: "unknown" else "unknown",
            attempts = if (sameRelease) prefs.getInt(K_DL_ATTEMPTS, 0) else 0,
        )
        edit.remove(K_DL_BASE).remove(K_DL_SOURCE).remove(K_DL_ATTEMPTS).apply()
        return result
    }

    /** True the first time [base] is offered (or held back) on this device: `offered` is reported once per release. */
    fun firstOffer(base: Int): Boolean {
        if (prefs.getInt(K_OFFERED, -1) == base) return false
        prefs.edit().putInt(K_OFFERED, base).apply()
        return true
    }

    /** One more download of [base] started; returns how many so far. */
    fun downloadStarted(base: Int): Int {
        val attempts = if (prefs.getInt(K_DL_BASE, -1) == base) prefs.getInt(K_DL_ATTEMPTS, 0) + 1 else 1
        prefs.edit().putInt(K_DL_BASE, base).putInt(K_DL_ATTEMPTS, attempts).remove(K_DL_SOURCE).apply()
        return attempts
    }

    /** [base]'s APK came, verified, from [source] (`github`, `jsdelivr`, `unpkg`, `archive`). */
    fun downloaded(base: Int, source: String) {
        if (prefs.getInt(K_DL_BASE, -1) == base) prefs.edit().putString(K_DL_SOURCE, source).apply()
    }

    /** The remote `otaIdentityCheck` last read; null = never read. */
    fun identityCheck(): Boolean? = if (prefs.contains(K_IDENTITY)) prefs.getBoolean(K_IDENTITY, true) else null

    fun saveIdentityCheck(on: Boolean) = prefs.edit().putBoolean(K_IDENTITY, on).apply()

    companion object {
        const val PREFIX = "ledger."
        private const val K_LAST = "ledger.lastVersionCode"
        private const val K_OFFERED = "ledger.offeredBase"
        private const val K_DL_BASE = "ledger.downloadBase"
        private const val K_DL_ATTEMPTS = "ledger.downloadAttempts"
        private const val K_DL_SOURCE = "ledger.downloadSource"
        private const val K_IDENTITY = "ledger.otaIdentityCheck"
    }
}