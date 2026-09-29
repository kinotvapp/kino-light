package com.arkiv.player.data.plugin

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Asks the person, in the moment, whether a plugin may reach a host its manifest never declared. */
fun interface HostApprovalRequester {
    /**
     * true = allowed, false = refused (remembered), null = no answer in time (NOT remembered: the
     * next miss asks again). How long "in time" is belongs to the implementation, see
     * [HostApprovalCenter.TIMEOUT_MS].
     */
    suspend fun request(pluginId: String, pluginName: String, host: String): Boolean?
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
 * here is rare).
 *
 * The [timeoutMs] window starts only once THIS request holds [mutex] and is about to be shown:
 * time spent queued behind another plugin's dialog doesn't come out of it. What does keep running
 * meanwhile is the plugin call's own whole-call limit (`PluginRuntime.call`'s `withTimeout`, 15 s
 * for `search`, 20 s for the rest) -- that clock isn't paused (a documented follow-up). So a person
 * slow to answer can still see the call time out; [wasAsking] is how `PluginRuntimePool` keeps such
 * a timeout from counting toward the plugin's "No responde".
 */
class HostApprovalCenter(
    private val timeoutMs: Long = TIMEOUT_MS,
    /** Monotonic, same unit as the `since` [wasAsking] is given: `System.nanoTime`. */
    private val clock: () -> Long = System::nanoTime,
) : HostApprovalRequester {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<HostApprovalRequest?>(null)
    val pending: StateFlow<HostApprovalRequest?> = _pending.asStateFlow()

    /** Per plugin: requests queued or showing right now. */
    private val asking = ConcurrentHashMap<String, Int>()
    /** Per plugin: [clock] when its last request finished (answered, timed out or cancelled). */
    private val lastAsked = ConcurrentHashMap<String, Long>()

    override suspend fun request(pluginId: String, pluginName: String, host: String): Boolean? {
        asking.merge(pluginId, 1, Int::plus)
        try {
            return mutex.withLock {
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        var req: HostApprovalRequest? = null
                        req = HostApprovalRequest(pluginId, pluginName, host) { approved ->
                            _pending.compareAndSet(req, null)
                            if (cont.isActive) cont.resume(approved) {}
                        }
                        cont.invokeOnCancellation { _pending.compareAndSet(req, null) }
                        if (cont.isActive) {
                            _pending.value = req
                            // A cancellation landing between the check above and the publish ran
                            // its handler BEFORE `req` was published, so that handler's CAS found
                            // nothing to clear: re-check, or the dialog would stay up, orphaned,
                            // until some unrelated later request overwrote it.
                            if (!cont.isActive) _pending.compareAndSet(req, null)
                        }
                    }
                }
            }
        } finally {
            lastAsked[pluginId] = clock()
            asking.computeIfPresent(pluginId) { _, n -> (n - 1).takeIf { it > 0 } }
        }
    }

    /**
     * Whether [pluginId] had a host prompt queued or on screen at any moment since [since] (a
     * [clock] reading taken when a call began): its call may have run out of time because a
     * person was deciding, not because the plugin hung.
     */
    fun wasAsking(pluginId: String, since: Long): Boolean =
        (asking[pluginId] ?: 0) > 0 || lastAsked[pluginId]?.let { it - since >= 0 } == true

    companion object {
        /** How long a shown prompt waits for an answer before the fetch behind it fails as `timeout` (not remembered). */
        const val TIMEOUT_MS = 12_000L
    }
}
