package com.arkiv.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.AppGraph
import com.arkiv.player.ArkivApp

/** Access to the dependency graph from composables. */
@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return (context.applicationContext as ArkivApp).graph
}

/** Formats milliseconds to m:ss or h:mm:ss. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
