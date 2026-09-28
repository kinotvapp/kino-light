package com.arkiv.player.data.plugin

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asks the person, in the moment, whether a plugin may reach a host its manifest never declared. */
fun interface HostApprovalRequester {
    suspend fun request(pluginId: String, pluginName: String, host: String): Boolean
}

/** The default for anywhere with no UI to ask (the install-time probe, tests): never grants a host it wasn't told to. */
object NoHostApprovalRequester : HostApprovalRequester {
    override suspend fun request(pluginId: String, pluginName: String, host: String): Boolean = false
}

/** One pending dialog. [respond] resumes the coroutine that's waiting inside [HostApprovalCenter.request]. */
data class HostApprovalRequest(val pluginId: String, val pluginName: String, val host: String, val respond: (Boolean) -> Unit)

/**
 * Bridges the coroutine inside [PluginHttp] (running on [kotlinx.coroutines.Dispatchers.IO], deep in
 * a plugin call) to the Compose dialog collected from [pending] at the app's root. [mutex] means a
 * second plugin's request simply waits its turn instead of showing a second dialog on top of the
 * first -- see the spec's "aprobación reactiva" section for why one at a time is enough (a
 * [PluginRuntime] only ever runs one call at a time for a given plugin; two DIFFERENT plugins racing
 * here is rare, and queueing costs them nothing they weren't already going to spend waiting).
 */
class HostApprovalCenter : HostApprovalRequester {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<HostApprovalRequest?>(null)
    val pending: StateFlow<HostApprovalRequest?> = _pending.asStateFlow()

    override suspend fun request(pluginId: String, pluginName: String, host: String): Boolean = mutex.withLock {
        suspendCancellableCoroutine { cont ->
            val req = HostApprovalRequest(pluginId, pluginName, host) { approved ->
                _pending.value = null
                if (cont.isActive) cont.resume(approved) {}
            }
            cont.invokeOnCancellation { _pending.value = null }
            _pending.value = req
        }
    }
}
