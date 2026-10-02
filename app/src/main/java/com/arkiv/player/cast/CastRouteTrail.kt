package com.arkiv.player.cast

/**
 * The routes one cast of one title tried, in order, and how each one ended, for the failure report
 * (`routes`): `direct=idle_error > proxy=load_never_played` on a Chromecast,
 * `proxy=refused:play:upnp501:http500 > remux=stopped_early > remux_file=position_stalled` on DLNA.
 *
 * Results are short codes, never a URL, a header or a token; [CastDiag.scrub] still runs over each
 * one. Bounded at [MAX] entries: every stage is tried at most once, so a longer trail is a bug.
 */
class CastRouteTrail {
    private val entries = ArrayList<String>()

    /** [route] (a stage name: `direct`, `proxy`, `remux`, `continuous_ts`...) ended with [result]. */
    @Synchronized
    fun tried(route: String, result: String) {
        if (entries.size >= MAX) return
        entries += "${route.ifBlank { "?" }}=${CastDiag.scrub(result).replace(Regex("\\s+"), "_")}"
    }

    /** One line, oldest first; empty when nothing was tried. */
    @Synchronized
    fun summary(): String = entries.joinToString(" > ")

    val size: Int @Synchronized get() = entries.size

    companion object {
        const val MAX = 12

        /**
         * What every cast failure report (Chromecast and DLNA) says about its routes: [trail]'s
         * summary (`routes`), the stage that failed last (`stage`) and the host the receiver was sent
         * to (`host`: a host name, or "lan" for one of the phone's own servers; never a path or a
         * token, see [CastFallback.hostOf]).
         */
        fun reportExtras(trail: CastRouteTrail, stage: String, uri: String?): Map<String, String> = mapOf(
            "routes" to trail.summary(),
            "stage" to stage,
            "host" to CastFallback.hostOf(uri),
        )
    }
}
