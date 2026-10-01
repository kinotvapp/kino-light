package com.arkiv.player.data.ditu

/**
 * The ONE switch that hides the built-in Caracol Streaming (Ditu) source from the person.
 *
 * Hidden since 2026-09-30 (release 0.9.44): Caracol is being rebuilt as a plugin, and until it
 * comes back that way nothing built-in may surface it -- no phone tab, no TV rail entry, no search
 * section/tab/spinner, and no Ditu search request at all. Nothing is deleted: every class, screen,
 * route and string stays, only gated on [HIDDEN]. Titles already saved from Caracol (library,
 * "Continuar viendo", history) still resolve and play: `AppGraph.contentSource` keeps Ditu for
 * resolving its own `ditu…` refs and only drops it from searching (see [ResolveOnlySource]).
 *
 * To bring Caracol back exactly as it was, set [HIDDEN] to false.
 */
object CaracolVisibility {
    const val HIDDEN = true

    /** Whether Caracol may show up anywhere the person can see it. */
    val visible: Boolean get() = !HIDDEN
}
