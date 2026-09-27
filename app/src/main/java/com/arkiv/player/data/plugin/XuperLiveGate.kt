package com.arkiv.player.data.plugin

/**
 * A source of the NATIVE live channels (the "En vivo" tab, the Home "Canales en vivo" row, the TV
 * guide and the player's channel drawer). Today there is exactly one: [XUPER], the portal's
 * channels played through `LiveHlsProxy`. Caracol's live channels are not here -- they live in
 * their own section and never depended on a plugin.
 *
 * Named as a set of providers, not a single on/off switch, so a later "any plugin can add
 * channels" module can add entries here instead of reworking every surface that asks.
 */
enum class LiveProvider { XUPER }

/**
 * The live providers the installed plugins switch on right now. Pure: every surface derives from
 * this (through `AppGraph.xuperLive`), and the player's hard stop reads [XuperLiveGate.blockedMessage].
 */
fun liveProviders(plugins: List<InstalledPlugin>): Set<LiveProvider> =
    if (plugins.any { XuperLiveGate.opens(it) }) setOf(LiveProvider.XUPER) else emptySet()

/** Whether the native Xuper live channels are on: see [XuperLiveGate]. */
fun xuperLiveAllowed(plugins: List<InstalledPlugin>): Boolean = LiveProvider.XUPER in liveProviders(plugins)

/**
 * The native Xuper live channels appear only while the recognized Xuper plugin install
 * ([XuperPrivilege.grants], by its install address -- never re-implemented here) is enabled and not
 * damaged. The user's rule (2026-09-27): "si detecta el plugin se activan y si no, no aparecen".
 *
 * Deliberately NOT [InstalledPlugin.isUsable]: "unresponsive" is set by VOD script timeouts, and live
 * never runs the plugin's script, so a slow VOD call must not take the channels away. Nor does a
 * missing setting matter (the Xuper plugin has none that live reads).
 *
 * Turning it off hides every surface but keeps the data (favourites, recents, the channel cache):
 * all of it comes back when the plugin is switched on again.
 */
object XuperLiveGate {
    /** The name used in the message when no Xuper install exists to take its name from. */
    private const val DEFAULT_NAME = "Xuper"

    internal fun opens(plugin: InstalledPlugin): Boolean =
        XuperPrivilege.grants(plugin.record) && plugin.record.enabled && !plugin.record.damaged

    /**
     * Null while live is allowed; otherwise what the player shows when a channel is asked for
     * anyway (a recent, a companion send, a deep link): the same wording as `PluginAccess`'s
     * messages, about a channel instead of "esto".
     */
    fun blockedMessage(plugins: List<InstalledPlugin>): String? {
        if (xuperLiveAllowed(plugins)) return null
        val installed = plugins.firstOrNull { XuperPrivilege.grants(it.record) }
        val name = installed?.manifest?.name?.ifBlank { null } ?: DEFAULT_NAME
        return when {
            installed == null -> "Instala el plugin $DEFAULT_NAME para ver este canal"
            installed.record.damaged -> "El plugin $name tiene archivos dañados, reinstálalo"
            else -> "Activa el plugin $name para ver este canal"
        }
    }
}
