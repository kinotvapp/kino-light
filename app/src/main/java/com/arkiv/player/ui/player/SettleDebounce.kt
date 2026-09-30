package com.arkiv.player.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the LAST of a burst of calls once nothing new has come for [settleMs]. Fast zapping on a plugin's live channels asks the
 * plugin to resolve every channel the person flies past; each cancelled call makes the plugin pool discard its runtime, so the
 * next one starts from a cold script, and the orphaned work piles up. Letting the burst settle sends the plugin one call.
 */
internal class SettleDebounce(private val scope: CoroutineScope, private val settleMs: Long) {
    private var job: Job? = null

    fun run(action: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(settleMs)
            action()
        }
    }

    /** Drops what is pending, for an open that goes straight through. */
    fun cancel() {
        job?.cancel()
        job = null
    }
}
