package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayEpisode
import com.arkiv.player.data.gateway.GatewayException
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.gateway.GatewaySeries
import com.arkiv.player.data.gateway.SearchEvent
import com.arkiv.player.data.magis.MagisRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Keeps every Xuper ref saved before Xuper became a plugin working, by handing it to the installed
 * Xuper plugin. Takes `MagisSource`'s old place in `AppGraph.contentSource`.
 *
 * Library rows, downloads, followed series, recommendations and companion payloads saved by the
 * native Magis source store a [MagisRef] (`magis1:<type>:<episode>:<contentId>`, or the gateway's
 * older `base64url(json).hmac`). This source claims exactly those ([recognizes] is
 * `MagisSource.recognizes`, byte for byte) and holds no Magis logic of its own: it wraps the ref
 * into a [PluginRef] of the installed Xuper plugin, whose `kino.xuper.resolve`/`episodes` already
 * take a [MagisRef] as the plugin's own ref, and delegates to [pluginRefs] -- the SAME
 * `PluginContentSource` instances (plus [UnusablePluginSource]) that serve every `plg1:` ref in that
 * same `CompositeSource` pass. There is no second resolve or episodes implementation here.
 *
 * "The installed Xuper plugin" is decided by [XuperPrivilege.grants] on each installed record, never
 * by a manifest id (any repo can claim `xuper`). When it is installed but not usable (disabled,
 * unresponsive, damaged), the wrapped ref falls through to [UnusablePluginSource] exactly like a
 * `plg1:` ref of that plugin would, so the person reads the same "Activa el plugin Xuper…". When no
 * installed record is granted at all, there is no plugin id to wrap into, so the error is built from
 * the same [PluginAccess.Uninstalled] message [UnusablePluginSource] gives an uninstalled plugin.
 *
 * Episode refs come back unwrapped to their inner [MagisRef] form: the callers of a legacy ref
 * (`NewChapterFinder.checkMagis`, the Magis season dialogs, `SearchPlayback.playMagisSeason`) store
 * them next to the `magis1:` refs they already have, under `magis:` episode ids, exactly as when
 * `MagisSource` answered. Played or downloaded later, they come back through here.
 */
class LegacyXuperRefSource(
    /** Every installed plugin, whatever its state: the one [XuperPrivilege.grants] is the target. */
    private val installed: List<InstalledPlugin>,
    /** The usable plugins' sources followed by [UnusablePluginSource], as `AppGraph` lists them. */
    private val pluginRefs: ContentSource,
) : ContentSource {

    override fun recognizes(ref: String): Boolean = MagisRef.decode(ref) != null

    /** Xuper searches through its plugin: a legacy source has nothing to search. */
    override fun search(ctx: GatewaySearchQuery): Flow<SearchEvent> = emptyFlow()

    override suspend fun resolve(ref: String): GatewayPlayable {
        val legacy = decode(ref)
        // A series ref points at the series plus a chapter number (0 = the first chapter), which the
        // bridge's resolve plays directly. An EPISODE-kind wrap is what lets `PluginContentSource`
        // pass it on: it refuses to resolve a SERIES-kind ref.
        val kind = if (legacy.isSeries) PluginRef.EPISODE else PluginRef.MOVIE
        return pluginRefs.resolve(wrap(ref, legacy, kind))
    }

    override suspend fun episodesWithSeries(ref: String): Pair<List<GatewayEpisode>, GatewaySeries?> {
        val legacy = decode(ref)
        val id = xuperPluginId() ?: throw notInstalled()
        val (episodes, series) = pluginRefs.episodesWithSeries(wrap(ref, legacy, PluginRef.SERIES, id))
        // Back to the legacy shape `MagisSource` answered with: its own refs, and no per-chapter
        // season (Magis's season travels only in the series block).
        return episodes.map { it.copy(ref = unwrap(it.ref, id), season = null) } to series
    }

    private fun decode(ref: String): MagisRef =
        MagisRef.decode(ref) ?: throw GatewayException("ese ref no es de Xuper")

    private fun wrap(ref: String, legacy: MagisRef, kind: String, id: String = xuperPluginId() ?: throw notInstalled()): String =
        PluginRef(
            pluginId = id,
            itemId = legacy.contentId,
            kind = kind,
            // The original string, untouched: the bridge's `MagisRef.decode` reads both of its forms.
            ref = ref,
            number = if (kind == PluginRef.EPISODE) legacy.episode else 0,
        ).encode()

    /** An episode's plugin ref, as the [MagisRef] it wraps; anything else is returned as it came. */
    private fun unwrap(ref: String, id: String): String =
        PluginRef.decode(ref)?.takeIf { it.pluginId == id }?.ref?.takeIf { MagisRef.decode(it) != null } ?: ref

    private fun xuperPluginId(): String? = installed.firstOrNull { XuperPrivilege.grants(it.record) }?.manifest?.id

    private fun notInstalled() = GatewayException(PluginAccess.Uninstalled(XUPER_NAME).blockedMessage()!!)

    companion object {
        /** Only for the "no longer installed" sentence when no record is left to read the name from. */
        const val XUPER_NAME = "Xuper"
    }
}
