package com.arkiv.player.cast

import android.content.Context
import android.os.Bundle
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import androidx.mediarouter.app.MediaRouteDynamicChooserDialog
import androidx.mediarouter.media.MediaRouter

/**
 * One entry per TV in the "Transmitir a" list.
 *
 * Seen 2026-10-01 on a Redmi with the KALLEY on the Wi-Fi: the chooser listed "liliycami" twice.
 * Both entries come from Google Play services, which runs two route providers for the same Cast
 * device -- `CastMediaRouteProviderService_Persistent` and `CastMediaRoute2ProviderService_Persistent`
 * (the latter shows up in the log as `AxMediaRouter: Ignoring null provider descriptor from …
 * CastMediaRoute2ProviderService_Persistent`) -- and the androidx chooser lists every route that
 * matches the Cast selector. The session that started came from the first provider, so routes
 * sharing a name and description collapse onto that one.
 *
 * The same TV can also show up under ANOTHER name: the KALLEY was listed once more as "R3", its
 * model name, next to "liliycami" (2026-10-01). So routes are also the same TV when they share the
 * Cast device's id or its address ([Route.deviceKeys]), whatever they are called.
 */
object CastRouteDedup {

    /**
     * [deviceKeys]: what identifies the TV behind the route, when the route says (the Cast device
     * id, its IP address). Two routes sharing any of them are one TV.
     */
    data class Route(val id: String, val name: String, val description: String?, val deviceKeys: Set<String> = emptySet())

    /** Ids of [routes] to show: one per TV, the Cast provider's own first. */
    fun keep(routes: List<Route>): Set<String> {
        // Union-find over "same name and description" and "same device key".
        val parent = IntArray(routes.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        val firstByKey = HashMap<Any, Int>()
        routes.forEachIndexed { i, r ->
            val keys = r.deviceKeys.map { "device:" + it.trim().lowercase() }.filter { it.length > "device:".length } +
                listOfNotNull(castIdSuffix(r.id)?.let { "device:$it" }) +
                listOf("name:" + r.name.trim().lowercase() + "|" + r.description?.trim()?.lowercase().orEmpty())
            keys.forEach { k -> firstByKey[k]?.let { union(i, it) } ?: firstByKey.put(k, i) }
        }
        return routes.indices.groupBy { find(it) }.values
            .map { group -> group.map { routes[it] }.minWith(compareBy<Route>({ rank(it.id) }, { it.id })).id }
            .toSet()
    }

    /**
     * The device part of a Play services Cast route id (`…CastMediaRouteProviderService…:<device>`),
     * the same for every route Play services lists for one TV; null for any other id.
     */
    private fun castIdSuffix(id: String): String? {
        if (!id.contains("CastMediaRoute")) return null
        return id.substringAfterLast(':', "").trim().lowercase().takeIf { it.length >= 4 }
    }

    /** The provider the Cast SDK's session manager selects, ahead of anything else with that name. */
    private fun rank(id: String): Int = if (id.contains("CastMediaRouteProviderService")) 0 else 1

    /** Drops from [routes], in place, every route [keep] leaves out. */
    fun filterInPlace(routes: MutableList<MediaRouter.RouteInfo>) {
        val kept = keep(routes.map { Route(it.id, it.name, it.description, deviceKeysOf(it)) })
        routes.retainAll { it.id in kept }
    }

    /** The Cast device id and address a route's extras carry, if any. */
    private fun deviceKeysOf(route: MediaRouter.RouteInfo): Set<String> = runCatching {
        val device = com.google.android.gms.cast.CastDevice.getFromBundle(route.extras) ?: return@runCatching emptySet()
        setOfNotNull(device.deviceId, device.inetAddress?.hostAddress)
    }.getOrDefault(emptySet())
}

/** The chooser the Cast button opens, with [CastRouteDedup] applied. Set on the button by `DlnaCastButtons`. */
class DedupedCastDialogFactory : MediaRouteDialogFactory() {
    override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment = DedupedChooserFragment()
}

class DedupedChooserFragment : MediaRouteChooserDialogFragment() {
    override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?): MediaRouteChooserDialog =
        object : MediaRouteChooserDialog(context) {
            override fun onFilterRoutes(routes: MutableList<MediaRouter.RouteInfo>) {
                super.onFilterRoutes(routes)
                CastRouteDedup.filterInPlace(routes)
            }
        }

    override fun onCreateDynamicChooserDialog(context: Context): MediaRouteDynamicChooserDialog =
        object : MediaRouteDynamicChooserDialog(context) {
            override fun onFilterRoutes(routes: MutableList<MediaRouter.RouteInfo>) {
                super.onFilterRoutes(routes)
                CastRouteDedup.filterInPlace(routes)
            }
        }
}
