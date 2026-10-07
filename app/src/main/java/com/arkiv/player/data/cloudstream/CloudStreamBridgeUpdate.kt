package com.arkiv.player.data.cloudstream

/**
 * Stub for kino-light: the full CloudStream complement logic (download, install, identity check
 * against Kino's own signers) lives in kino-app and requires the `bridge-contract` AIDL module,
 * which kino-light does not have. Without a CloudStream plugin installed there is nothing to check,
 * so this stub is a clean no-op: `needsUpdate()` always answers false, and the manifest's `bridge`
 * entry is ignored (kino-light clients are < 0.9.54 and the parser already drops unknown keys).
 *
 * When kino-light ships the CloudStream bridge (a real install path for the complement), replace
 * this object with kino-app's `CloudStreamBridgeUpdate` and add the bridge-contract module to
 * `app/build.gradle.kts`.
 */
object CloudStreamBridgeUpdate {
    /** Always false in kino-light: the bridge is not part of this fork's OTA. */
    suspend fun needsUpdate(): Boolean = false
}