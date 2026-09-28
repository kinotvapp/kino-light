package com.arkiv.player.data.update

/** One published release, as its OTA manifest describes it. [sha256] is lowercase hex, or empty for a legacy manifest without one. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val sha256: String = "",
)

/**
 * What an OTA check found. [Failed] is NOT "up to date": no source could be read, so the answer is
 * unknown -- the UI says so and the automatic path simply tries again later.
 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    /** [reason] is the first source's failure class: `offline`, `dns`, `tls`, `timeout`, `http_<code>`, `parse`, `io` or `error`. */
    data class Failed(val reason: String) : UpdateCheckResult
}
