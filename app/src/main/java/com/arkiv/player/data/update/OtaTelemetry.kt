package com.arkiv.player.data.update

/**
 * Every OTA stage's report, one stable shape: [event] is the stage, [reason] a low-cardinality word (the issue's
 * fingerprint is `ota/<event>/<reason>`, so GlitchTip opens one issue per failure kind, not per device or value), and
 * [extras] the detail. Never a URL, an id or anything personal.
 *
 * Events:
 * - `check_failed` (reason = first source's class): `github`/`jsdelivr`/`unpkg`/`archive` classes, `trigger`.
 * - `offered` (reason `in_rollout`/`held_back`), once per release per device: `versionCode`, `source` (the manifest that
 *   won), `rollout`, `bucket`.
 * - `download_failed` (reason `refused`, `disk_full` or the first copy's class): `versionCode`, `abi`, per-host classes,
 *   `copies` (per copy `host:class:gotKB/expectedKB:ms`), `tries`, the identity facts, `bucket`.
 * - `identity` (reason `refused_<why>` or `skipped_remote`): what [ApkIdentity] read.
 * - `identity_observed` (reason `empty` / `differ`), never blocking: the signers as the app read them, to find why
 *   0.9.51 saw a mismatch nobody can reproduce from outside the app.
 * - `install` (reason `cancelled`, `failed`, `no_installer`, `file_missing`): `method`, `unknown_apps`, `install_result`.
 * - `given_up` (reason = why): `base`, `count`, `backoff_h` -- the release is not downloaded again for a while.
 * - `updated` (reason `ota`), once on the first start after an update: `from`, `to`, `abi`, `source`, `attempts`, `bucket`.
 */
fun interface OtaTelemetry {
    fun send(event: String, reason: String, extras: Map<String, String>)

    companion object {
        /**
         * The default implementation for kino-light's demo build: writes to Logcat with the tag
         * "KinoOta" (and never to a crash board, never to disk). The real kino-app wires this to
         * Sentry/GlitchTip; the demo skips that. Adding crash reporting later means swapping this
         * for a board-backed implementation -- the field names stay the same.
         */
        val LOGCAT = OtaTelemetry { event, reason, extras ->
            // Logcat has a length cap per line (~4 KB). Trim the extras to fit the most important keys.
            val extrasShort = extras.entries.joinToString(" ") { (k, v) -> "$k=$v" }.take(2_000)
            android.util.Log.w("KinoOta", "ota: $event reason=$reason $extrasShort")
        }
    }
}