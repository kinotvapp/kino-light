package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val idleMs: Long = 5 * 60_000L,
    private val maxConsecutiveTimeouts: Int = 3,
) : PluginCaller {
    private class Slot {
        val mutex = Mutex()
        var runtime: ScriptRuntime? = null
        var timeouts = 0
        var idleJob: Job? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
        val slot = slots.getOrPut(pluginId) { Slot() }
        return slot.mutex.withLock {
            slot.idleJob?.cancel()
            try {
                val runtime = slot.runtime?.takeUnless { it.isDiscarded } ?: open(pluginId).also { slot.runtime = it }
                beforeCall(pluginId)
                runtime.call(function, argJson, timeoutMs).also { slot.timeouts = 0 }
            } catch (e: PluginTimeoutException) {
                slot.runtime = null
                if (++slot.timeouts >= maxConsecutiveTimeouts) {
                    slot.timeouts = 0
                    onUnresponsive(pluginId)
                }
                throw e
            } finally {
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
}
