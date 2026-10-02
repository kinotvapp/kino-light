package com.arkiv.player.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts the companion sync on every foreground start without building its dependencies on the
 * main thread (ERRORES-AHE): the plugin-secret table forces `pluginRegistry`'s first `reload()`
 * (every plugin's files), which 0.9.45 built inside `onStart` on the UI thread on 73+ devices.
 * [resolve] runs on [io]; [start] runs back on [main] (where `startSync` always ran), and only if
 * no [stop] came in between: a start/stop/start in quick succession never leaves an engine running
 * for a backgrounded app. A [resolve] that throws is reported and the sync starts without the
 * secrets ([fallback]) instead of crashing the process from a lifecycle callback.
 */
internal class CompanionSyncStarter<D>(
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val resolve: () -> D,
    private val fallback: () -> D,
    private val start: (D) -> Unit,
    private val stopSync: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var pending: Job? = null

    /** Main thread only, like the lifecycle callbacks that call it. */
    fun start() {
        pending?.cancel()
        pending = scope.launch(main) {
            val deps = try {
                withContext(io) { resolve() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
                withContext(io) { fallback() }
            }
            start(deps)
        }
    }

    /** Main thread only. Cancels a start still resolving, then stops a running engine. */
    fun stop() {
        pending?.cancel()
        pending = null
        stopSync()
    }
}
