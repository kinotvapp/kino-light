package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arkiv.player.AppGraph
import com.arkiv.player.data.credentials.NativeSealAgreement
import com.arkiv.player.data.plugin.SealException
import com.arkiv.player.data.plugin.SealedSecrets

/**
 * Debug-only: checks the native X25519 behind plugin seals on a real device.
 *
 * ```
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.SealSelfTestProbe
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.SealSelfTestProbe \
 *     --es seal kino-sealed:v1:... --es binding owner/repo --es name k
 * adb logcat -d -s KinoSeal
 * ```
 * Always logs `selfTest=<bool>` (RFC 7748 §6.1 through the native code). With all three extras it
 * also opens the seal with the production key and logs `opened=<bool> length=<n>`: never the value.
 *
 * Lives in `src/debug`: a test tool, not a feature; a release APK has no way to trigger it.
 */
class SealSelfTestProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "selfTest=${NativeSealAgreement.selfTest()}")
        val seal = intent.getStringExtra("seal") ?: return
        val binding = intent.getStringExtra("binding") ?: return
        val name = intent.getStringExtra("name") ?: return
        val opened = runCatching { SealedSecrets.open(seal, binding, name, AppGraph.from(context).sealAgreement) }
        // A SealException's message is one of SealedSecrets' fixed strings (never a value); any
        // other failure logs its class name only.
        val error = opened.exceptionOrNull()?.let { if (it is SealException) "\"${it.message}\"" else it.javaClass.simpleName }
        Log.w(TAG, "opened=${opened.isSuccess} length=${opened.getOrNull()?.length ?: 0}" + (error?.let { " error=$it" } ?: ""))
    }

    private companion object {
        const val TAG = "KinoSeal"
    }
}
