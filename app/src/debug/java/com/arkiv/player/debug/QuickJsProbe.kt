package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only probe: does quickjs-kt's native library load and run on THIS device (ABI, page
 * size)? The JVM unit tests use desktop natives, so they can't answer that.
 *
 * ```
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.QuickJsProbe
 * adb logcat -s KinoQuickJsProbe
 * ```
 * Expected line: `ok version=… sum=2 async=fetched:x module=search`.
 *
 * Lives in `src/debug`: a measurement, not a feature; it must not exist in a release APK.
 */
class QuickJsProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                val js = QuickJs.create(Dispatchers.Default)
                try {
                    js.memoryLimit = 64L * 1024 * 1024
                    js.maxStackSize = 1024L * 1024
                    js.define("host") { asyncFunction("fetchText") { args -> delay(10); "fetched:" + args[0] } }
                    val sum = js.evaluate<Long>("1 + 1")
                    val async = js.evaluate<String>("await host.fetchText('x')")
                    js.addModule("plugin.js", "export async function search(q) { return [] }")
                    js.evaluate<Any?>(code = "import * as p from 'plugin.js'; globalThis.__e = p;", filename = "l.js", asModule = true)
                    runCatching { js.evaluate<Any?>("0") }
                    val exports = js.evaluate<String>("Object.keys(__e).join(',')")
                    Log.w(TAG, "ok version=${js.version} sum=$sum async=$async module=$exports")
                } finally {
                    js.close()
                }
            }.onFailure { Log.e(TAG, "FAILED", it) }
            pending.finish()
        }
    }

    private companion object { const val TAG = "KinoQuickJsProbe" }
}
