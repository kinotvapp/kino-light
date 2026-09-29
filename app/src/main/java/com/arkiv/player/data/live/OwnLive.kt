package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.plugin.EffectiveHosts
import com.arkiv.player.data.plugin.PluginAccess

/**
 * "Mis canales": the person's own live sources as a built-in provider of the En vivo module.
 *
 * It wears the shape of a plugin (plugin id [PLUGIN_ID], reserved in `ManifestParser.RESERVED_IDS`,
 * provider [PROVIDER] = `plugin:own`) so every helper that reads a live code, a player episode id
 * or a provider id (favourites, recents, sync, reopen after a cut) keeps working unchanged. It is
 * NOT an installed plugin: `PlayerViewModel` asks [access] instead of the registry for it.
 *
 * The host exception of the project (`.claude/reglas.md`): the person typed these addresses, so a
 * live stream may be on any PUBLIC host over http or https -- the same gate and DNS filter as a
 * plugin approved for `liveStreamHosts: "any"`. No `UserHost` is ever created for it, so the home
 * network stays out.
 */
object OwnLive {
    const val PLUGIN_ID = "own"
    const val NAME = "Mis canales"
    const val COLOR = 0xFF00B894L
    const val MAX_SOURCES = 50

    val PROVIDER: String = LiveChannelKeys.pluginProvider(PLUGIN_ID)

    val hosts = EffectiveHosts(declared = emptyList(), anyPublicLiveHost = true)

    /** Strict for everything that is not the channel's own stream (subtitles, licences, side files). */
    fun access() = PluginAccess.Ready(name = NAME, hosts = EffectiveHosts(emptyList()), xuper = false, liveHosts = hosts)
}
