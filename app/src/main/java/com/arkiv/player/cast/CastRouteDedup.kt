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
 */
object CastRouteDedup {

    data class Route(val id: String, val name: String, val description: String?)

    /** Ids of [routes] to show: one per (name, description), the Cast provider's own first. */
    fun keep(routes: List<Route>): Set<String> =
        routes.groupBy { it.name.trim().lowercase() to it.description?.trim()?.lowercase().orEmpty() }
            .values
            .map { same -> same.minWith(compareBy<Route>({ rank(it.id) }, { it.id })).id }
            .toSet()

    /** The provider the Cast SDK's session manager selects, ahead of anything else with that name. */
    private fun rank(id: String): Int = if (id.contains("CastMediaRouteProviderService")) 0 else 1

    /** Drops from [routes], in place, every route [keep] leaves out. */
    fun filterInPlace(routes: MutableList<MediaRouter.RouteInfo>) {
        val kept = keep(routes.map { Route(it.id, it.name, it.description) })
        routes.retainAll { it.id in kept }
    }
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
