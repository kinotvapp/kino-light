package com.arkiv.player.data.plugin

/**
 * Whether the person must pick a source before reaching the app: activated, and no plugin that is
 * both usable (enabled, not damaged, not unresponsive) and set up (no required setting empty).
 */
fun needsSource(activated: Boolean, plugins: List<InstalledPlugin>): Boolean =
    activated && plugins.none { it.isUsable && !it.needsSetup }
