package com.arkiv.player.ui.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One plugin playback preparing at a time: a second tap while the first one resolves is ignored,
 * so a double tap doesn't play (and navigate) twice. The phone's counterpart of the TV Home's
 * `preparingPlugin`. Used from the main thread only.
 */
internal class SinglePlayback(private val scope: CoroutineScope) {
    private var preparing = false

    /** Runs [block] unless one is already running; returns whether it started. */
    fun start(block: suspend () -> Unit): Boolean {
        if (preparing) return false
        preparing = true
        scope.launch {
            try {
                block()
            } finally {
                preparing = false
            }
        }
        return true
    }
}
