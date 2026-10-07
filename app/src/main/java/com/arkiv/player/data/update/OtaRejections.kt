package com.arkiv.player.data.update

import android.content.SharedPreferences

/**
 * Releases this device could not install for a reason that would repeat: every copy's bytes (same sha256 on
 * every mirror) were refused by [ApkIdentity], or Android's installer itself failed. Downloading it again would
 * only fail the same way -- on 0.9.51 a refused copy cost ~14 MB per attempt, attempt after attempt (ERRORES-EFS) --
 * so the release is left alone for [BASE_BACKOFF_MS], doubling per refusal up to [MAX_BACKOFF_MS], and the person
 * is pointed to the manual install ([ApkDownloader.MANUAL_INSTALL]).
 *
 * Device-only on purpose: whether THIS device's Android can install an APK says nothing about another device.
 * Kept in the OTA prefs file, so it goes with [PendingUpdateStore.clear] once the installed version caught up.
 */
class OtaRejections(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The owner's `otaIdentityCheck` switch: while it is off, a release refused by the identity check is not blocked. */
    private val identityCheckOn: () -> Boolean = { true },
) {
    /**
     * True while [versionCode] waits out its backoff: no download is attempted and the prompt is not shown. A wait longer
     * than [MAX_BACKOFF_MS] from now means the clock went back (or was far ahead when it was written): it is not
     * honoured. A refusal by the identity check ([CAUSE_IDENTITY]) stops counting as soon as the owner turns it off.
     */
    fun blocked(versionCode: Int): Boolean {
        if (prefs.getString(causeKey(versionCode), null) == CAUSE_IDENTITY && !runCatching(identityCheckOn).getOrDefault(true)) return false
        val left = prefs.getLong(untilKey(versionCode), 0L) - clock()
        return left in 1..MAX_BACKOFF_MS
    }

    /** One refusal recorded by [reject]: [count] so far, and how long the release now waits. */
    data class Refusal(val count: Int, val backoffMs: Long) {
        val first: Boolean get() = count == 1
    }

    /** Records one refusal of [versionCode], and its [cause] ([CAUSE_IDENTITY] or [CAUSE_INSTALL]). */
    fun reject(versionCode: Int, cause: String): Refusal {
        val count = prefs.getInt(countKey(versionCode), 0) + 1
        val backoff = (BASE_BACKOFF_MS shl (count - 1).coerceAtMost(3)).coerceAtMost(MAX_BACKOFF_MS)
        prefs.edit()
            .putInt(countKey(versionCode), count)
            .putLong(untilKey(versionCode), clock() + backoff)
            .putString(causeKey(versionCode), cause)
            .apply()
        return Refusal(count, backoff)
    }

    private fun countKey(versionCode: Int) = "rejected.$versionCode.count"
    private fun untilKey(versionCode: Int) = "rejected.$versionCode.until"
    private fun causeKey(versionCode: Int) = "rejected.$versionCode.cause"

    companion object {
        const val BASE_BACKOFF_MS = 24 * 60 * 60 * 1000L
        const val MAX_BACKOFF_MS = 7 * BASE_BACKOFF_MS

        /** [ApkIdentity] refused the bytes (another package, a wrong release). */
        const val CAUSE_IDENTITY = "identity"

        /** Android's installer refused the APK for a reason that will repeat ([OtaJournal.blocksRelease]). */
        const val CAUSE_INSTALL = "install"
    }
}