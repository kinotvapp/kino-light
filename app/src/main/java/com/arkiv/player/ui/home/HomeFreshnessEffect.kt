package com.arkiv.player.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Phone and TV Home: on every resume, and every [HomeFreshness.VISIBLE_CHECK_EVERY_MS] while Home
 * stays resumed (a TV left on), asks [vm] to re-ask for its rows if they're stale
 * ([HomeViewModel.refreshIfStale] decides; never a forced pass). Stops while Home isn't resumed.
 */
@Composable
fun HomeFreshnessEffect(vm: HomeViewModel) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, vm) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshIfStale()
                delay(HomeFreshness.VISIBLE_CHECK_EVERY_MS)
            }
        }
    }
}
