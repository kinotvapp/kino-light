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
 * a site that failed to answer (DNS, timeout, connection), then an HTTP error from a site (403, 429,
 * 5xx; a 404 is a scraper probing mirrors, never blamed), then the adapter's own reason.
 */
object PluginFailureText {
    fun of(e: PluginException, name: String): String? {
        val trace = e.trace
        if (e is PluginTimeoutException) {
            return trace?.waitingFor?.let { "$name no respondió a tiempo: esperaba a $it" }
        }
        fromTrace(trace, name)?.let { return it }
        if (e is PluginErrorException) return fromAdapter(e, name)
        return null
    }

    private fun fromTrace(trace: PluginCallTrace?, name: String): String? {
        val events = trace?.events ?: return null
        events.filterIsInstance<PluginCallTrace.Refused>().firstOrNull()?.let { r ->
            return when (r.why) {
                PluginCallTrace.Refusal.REJECTED_NOW, PluginCallTrace.Refusal.REJECTED_BEFORE -> "$name necesita ${r.host}, que rechazaste"
                PluginCallTrace.Refusal.NOT_ASKED -> "$name necesita ${r.host}, que no está aprobado"
                PluginCallTrace.Refusal.LIMIT -> "$name necesita ${r.host}, pero ya tiene el máximo de servidores aprobados"
            }
        }
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
            detail == NuvioPluginConverter.NO_STREAMS -> "$name no encontró este título"
            detail == NuvioPluginConverter.ONLY_TORRENTS -> "$name solo tiene torrents de este título"
            detail.startsWith(NuvioPluginConverter.SCRAPER_ERROR_PREFIX) ->
                "$name falló: ${detail.removePrefix(NuvioPluginConverter.SCRAPER_ERROR_PREFIX).take(MAX_DETAIL_CHARS)}"
            else -> null
        }
    }

    /** The longest scraper error text shown. */
    const val MAX_DETAIL_CHARS = 120
}
