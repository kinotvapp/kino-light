package com.arkiv.player.data.update

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    /**
     * Expected SHA-256 of the APK at [url], lowercase hex, from `latest.json`. Empty when the JSON
     * omits it (older channels). When present, [ApkDownloader] verifies the downloaded file against
     * it and refuses to install on a mismatch -- catches a truncated/corrupt download or an error
     * page served instead of the APK (e.g. an archive.org item still propagating). It is an
     * INTEGRITY check, not authenticity: the APK signature (release keystore) is what actually gates
     * installs.
     */
    val sha256: String = "",
)
