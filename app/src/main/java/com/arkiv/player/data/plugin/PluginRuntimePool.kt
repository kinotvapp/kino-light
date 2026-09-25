package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Calls one exported function of one plugin. */
fun interface PluginCaller {
    suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String
}

/**
 * One lazily-opened runtime per plugin, calls serialized per plugin, closed after [idleMs] without
 * calls. A timeout discards the runtime (the next call opens a fresh one); [maxConsecutiveTimeouts]
 * in a row call [onUnresponsive] — the registry then disables the plugin as "no responde".
 *
 * [beforeCall] runs once per top-level call, inside the per-plugin mutex, right before the runtime
 * answers: AppGraph wires it to that plugin's `PluginHttp.beginCall()`, so the 60-request-per-call
 * budget resets between calls instead of accumulating across the runtime's whole lifetime, and the
 * mutex means two concurrent calls to the same plugin can never interleave and clobber each other's
 * count.
 */
class PluginRuntimePool(
    private val open: suspend (pluginId: String) -> ScriptRuntime,
    private val onUnresponsive: (pluginId: String) -> Unit,
    private val scope: CoroutineScope,
    private val beforeCall: (pluginId: String) -> Unit = {},
    private val idleMs: Long = DEFAULT_IDLE_MS,
    private val maxConsecutiveTimeouts: Int = DEFAULT_MAX_TIMEOUTS,
    /** Leaves an on-disk trace around each call so a plugin that kills the app gets switched off. */
    private val sentinel: PluginCrashSentinel? = null,
    /** Where the sentinel's small file writes run. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PluginCaller {
    private class Slot {
        val mutex = Mutex()
        var runtime: ScriptRuntime? = null
        var timeouts = 0
        var idleJob: Job? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    private val recovery = Mutex()
    private var recovered = false

    /** Plugins the start-up recovery just switched off: their first call in this process is refused. */
    private val switchedOff = ConcurrentHashMap.newKeySet<String>()

    /**
     * The sentinel's start-up check, once per process and before any plugin call: leftover markers
     * are the previous process dying inside a plugin. Runs on [io], not on whoever calls first.
     */
    private suspend fun recoverOnce() {
        val s = sentinel ?: return
        // NonCancellable: recover() deletes the markers and the counter it reads, so once it runs
        // the switch-off must happen too, even if the call that triggered it was cancelled (a
        // superseded search); and `recovered` is set only after that, so nothing is ever skipped.
        withContext(NonCancellable) {
            recovery.withLock {
                if (recovered) return@withLock
                withContext(io) { s.recover() }.forEach { id ->
                    switchedOff += id
                    onUnresponsive(id)
                }
                recovered = true
            }
        }
    }

    override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
        recoverOnce()
        // The caller picked this plugin before the recovery switched it off: don't run it a third time.
        if (switchedOff.remove(pluginId)) throw PluginScriptException("El plugin cerró Kino dos veces seguidas y se desactivó")
        val slot = slots.getOrPut(pluginId) { Slot() }
        return slot.mutex.withLock {
            slot.idleJob?.cancel()
            var completedNormally = false
            try {
                sentinel?.let { s -> withContext(io) { s.begin(pluginId) } }
                val runtime = slot.runtime?.takeUnless { it.isDiscarded } ?: open(pluginId).also { slot.runtime = it }
                beforeCall(pluginId)
                runtime.call(function, argJson, timeoutMs).also {
                    slot.timeouts = 0
                    completedNormally = true
                }
            } catch (e: PluginTimeoutException) {
                slot.runtime = null
                if (++slot.timeouts >= maxConsecutiveTimeouts) {
                    slot.timeouts = 0
                    onUnresponsive(pluginId)
                }
                throw e
            } finally {
                // Control is back in Kotlin whatever happened: the process survived this call.
                sentinel?.let { s -> withContext(NonCancellable + io) { s.end(pluginId, completedNormally) } }
                slot.idleJob = scope.launch {
                    delay(idleMs)
                    slot.mutex.withLock {
                        slot.runtime?.close()
                        slot.runtime = null
                    }
                }
            }
        }
    }

    /** After disable, uninstall or update: the next call loads the plugin from disk again. */
    fun close(pluginId: String) {
        val slot = slots.remove(pluginId) ?: return
        slot.idleJob?.cancel()
        scope.launch {
            slot.mutex.withLock {
                slot.runtime?.close()
                slot.runtime = null
            }
        }
    }

    fun closeAll() = slots.keys.toList().forEach(::close)

    companion object {
        const val DEFAULT_IDLE_MS = 5 * 60_000L
        const val DEFAULT_MAX_TIMEOUTS = 3
    }
}
