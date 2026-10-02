package com.arkiv.player.dlna

import com.arkiv.player.cast.CastRouteTrail
import com.arkiv.player.cast.CastStrategy

/**
 * The stages one DLNA VOD cast may go through, in order, and which it already tried: the TV's own
 * URL as it is → the same through the phone's proxy → the growing remux as HLS → the whole MP4
 * remux, keeping only what the renderer's sink list allows ([CastStrategy.chain], with
 * [DlnaRenderer.receiverOf] as the receiver). Each stage is tried at most once; what moves a cast
 * from one to the next is [DirectPlayFallback] (a SOAP refusal, an early stop, a load that never
 * plays). [trail] is what each stage ended with, for the report.
 */
internal class DlnaRouteChain(val stages: List<CastStrategy.Route>, val trail: CastRouteTrail = CastRouteTrail()) {

    private val tried = LinkedHashSet<CastStrategy.Route>()

    /** [route] is being tried now; it is never offered again by [next]. */
    @Synchronized
    fun start(route: CastStrategy.Route) {
        tried += route
    }

    /** The stage after [route] that was not tried yet, or null: nothing left. */
    @Synchronized
    fun next(route: CastStrategy.Route): CastStrategy.Route? =
        stages.dropWhile { it != route }.drop(1).firstOrNull { it !in tried }

    /** The plan, for the log: `proxy > remux > remux_file`. */
    fun plan(): String = stages.joinToString(" > ") { label(it) }

    companion object {
        /**
         * The stages for a stream of [format] to a renderer that is [receiver]: the TV's own URL first
         * only when there is one it may fetch itself ([direct]); a format nothing decides goes through
         * the proxy as it is, as every DLNA cast always has.
         */
        fun stagesFor(format: CastStrategy.Format, receiver: CastStrategy.Receiver, direct: Boolean): List<CastStrategy.Route> =
            CastStrategy.chain(format, needsHeaders = !direct, directAllowed = direct, remuxAvailable = true, receiver = receiver)
                .ifEmpty { listOf(CastStrategy.Route.PROXY) }

        /** A stage's name in logs and reports. */
        fun label(route: CastStrategy.Route?): String = route?.name?.lowercase() ?: "?"
    }
}
