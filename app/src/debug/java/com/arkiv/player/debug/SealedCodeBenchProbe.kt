package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arkiv.player.AppGraph
import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.SealedCode
import com.arkiv.player.data.plugin.SealedSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only: times opening an installed sealed plugin's entry, off Main, several times in a row (the
 * first run pays class loading and JCA provider set-up), plus the X25519 step and the signature check
 * on their own. Logs durations and sizes only, never a byte of the script.
 *
 * ```
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.SealedCodeBenchProbe --es id sealed-1m
 * adb logcat -d -s KinoSealedBench
 * ```
 */
class SealedCodeBenchProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val runs = intent.getIntExtra("runs", 5)
        val graph = AppGraph.from(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stored = graph.pluginStore.get(id) ?: run { Log.w(TAG, "$id not installed"); return@launch }
                val blob = graph.pluginStore.readVerifiedEntry(id)
                val address = PluginAddress.parse(stored.record.address)!!
                val binding = SealedSecrets.bindingOf(address)
                repeat(runs) { i ->
                    var t = System.nanoTime()
                    val eph = blob.copyOfRange(SealedCode.HEADER_BYTES, SealedCode.HEADER_BYTES + 32)
                    graph.sealAgreement.sharedSecret(eph).fill(0)
                    val agreeMs = (System.nanoTime() - t) / 1e6
                    t = System.nanoTime()
                    val script = SealedCode.open(blob, binding, id, graph.sealAgreement)
                    val openMs = (System.nanoTime() - t) / 1e6
                    // The same open with the X25519 result already in hand: HKDF + GCM + inflate + String only.
                    val shared = graph.sealAgreement.sharedSecret(eph)
                    t = System.nanoTime()
                    SealedCode.open(blob, binding, id, { shared.copyOf() })
                    val restMs = (System.nanoTime() - t) / 1e6
                    Log.w(TAG, "[$id] run $i: open %.1f ms = native x25519 (alone %.1f ms) + JCA/inflate/String %.1f ms; blob %d B -> %d chars".format(openMs, agreeMs, restMs, blob.size, script.length))
                }
                repeat(3) {
                    val st = System.nanoTime()
                    val ok = com.arkiv.player.data.credentials.NativeSealAgreement.selfTest()
                    Log.w(TAG, "native self-test (4 bare X25519, no instrumentation scan) %.1f ms ok=%b".format((System.nanoTime() - st) / 1e6, ok))
                }
                val t = System.nanoTime()
                SealedCode.verifiedAuthorKey(blob)
                Log.w(TAG, "[$id] signature check (install/update only) %.1f ms".format((System.nanoTime() - t) / 1e6))
            } catch (e: Exception) {
                Log.w(TAG, "[$id] failed: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "KinoSealedBench" }
}
