package com.arkiv.player.data.update

import java.security.MessageDigest

/**
 * Staged rollout: a manifest's `"rollout": <percent>` offers a release only to the devices whose [bucket] (0..99,
 * stable per device and release) is below it, so a release can go to 10 % first and be raised to 100 % by editing
 * `latest.json`, without a rebuild. Absent or invalid = 100 ([OtaManifest.parse]).
 *
 * The bucket mixes the release base in, so the same 10 % of devices are not always the first ones.
 */
object OtaRollout {
    const val ALL = 100

    /** A `rollout` the manifest carries but that is not a percent: below every bucket, so nobody is offered it. */
    const val INVALID = -1

    /** [deviceKey] is an app-scoped, already hashed device id; [base] the release ([OtaVersion.baseOf]). */
    fun bucket(deviceKey: String, base: Int): Int {
        val d = MessageDigest.getInstance("SHA-256").digest("kino-ota-rollout:$deviceKey:$base".toByteArray(Charsets.UTF_8))
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 100).toInt()
    }

    fun includes(bucket: Int, rollout: Int): Boolean = bucket < rollout
}