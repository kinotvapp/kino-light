package com.arkiv.player.data.update

import android.content.Context
import kotlin.random.Random

/**
 * A detected-but-not-yet-surfaced update, with the (staggered) time it may be shown.
 *
 * [promoteAtMillis] is rolled ONCE when the update is first detected -- a base delay plus a random
 * jitter -- so a fleet of devices doesn't all prompt (and download) at the same instant, and the
 * point is stable across app restarts (persisted, never re-rolled). [dismissed] is set when the
 * person picks "later": it stops the automatic prompt without forgetting the update (Settings' manual
 * check still offers it).
 */
data class PendingUpdate(
    val info: UpdateInfo,
    val promoteAtMillis: Long,
    val dismissed: Boolean,
) {
    fun isDue(now: Long): Boolean = !dismissed && now >= promoteAtMillis
}

/**
 * Persists the pending OTA update across process death, so the staggered-rollout delay survives app
 * restarts and the prompt is not re-rolled on every launch. Non-secret (a public version number and
 * URL), so a plain [android.content.SharedPreferences] file is enough.
 */
class PendingUpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ota_pending", Context.MODE_PRIVATE)

    fun read(): PendingUpdate? {
        val versionCode = prefs.getInt(K_VERSION_CODE, -1)
        if (versionCode < 0) return null
        return PendingUpdate(
            info = UpdateInfo(
                versionCode = versionCode,
                versionName = prefs.getString(K_VERSION_NAME, "").orEmpty(),
                url = prefs.getString(K_URL, "").orEmpty(),
                notes = prefs.getString(K_NOTES, "").orEmpty(),
            ),
            promoteAtMillis = prefs.getLong(K_PROMOTE_AT, 0L),
            dismissed = prefs.getBoolean(K_DISMISSED, false),
        )
    }

    /**
     * Records [info] as pending the first time this versionCode is seen, rolling its randomized
     * [PendingUpdate.promoteAtMillis] = [now] + [baseDelayMs] + random(0..[jitterMs]). The SAME
     * versionCode seen again is left untouched (its promote time and dismissed flag are preserved),
     * so repeated checks don't reset the clock or un-dismiss it. A DIFFERENT (newer) versionCode
     * replaces it and re-rolls the delay. Returns the stored [PendingUpdate].
     */
    fun putIfNew(info: UpdateInfo, now: Long, baseDelayMs: Long, jitterMs: Long): PendingUpdate {
        val existing = read()
        if (existing != null && existing.info.versionCode == info.versionCode) return existing
        val jitter = if (jitterMs > 0) Random.nextLong(jitterMs) else 0L
        val promoteAt = now + baseDelayMs + jitter
        prefs.edit()
            .putInt(K_VERSION_CODE, info.versionCode)
            .putString(K_VERSION_NAME, info.versionName)
            .putString(K_URL, info.url)
            .putString(K_NOTES, info.notes)
            .putLong(K_PROMOTE_AT, promoteAt)
            .putBoolean(K_DISMISSED, false)
            .apply()
        return PendingUpdate(info, promoteAt, false)
    }

    /** "Later": stop auto-surfacing this pending update (Settings' manual check still offers it). */
    fun markDismissed() {
        if (prefs.getInt(K_VERSION_CODE, -1) < 0) return
        prefs.edit().putBoolean(K_DISMISSED, true).apply()
    }

    /** Forget the pending update entirely (e.g. once the installed version caught up to it). */
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val K_VERSION_CODE = "versionCode"
        const val K_VERSION_NAME = "versionName"
        const val K_URL = "url"
        const val K_NOTES = "notes"
        const val K_PROMOTE_AT = "promoteAt"
        const val K_DISMISSED = "dismissed"
    }
}
