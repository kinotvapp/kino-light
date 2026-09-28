package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arkiv.player.AppGraph
import kotlinx.coroutines.launch

/**
 * Debug-only: fires a FAKE host-approval request so the reactive dialog (collected from
 * [com.arkiv.player.data.plugin.HostApprovalCenter.pending] at `MainActivity`'s Compose root) can
 * be exercised without a real plugin's `kino.fetch` actually hitting an undeclared host.
 *
 * ```
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.HostApprovalProbe \
 *     --es pluginId demo --es pluginName "Demo Plugin" --es host api.ejemplo-debug.test
 * adb logcat -s KinoHostApproval
 * ```
 * All three extras are optional; whichever is left out falls back to a `DEFAULT_*` below. The
 * broadcast itself returns right away -- the request only resolves once the dialog is answered (or
 * dismissed), so the "answered" line can take as long as the person takes to tap something.
 *
 * Lives in `src/debug`: a test tool, not a feature; a release APK has no way to trigger it.
 */
class HostApprovalProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pluginId = intent.getStringExtra("pluginId")?.takeIf { it.isNotBlank() } ?: DEFAULT_PLUGIN_ID
        val pluginName = intent.getStringExtra("pluginName")?.takeIf { it.isNotBlank() } ?: DEFAULT_PLUGIN_NAME
        val host = intent.getStringExtra("host")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        val graph = AppGraph.from(context)
        Log.w(TAG, "requesting approval · pluginId=$pluginId pluginName=$pluginName host=$host")
        // On the app's own scope (lives as long as the process, cancelled with it): the request
        // suspends until the dialog is answered, which can be well past onReceive's ~10 s window.
        graph.applicationScope.launch {
            val approved = graph.hostApprovalCenter.request(pluginId, pluginName, host)
            Log.w(TAG, "answered · approved=$approved")
        }
    }

    private companion object {
        const val TAG = "KinoHostApproval"
        const val DEFAULT_PLUGIN_ID = "debug-probe"
        const val DEFAULT_PLUGIN_NAME = "Sonda de aprobación"
        const val DEFAULT_HOST = "api.ejemplo-debug.test"
    }
}
