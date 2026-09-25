package com.arkiv.player.data.plugin

import java.io.File

/**
 * Catches a plugin that kills the app (a native crash in the JS engine, an out-of-memory kill):
 * nothing in-process can, so it leaves a trace on disk instead.
 *
 * [PluginRuntimePool] writes `plugin-data/<id>/.inflight` before each top-level call (including
 * the runtime load it may trigger) and deletes it whenever control comes back to Kotlin: success,
 * exception, timeout, cancellation. A marker still there at the next start means the process died
 * inside that plugin: [recover] counts it in `.unclean` and deletes it, and [threshold] such exits
 * in a row switch the plugin off (the registry's "no responde"). A call that completes normally
 * resets the count. The install/update probe doesn't go through the pool, so it's never counted.
 * Uninstalling deletes the plugin's data dir, these files included.
 */
class PluginCrashSentinel(private val dataRoot: File, private val threshold: Int = 2) {
    fun begin(pluginId: String) {
        runCatching { File(dataRoot, pluginId).apply { mkdirs() }.let { File(it, INFLIGHT_FILE).createNewFile() } }
    }

    fun end(pluginId: String, completedNormally: Boolean) {
        val dir = File(dataRoot, pluginId)
        runCatching { File(dir, INFLIGHT_FILE).delete() }
        if (completedNormally) runCatching { File(dir, UNCLEAN_FILE).delete() }
    }

    /** Once per process, before any plugin call: the ids that just reached [threshold]. */
    fun recover(): List<String> {
        val dirs = dataRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val marker = File(dir, INFLIGHT_FILE)
            if (!marker.exists()) return@mapNotNull null
            val counter = File(dir, UNCLEAN_FILE)
            val count = (runCatching { counter.readText().trim().toInt() }.getOrNull() ?: 0) + 1
            marker.delete()
            if (count >= threshold) {
                // Switched off now; if the person re-enables it, it starts from a clean count.
                counter.delete()
                dir.name
            } else {
                runCatching { writeFileAtomically(counter, count.toString().toByteArray()) }
                null
            }
        }
    }

    companion object {
        const val INFLIGHT_FILE = ".inflight"
        const val UNCLEAN_FILE = ".unclean"
    }
}
