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

    /** This device's refused releases ([OtaRejections]), in the same file: [clear] forgets them too. */
    val rejections: OtaRejections by lazy { OtaRejections(prefs, identityCheckOn = { ledger.identityCheck() ?: true }) }

    /**
     * The pending update to show now: its staggered time has passed, the person did not dismiss it, and this device has
     * not given it up ([rejections]: while blocked it stays hidden, and it comes back when the backoff ends or the
     * owner turns `otaIdentityCheck` off for an identity refusal). Null otherwise.
     */
    fun due(now: Long): UpdateInfo? {
        val pending = read() ?: return null
        if (!pending.isDue(now) || rejections.blocked(OtaVersion.baseOf(pending.info.versionCode))) return null
        return pending.info
    }

    /** The OTA telemetry's memory ([OtaLedger]), in the same file but kept by [clear]. */
    val ledger: OtaLedger by lazy { OtaLedger(prefs) }

    fun read(): PendingUpdate? {
        val versionCode = prefs.getInt(K_VERSION_CODE, -1)
        if (versionCode < 0) return null
        return PendingUpdate(
            info = UpdateInfo(
                versionCode = versionCode,
                versionName = prefs.getString(K_VERSION_NAME, "").orEmpty(),
                url = prefs.getString(K_URL, "").orEmpty(),
                notes = prefs.getString(K_NOTES, "").orEmpty(),
                sha256 = prefs.getString(K_SHA256, "").orEmpty(),
                abi = prefs.getString(K_ABI, null) ?: OtaAbi.UNIVERSAL,
                alternates = decodeCopies(prefs.getString(K_ALTERNATES, null)),
            ),
            promoteAtMillis = prefs.getLong(K_PROMOTE_AT, 0L),
            dismissed = prefs.getBoolean(K_DISMISSED, false),
        )
    }

    /**
     * Records [info] as pending the first time its RELEASE is seen ([OtaVersion.baseOf]), rolling its
     * randomized [PendingUpdate.promoteAtMillis] = [now] + [baseDelayMs] + random(0..[jitterMs]). The SAME
     * release seen again keeps its promote time and dismissed flag, so repeated checks don't reset the
     * clock or un-dismiss it -- only its download details are refreshed (another mirror answered, or the
     * manifest now lists this device's ABI). A DIFFERENT (newer) release replaces it and re-rolls the
     * delay. Returns the stored [PendingUpdate].
     */
    fun putIfNew(info: UpdateInfo, now: Long, baseDelayMs: Long, jitterMs: Long): PendingUpdate {
        val existing = read()
        if (existing != null && OtaVersion.baseOf(existing.info.versionCode) == OtaVersion.baseOf(info.versionCode)) {
            if (existing.info != info) writeInfo(info).apply()
            return existing.copy(info = info)
        }
        val jitter = if (jitterMs > 0) Random.nextLong(jitterMs) else 0L
        val promoteAt = now + baseDelayMs + jitter
        writeInfo(info)
            .putLong(K_PROMOTE_AT, promoteAt)
            .putBoolean(K_DISMISSED, false)
            .apply()
        return PendingUpdate(info, promoteAt, false)
    }

    private fun writeInfo(info: UpdateInfo) = prefs.edit()
        .putInt(K_VERSION_CODE, info.versionCode)
        .putString(K_VERSION_NAME, info.versionName)
        .putString(K_URL, info.url)
        .putString(K_NOTES, info.notes)
        .putString(K_SHA256, info.sha256)
        .putString(K_ABI, info.abi)
        .putString(K_ALTERNATES, encodeCopies(info.alternates))

    /** "Later": stop auto-surfacing this pending update (Settings' manual check still offers it). */
    fun markDismissed() {
        if (prefs.getInt(K_VERSION_CODE, -1) < 0) return
        prefs.edit().putBoolean(K_DISMISSED, true).apply()
    }

    /** Forget the pending update entirely (e.g. once the installed version caught up to it); the [ledger] stays. */
    fun clear() {
        val edit = prefs.edit()
        prefs.all.keys.filterNot { it.startsWith(OtaLedger.PREFIX) }.forEach { edit.remove(it) }
        edit.apply()
    }

    private companion object {
        fun encodeCopies(copies: List<ApkCopy>): String =
            org.json.JSONArray(copies.map { org.json.JSONObject().put("url", it.url).put("sha256", it.sha256) }).toString()

        fun decodeCopies(raw: String?): List<ApkCopy> = runCatching {
            val a = org.json.JSONArray(raw ?: return emptyList())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> ApkCopy(o.getString("url"), o.optString("sha256", "")) } }
        }.getOrDefault(emptyList())

        const val K_VERSION_CODE = "versionCode"
        const val K_VERSION_NAME = "versionName"
        const val K_URL = "url"
        const val K_NOTES = "notes"
        const val K_SHA256 = "sha256"
        const val K_PROMOTE_AT = "promoteAt"
        const val K_DISMISSED = "dismissed"
        const val K_ABI = "abi"
        const val K_ALTERNATES = "alternates"
    }
}