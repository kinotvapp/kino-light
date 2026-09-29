package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.ManifestParser
import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.XuperLiveGate
import com.arkiv.player.data.plugin.XuperPrivilege
import com.arkiv.player.data.plugin.xuperLiveAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A plugin feeds the En vivo module while it can actually answer: usable (enabled, intact,
 * responsive), configured, on apiVersion 3+, declaring `channels`, and not the Xuper install,
 * whose channels stay native behind the step-1 gate. A plugin that needs setup is left out
 * rather than shown as an empty section. It comes back the moment it is configured.
 */
fun addsChannels(plugin: InstalledPlugin): Boolean =
    plugin.isUsable && !plugin.needsSetup &&
        plugin.manifest.apiVersion >= PluginLiveContract.API_VERSION &&
        ManifestParser.CHANNELS in plugin.manifest.capabilities &&
        !XuperPrivilege.grants(plugin.record)

/** The module's providers, in order: Xuper first while its gate is open, then plugins in registry order, then the person's own channels ([OwnLive]), which are always there. Pure. */
fun liveProviderIds(plugins: List<InstalledPlugin>): List<String> = buildList {
    if (xuperLiveAllowed(plugins)) add(LiveChannelKeys.XUPER)
    plugins.filter(::addsChannels).forEach { add(LiveChannelKeys.pluginProvider(it.id)) }
    // The person's own channels, always: it is what keeps En vivo (and the "+" on it) reachable with nothing installed.
    add(OwnLive.PROVIDER)
}

/**
 * What the player shows for a channel whose provider is not in [liveProviderIds]. Pure. [place] is where
 * the Plugins screen lives on this device ([PluginsPlace]).
 */
fun liveBlockedMessage(
    providerId: String,
    plugins: List<InstalledPlugin>,
    place: String = com.arkiv.player.data.plugin.PluginsPlace.current,
): String {
    if (providerId == LiveChannelKeys.XUPER) return XuperLiveGate.blockedMessage(plugins) ?: UNAVAILABLE
    // Built in, never "a plugin that is no longer installed".
    if (providerId == OwnLive.PROVIDER) return UNAVAILABLE
    val p = LiveChannelKeys.pluginIdOf(providerId)?.let { id -> plugins.firstOrNull { it.id == id } }
        ?: return "Este canal venía de un plugin que ya no está instalado"
    val name = p.manifest.name
    return when {
        p.record.damaged -> "El plugin $name tiene archivos dañados, reinstálalo"
        !p.record.enabled -> "Activa el plugin $name para ver este canal"
        p.record.unresponsive -> "El plugin $name no responde ahora; revísalo en $place"
        p.needsSetup -> "Configura $name en $place"
        !addsChannels(p) -> "El plugin $name ya no ofrece canales en vivo"
        else -> UNAVAILABLE
    }
}

private const val UNAVAILABLE = "Este canal no está disponible ahora"

/**
 * The En vivo module (spec §1): its providers derived from the installed plugins, live. Every
 * surface reads [providers]/[tabs]/[available], so installing, switching or configuring a
 * plugin shows or hides its channels without a restart. An empty module hides the phone tab,
 * the TV nav button and the Home row (§4).
 *
 * A provider instance lives as long as its plugin's `changeKey()` (version, setup state,
 * typed servers, config revision). Its caches survive screens, but never a settings change:
 * the instance replaced (or dropped, when its plugin stops adding channels) is
 * [LiveChannelProvider.close]d, which cancels whatever it was still doing. A channel whose
 * provider vanished mid-playback stops with [blockedMessage] (the player watches [providers]).
 * [xuperProvider] is only called while the Xuper gate is open.
 */
class LiveCatalog(
    private val plugins: StateFlow<List<InstalledPlugin>>,
    scope: CoroutineScope,
    private val xuperProvider: () -> LiveChannelProvider,
    private val pluginProvider: (InstalledPlugin) -> LiveChannelProvider,
    private val ownProvider: () -> LiveChannelProvider,
) : LiveModule {
    /** The live instances by provider id, with the key they were built for. */
    private val built = HashMap<String, Pair<String, LiveChannelProvider>>()

    private fun build(list: List<InstalledPlugin>): List<LiveChannelProvider> = synchronized(built) {
        val ids = liveProviderIds(list)
        val gone = built.filterKeys { it !in ids }
        gone.keys.forEach(built::remove)
        gone.values.forEach { it.second.close() }
        ids.map { id ->
            val plugin = if (id == OwnLive.PROVIDER) null else LiveChannelKeys.pluginIdOf(id)?.let { pid -> list.first { it.id == pid } }
            val key = plugin?.changeKey() ?: id
            val current = built[id]
            if (current != null && current.first == key) return@map current.second
            current?.second?.close()
            (when {
                id == OwnLive.PROVIDER -> ownProvider()
                plugin == null -> xuperProvider()
                else -> pluginProvider(plugin)
            }).also { built[id] = key to it }
        }
    }

    /**
     * Uninstall, BEFORE the store deletes the plugin's data: closes its provider now instead of when
     * the registry reload drops it, so a playlist download still running cancels at its next step
     * rather than re-creating `plugin-data/<id>/live` after the delete. Closed once: the next [build]
     * no longer finds it.
     */
    fun forget(pluginId: String) {
        val gone = synchronized(built) { built.remove(LiveChannelKeys.pluginProvider(pluginId)) }
        gone?.second?.close()
    }

    override val providers: StateFlow<List<LiveChannelProvider>> =
        plugins.map(::build).stateIn(scope, SharingStarted.Eagerly, build(plugins.value))

    val tabs: StateFlow<List<LiveProviderTab>> = providers
        .map(::tabsOf)
        .stateIn(scope, SharingStarted.Eagerly, tabsOf(providers.value))

    /** Whether the module has anything at all: the tab, the TV nav button and the Home row follow it. */
    val available: StateFlow<Boolean> = providers
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, providers.value.isNotEmpty())

    /**
     * A plugin's "Lista recortada: …" line for the Plugins screen (see `PluginLiveProvider.notice`),
     * null while it has none or adds no channels. Follows the plugin's CURRENT provider: a
     * replaced instance (settings change) or a dropped one never leaves a stale line behind.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun noticeFor(pluginId: String): Flow<String?> {
        val id = LiveChannelKeys.pluginProvider(pluginId)
        return providers
            .map { list -> list.firstOrNull { it.id == id } }
            .distinctUntilChanged { a, b -> a === b }
            .flatMapLatest { it?.notice ?: flowOf(null) }
    }

    override fun blockedMessage(providerId: String): String = liveBlockedMessage(providerId, plugins.value)

    private fun tabsOf(list: List<LiveChannelProvider>) = list.map { LiveProviderTab(it.id, it.name, it.color) }
}
