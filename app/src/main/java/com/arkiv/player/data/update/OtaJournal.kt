package com.arkiv.player.data.update

/**
 * The OTA events that are not one component's own failure ([OtaTelemetry]): `offered` once per release, `updated` on
 * the first start after an update, and the installer handoff (`install`, plus `given_up` when the installer refused the
 * APK: the same file would fail the same way, so the release waits out [OtaRejections]' backoff).
 */
class OtaJournal(
    private val ledger: OtaLedger,
    private val rejections: OtaRejections,
    private val telemetry: OtaTelemetry,
    private val bucketOf: (base: Int) -> Int,
    private val sdk: Int,
) {
    /** A check's answer: reports `offered` (reason `in_rollout` / `held_back`) the first time a release is seen. */
    fun offered(result: UpdateCheckResult) {
        val (code, source, rollout, inRollout) = when (result) {
            is UpdateCheckResult.Available -> Offer(result.info.versionCode, result.source, result.rollout, true)
            is UpdateCheckResult.HeldBack -> Offer(result.versionCode, result.source, result.rollout, false)
            else -> return
        }
        val base = OtaVersion.baseOf(code)
        if (!ledger.firstOffer(base)) return
        telemetry.send(
            "offered", when {
                rollout == OtaRollout.INVALID -> "invalid_rollout"
                inRollout -> "in_rollout"
                else -> "held_back"
            },
            mapOf("versionCode" to code.toString(), "source" to source, "rollout" to rollout.toString(), "bucket" to bucketOf(base).toString()),
        )
    }

    private data class Offer(val code: Int, val source: String, val rollout: Int, val inRollout: Boolean)

    /** At app start, before the pending update is cleared: `updated` once when this start follows an update. */
    fun started(current: Int, pendingBase: Int?, abi: String) {
        val u = ledger.onStart(current, pendingBase) ?: return
        telemetry.send(
            "updated", "ota",
            mapOf(
                "from" to u.from.toString(), "to" to u.to.toString(), "abi" to abi, "source" to u.source,
                "attempts" to u.attempts.toString(), "bucket" to bucketOf(OtaVersion.baseOf(current)).toString(),
                "sdk" to sdk.toString(),
            ),
        )
    }

    /**
     * The installer handoff for [info] ended in [outcome] (`cancelled`, `failed`, `no_installer`, `file_missing`), with
     * [extras] (`method`, `unknown_apps`, `install_result`). A failure gives the release up on this device only when
     * [installResult] says the same file will fail the same way ([blocksRelease]); anything else (no room, the person
     * declining Play Protect's warning) can be retried at once. True when the release is now given up.
     */
    fun install(info: UpdateInfo, outcome: String, extras: Map<String, String>, installResult: Int = 0): Boolean {
        val base = OtaVersion.baseOf(info.versionCode)
        val common = mapOf(
            "versionCode" to info.versionCode.toString(), "abi" to info.abi, "bucket" to bucketOf(base).toString(), "sdk" to sdk.toString(),
        )
        telemetry.send("install", outcome, common + extras)
        if (outcome != "failed" || !blocksRelease(installResult)) return false
        val refusal = rejections.reject(base, OtaRejections.CAUSE_INSTALL)
        telemetry.send(
            "given_up", "install_failed",
            common + mapOf("base" to base.toString(), "count" to refusal.count.toString(), "backoff_h" to (refusal.backoffMs / 3_600_000).toString()),
        )
        return true
    }

    companion object {
        /** `PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE`. */
        const val INSUFFICIENT_STORAGE = -4

        /**
         * Installer codes that will repeat with the same file: `INSTALL_FAILED_INVALID_APK` (-2),
         * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (-7, another signing key), `INSTALL_PARSE_FAILED_NO_CERTIFICATES` (-103),
         * `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES` (-104).
         */
        private val PERMANENT = setOf(-2, -7, -103, -104)

        fun blocksRelease(installResult: Int): Boolean = installResult in PERMANENT
    }
}