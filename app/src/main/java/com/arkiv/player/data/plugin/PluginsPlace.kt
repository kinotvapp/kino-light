package com.arkiv.player.data.plugin

/**
 * Where the person manages their plugins, as the copy names it. On the phone the Plugins screen is an
 * item of the drawer ("Plugins, en el menú"); on the TV it is still a tab of Ajustes ("Ajustes ▸ Plugins").
 *
 * Screens that know which root they are in pass [of] explicitly. Messages worded deep in the data layer
 * (a plugin's typed error, a live channel's notice) read [current], which follows the root MainActivity
 * composed ([onTv], set there before the first frame; `ArkivApp` seeds it with the device's own kind).
 */
object PluginsPlace {
    const val PHONE = "Plugins, en el menú"
    const val TV = "Ajustes ▸ Plugins"

    fun of(isTv: Boolean): String = if (isTv) TV else PHONE

    /** Whether the TV root is the one on screen. */
    @Volatile var onTv: Boolean = false

    val current: String get() = of(onTv)
}
