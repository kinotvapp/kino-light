package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arkiv.player.AppGraph
import com.arkiv.player.data.plugin.PluginFetcher
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Debug-only: lets the emulator install a plugin that isn't on GitHub yet (the updated example,
 * the test plugins) through the REAL install flow — address field, consent sheet, validation,
 * sandbox probe. While on, a manifest/script/icon Kino would fetch from
 * `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>` is read from
 * `files/debug-plugins/<owner>/<repo>/<path>` instead when that file exists (the `<ref>` is
 * ignored); anything else still goes to GitHub.
 *
 * ```
 * adb push <plugin folder> /data/local/tmp/kp
 * adb shell run-as com.arkiv.player.light sh -c 'mkdir -p files/debug-plugins/test/kino-plugin-demo && cp -r /data/local/tmp/kp/. files/debug-plugins/test/kino-plugin-demo/'
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.PluginSideloadProbe --ez on true
 * adb logcat -s KinoSideload
 * ```
 * Then type `test/kino-plugin-demo` in Ajustes ▸ Plugins. `--ez on false` turns it off.
 *
 * Lives in `src/debug`: a test tool, not a feature; a release APK has no way to set it.
 */
class PluginSideloadProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = AppGraph.from(context)
        val on = intent.getBooleanExtra("on", true)
        val root = File(context.filesDir, "debug-plugins")
        graph.debugPluginFetcher = if (on) LocalFolderFetcher(root) else null
        Log.w(TAG, if (on) "on: serving raw.githubusercontent.com paths from $root" else "off")
    }

    private class LocalFolderFetcher(private val root: File) : PluginFetcher {
        private val github = com.arkiv.player.data.plugin.RawGithubFetcher(okhttp3.OkHttpClient())

        override suspend fun fetch(url: String, maxBytes: Int): ByteArray {
            val parts = url.removePrefix("https://raw.githubusercontent.com/").split('/')
            if (!url.startsWith("https://raw.githubusercontent.com/") || parts.size < 4) return github.fetch(url, maxBytes)
            val file = File(root, (listOf(parts[0], parts[1]) + parts.drop(3)).joinToString("/"))
            if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw FileNotFoundException(url)
            if (!file.isFile) return github.fetch(url, maxBytes)
            if (file.length() > maxBytes) throw IOException("archivo demasiado grande")
            Log.w(TAG, "served ${file.relativeTo(root)}")
            return file.readBytes()
        }
    }

    private companion object { const val TAG = "KinoSideload" }
}
