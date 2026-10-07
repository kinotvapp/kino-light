package com.arkiv.player.data.update

/**
 * One published release as THIS device will install it: the APK of its ABI (or the universal one, see
 * [OtaManifest.forDevice]). [versionCode] is that APK's own code (compare releases with [OtaVersion.isNewer],
 * never raw codes). [sha256] is lowercase hex, or empty for a legacy manifest without one. [alternates] are
 * the other copies to try, in order, when [url] fails: the same bytes on other mirrors, then the universal
 * APK's copies (another file, so each copy carries its own sha256).
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val sha256: String = "",
    val abi: String = OtaAbi.UNIVERSAL,
    val alternates: List<ApkCopy> = emptyList(),
)

/**
 * What an OTA check found. [Failed] is NOT "up to date": no source could be read, so the answer is
 * unknown -- the UI says so and the automatic path simply tries again later.
 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    /** [source] is the manifest source that answered (telemetry), [rollout] its staged-rollout percent. */
    data class Available(val info: UpdateInfo, val source: String = "", val rollout: Int = OtaRollout.ALL) : UpdateCheckResult

    /** A newer release exists but this device's [bucket] is outside its [rollout] (see [OtaRollout]): not offered yet. */
    data class HeldBack(val versionCode: Int, val source: String, val rollout: Int, val bucket: Int) : UpdateCheckResult
    /** [reason] is the first source's failure class: `offline`, `dns`, `tls`, `timeout`, `http_<code>`, `parse`, `io` or `error`. */
    data class Failed(val reason: String) : UpdateCheckResult
}