package com.arkiv.player.ui.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.live.LiveModule
import com.arkiv.player.data.live.LiveOpening
import com.arkiv.player.data.plugin.PluginIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** How opening an En vivo module channel ended, for `PlayerViewModel.openPluginChannel`. */
internal sealed interface ModuleChannelOpen {
    data class Opened(val opening: LiveOpening) : ModuleChannelOpen
    /** The provider failed; [providerName] names it in the message the person reads. */
    data class Failed(val error: Throwable, val providerName: String) : ModuleChannelOpen
    /** The channel's provider is not (or no longer) in the module: the blocked dialog with [message]. */
    data class Gone(val message: String) : ModuleChannelOpen
}

private const val UNAVAILABLE = "Este canal no está disponible ahora"

/**
 * Opens [channel] on its provider in [module]. A provider instance the module closed mid-open
 * (its plugin changed, so `LiveCatalog` replaced it) ends its calls with a
 * [CancellationException] even though the caller is still active: that is never a silent end.
 * The open is asked once more of the instance now listed under the same id; if there is none the
 * channel is [ModuleChannelOpen.Gone], and if it is the same one, or the retry fails, it is an
 * ordinary [ModuleChannelOpen.Failed]. Only the caller's OWN cancellation (a newer zap) propagates.
 */
internal suspend fun openModuleChannel(module: LiveModule?, channel: LiveChannel): ModuleChannelOpen {
    val first = module?.provider(channel.provider) ?: return ModuleChannelOpen.Gone(module?.blockedMessage(channel.provider) ?: UNAVAILABLE)
    return try {
        ModuleChannelOpen.Opened(first.open(channel))
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        val next = module.provider(channel.provider) ?: return ModuleChannelOpen.Gone(module.blockedMessage(channel.provider))
        if (next === first) return ModuleChannelOpen.Failed(e, first.name)
        try {
            ModuleChannelOpen.Opened(next.open(channel))
        } catch (again: CancellationException) {
            currentCoroutineContext().ensureActive()
            ModuleChannelOpen.Failed(again, next.name)
        } catch (again: Exception) {
            ModuleChannelOpen.Failed(again, next.name)
        }
    } catch (e: Exception) {
        ModuleChannelOpen.Failed(e, first.name)
    }
}

/** The recents row for [channel] once [opened]: the listed name (a channel rebuilt from its live code is named by its code) and the adult mark. */
internal fun recentOf(channel: LiveChannel, opened: LiveOpening.Plugin): LiveChannel =
    channel.copy(name = opened.channel.title.ifBlank { channel.name }, adult = channel.adult || opened.adult)

/**
 * Whether a scheduled plugin-live reopen of [episodeId] may still publish: the item on screen
 * ([onScreen]) is still that one and, for an En vivo channel ([moduleChannel], the channel zapping
 * is on; null for a plugin's `live` card), it is still the channel wanted. While the next channel
 * opens the previous stream stays on screen, so the item alone can't tell.
 */
internal fun pluginReopenStillWanted(episodeId: String, onScreen: String?, moduleChannel: LiveChannel?): Boolean {
    if (onScreen != episodeId) return false
    if (moduleChannel == null) return true
    val pluginId = LiveChannelKeys.pluginIdOf(moduleChannel.provider) ?: return false
    return PluginIds.liveEpisodeId(pluginId, moduleChannel.code) == episodeId
}
