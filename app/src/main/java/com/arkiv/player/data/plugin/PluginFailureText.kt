package com.arkiv.player.data.plugin

/**
 * The sentence a failed plugin call shows, when Kino knows something more specific than
 * "<fuente> no está disponible ahora": what the call's own `kino.fetch`es met ([PluginCallTrace],
 * which the plugin can't make up) first, then what a converted Nuvio scraper's adapter reported
 * ([NuvioPluginConverter.NO_STREAMS] and friends). Null: nothing better known, the caller keeps its
 * usual wording. Short, plain Spanish; hosts named, since that is what the person can act on
 * (approve, forget a rejection, or just know the site is down).
 *
 * Order, most actionable first: a host refused (by the person, not asked, or over the cap), then
 * the adapter's own answer when the scraper finished and simply found nothing ("no encontró este
 * título", "solo tiene torrents"), then a site that failed to answer (DNS, timeout, connection),
 * then an HTTP error from a site (403, 429, 5xx; a 404 is a scraper probing mirrors, never blamed),
 * then the adapter's script error. A scraper walks many mirrors, and one of them being dead is
 * routine: when it still came back with a clean "nothing here", that is the real answer, and
 * naming the dead mirror would send the person after a host that didn't matter.
 */
object PluginFailureText {
    fun of(e: PluginException, name: String): String? {
        val trace = e.trace
        if (e is PluginTimeoutException) {
            return trace?.waitingFor?.let { "$name no respondió a tiempo: esperaba a $it" }
        }
        val events = trace?.events.orEmpty()
        fromRefusal(events, name)?.let { return it }
        if (e is PluginErrorException) adapterFoundNothing(e, name)?.let { return it }
        fromNetwork(events, name)?.let { return it }
        if (e is PluginErrorException) return fromAdapter(e, name)
        return null
    }

    private fun fromRefusal(events: List<PluginCallTrace.Event>, name: String): String? {
        val r = events.filterIsInstance<PluginCallTrace.Refused>().firstOrNull() ?: return null
        return when (r.why) {
            PluginCallTrace.Refusal.REJECTED_NOW, PluginCallTrace.Refusal.REJECTED_BEFORE -> "$name necesita ${r.host}, que rechazaste"
            PluginCallTrace.Refusal.NOT_ASKED -> "$name necesita ${r.host}, que no está aprobado"
        }
    }

    /** The adapter's clean "nothing here" answers: the scraper ran to the end, whatever mirrors it met. */
    private fun adapterFoundNothing(e: PluginErrorException, name: String): String? = when (e.message.orEmpty()) {
        NuvioPluginConverter.NO_STREAMS -> "$name no encontró este título"
        NuvioPluginConverter.ONLY_TORRENTS -> "$name solo tiene torrents de este título"
        else -> null
    }

    private fun fromNetwork(events: List<PluginCallTrace.Event>, name: String): String? {
        events.filterIsInstance<PluginCallTrace.Failed>().firstOrNull()?.let { f ->
            return when (f.how) {
                PluginCallTrace.Failure.DNS -> "$name: no se encontró el sitio ${f.host}"
                PluginCallTrace.Failure.TIMEOUT -> "$name: ${f.host} no respondió a tiempo"
                PluginCallTrace.Failure.NETWORK -> "$name: no se pudo conectar con ${f.host}"
            }
        }
        events.filterIsInstance<PluginCallTrace.Answered>().firstOrNull { it.status == 403 || it.status == 429 || it.status >= 500 }?.let { a ->
            return "$name: ${a.host} respondió con error ${a.status}"
        }
        return null
    }

    private fun fromAdapter(e: PluginErrorException, name: String): String? {
        val detail = e.message.orEmpty()
        return when {
            // Only a converted scraper's getStreams (resolve) reports this; cleaned like any script error.
            detail.startsWith(NuvioPluginConverter.SCRAPER_ERROR_PREFIX) ->
                PluginErrorText.of(name, "resolve", detail.removePrefix(NuvioPluginConverter.SCRAPER_ERROR_PREFIX))
            else -> null
        }
    }
}
