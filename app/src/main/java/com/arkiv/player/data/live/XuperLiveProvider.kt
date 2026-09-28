package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.ui.live.LiveController

/**
 * The native Xuper channels as a provider: `MagisLiveCatalog` (portal categories, all pages,
 * its own caches) and [LiveController] (session resolution, `LiveHlsProxy`, seed rotation,
 * preheating, the step-1 gate), both used exactly as before. Only the types change here:
 * category ids become their decimal text and channels keep [LiveChannelKeys.XUPER]. This
 * provider exists only while the step-1 gate is open (see [liveProviderIds]).
 *
 * [close] stays the default no-op: the sessions and the proxy are shared, and dropping them
 * when the gate closes is step 1's `closeXuperLive`.
 */
class XuperLiveProvider(
    private val catalog: LiveCatalogGateway,
    private val controller: LiveController,
) : LiveChannelProvider {
    override val id: String = LiveChannelKeys.XUPER
    override val name: String = NAME
    override val color: Long = COLOR

    override fun initialCategory(): String = ALL_CATEGORY_ID

    override fun hasGuide(): Boolean = true

    override suspend fun categories(includeAdults: Boolean): List<ProviderCategory> =
        catalog.categories(includeAdults).map { ProviderCategory(it.id.toString(), it.name) }

    override suspend fun channels(categoryId: String, force: Boolean): List<LiveChannel> =
        categoryId.toIntOrNull()?.let { catalog.channels(it, force) } ?: emptyList()

    override suspend fun guide(channels: List<LiveChannel>): Pair<Map<String, List<LiveProgram>>, List<String>> =
        catalog.epg(channels.filter { it.provider == id }.map { it.code })

    override suspend fun open(channel: LiveChannel): LiveOpening = LiveOpening.Proxied(controller.open(channel.code))

    companion object {
        const val NAME = "Xuper"
        /** `ArkivMagisBlue` (ui/catalog/PlaySources.kt), as a Long for the data layer. */
        const val COLOR: Long = 0xFF64B5F6
        /** The portal's own "all channels" category (76182, measured 2026-08-14), as text. */
        const val ALL_CATEGORY_ID = "76182"
    }
}
