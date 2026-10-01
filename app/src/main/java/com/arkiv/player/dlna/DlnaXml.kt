package com.arkiv.player.dlna

/** What a UPnP SOAP fault says: the error code and its text, e.g. 714 "Illegal MIME-type". */
internal data class UpnpFault(val code: Int?, val description: String?)

/** The renderer's answer to `GetTransportInfo`: what it is doing and whether it hit a problem. */
internal data class TransportInfo(val state: String?, val status: String?, val speed: String?)

/** The renderer's answer to `GetPositionInfo`: where playback is, in ms (null when it doesn't say). */
internal data class PositionInfo(val relTimeMs: Long?, val durationMs: Long?, val trackUri: String?)

/**
 * Reads what a DLNA/UPnP renderer sends back. Pure string handling with no Android in it (no
 * `XmlPullParser`, which is only a stub on the JVM), so every quirk seen in the field can be pinned
 * by a test built from a real response.
 *
 * Deliberately tolerant: renderers disagree on namespace prefixes, whitespace, time formats and which
 * arguments they bother to fill, and a parser that throws would hide the very failure being diagnosed.
 * Anything it can't read comes back null, never as an exception.
 */
internal object DlnaXml {
    /**
     * A token path segment, for [safeUrl] to hide: `/t/<token>` (ArchiveCacheProxy, DlnaProxyServer,
     * PluginCastProxy), `/r/<token>` (RemuxHlsServer -- whose tokens were still reaching the log) and
     * `/s/<token>` (the cast's subtitle server).
     */
    private val PATH_TOKEN = Regex("/([trs])/[^/?#]+")

    /** The text of the first `<name>…</name>` (any namespace prefix), unescaped, or null if absent/empty. */
    fun tag(xml: String?, name: String): String? {
        if (xml.isNullOrEmpty()) return null
        val regex = Regex("<(?:[A-Za-z0-9_.-]+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:[A-Za-z0-9_.-]+:)?$name>")
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.let(::unescape)?.takeIf { it.isNotEmpty() }
    }

    fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /** The UPnP error inside a SOAP fault, or null when the body isn't one. */
    fun fault(xml: String?): UpnpFault? {
        if (xml.isNullOrEmpty()) return null
        if (!xml.contains("UPnPError", ignoreCase = true) && !xml.contains("Fault", ignoreCase = true)) return null
        return UpnpFault(tag(xml, "errorCode")?.toIntOrNull(), tag(xml, "errorDescription"))
    }

    fun transportInfo(xml: String?): TransportInfo? {
        val info = TransportInfo(
            state = tag(xml, "CurrentTransportState"),
            status = tag(xml, "CurrentTransportStatus"),
            speed = tag(xml, "CurrentSpeed"),
        )
        return info.takeIf { it.state != null || it.status != null }
    }

    fun positionInfo(xml: String?): PositionInfo? {
        val info = PositionInfo(
            relTimeMs = hmsToMs(tag(xml, "RelTime")),
            durationMs = hmsToMs(tag(xml, "TrackDuration")),
            trackUri = tag(xml, "TrackURI"),
        )
        return info.takeIf { it.relTimeMs != null || it.durationMs != null || it.trackUri != null }
    }

    /**
     * `H:MM:SS`, `HH:MM:SS.mmm` or `+H:MM:SS` to ms. `NOT_IMPLEMENTED` (a live stream, or a renderer that
     * doesn't track it) and anything malformed are null, NOT zero: zero would read as "not advancing".
     */
    fun hmsToMs(text: String?): Long? {
        val s = text?.trim()?.removePrefix("+") ?: return null
        val match = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d+))?$").find(s) ?: return null
        val (h, m, sec, frac) = match.destructured
        val millis = if (frac.isEmpty()) 0L else (("0.$frac").toDouble() * 1000).toLong()
        return (h.toLong() * 3600 + m.toLong() * 60 + sec.toLong()) * 1000 + millis
    }

    /**
     * The MIME types a renderer says it can play, from the `Sink` of `GetProtocolInfo`
     * (`http-get:*:video/mp4:DLNA.ORG_PN=…,http-get:*:video/mpeg:*`). Lower-cased and de-duplicated; wildcard
     * (`*`) entries are dropped. Empty when the renderer doesn't say, which is different from "supports nothing".
     */
    fun sinkMimes(xml: String?): List<String> {
        val sink = tag(xml, "Sink") ?: return emptyList()
        return sink.split(',')
            .mapNotNull { entry -> entry.trim().split(':').getOrNull(2)?.trim()?.lowercase() }
            .filter { it.isNotEmpty() && it != "*" }
            .distinct()
    }

    /** Whether [declared] is in [supported]. Unknown support (empty list) counts as "can't tell", i.e. true. */
    fun isSupported(declared: String, supported: List<String>): Boolean =
        supported.isEmpty() || supported.any { it.equals(declared, ignoreCase = true) }

    /**
     * A URL that is safe to write into a log and to send to GlitchTip: scheme, host, port and path, with the
     * query string and any credentials dropped (the tokens the proxies carry live in the query) and a long
     * path cut short.
     */
    fun safeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "(none)"
        val noQuery = url.substringBefore('?').substringBefore('#')
        val scheme = noQuery.substringBefore("://", "")
        val rest = noQuery.substringAfter("://", noQuery)
        val hostPort = rest.substringBefore('/').substringAfter('@')
        val path = rest.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
            // The cast proxies' session tokens ride in the PATH (`/t/<token>/…`, see ProxyTokens): never logged.
            .replace(PATH_TOKEN) { "/${it.groupValues[1]}/…" }
        val shortPath = if (path.length > 60) "…" + path.takeLast(57) else path
        return if (scheme.isEmpty()) "$hostPort$shortPath" else "$scheme://$hostPort$shortPath"
    }

    /**
     * [ref] (a `controlURL` from a device description) resolved against [base], or null if [base] is not a URL.
     *
     * `java.net.URI.resolve` on a base with no path (`http://host:16206`, a common `URLBase`) glues a relative
     * reference straight onto the port (`http://host:16206AVTransport`), which OkHttp then rejects as an
     * invalid port. An empty path is treated as `/`, as RFC 3986 says.
     */
    fun resolveUrl(base: String, ref: String): String? = runCatching {
        val uri = java.net.URI(base)
        val rooted = if (uri.path.isNullOrEmpty() && uri.host != null) java.net.URI("$base/") else uri
        rooted.resolve(ref).toString()
    }.getOrNull()

    /** A UPnP AVTransport / ConnectionManager error code in words (UPnP AV spec + the common vendor ones). */
    fun describeError(code: Int?): String = when (code) {
        null -> "sin código"
        401 -> "acción inválida"
        402 -> "argumentos inválidos"
        501 -> "la acción falló"
        701 -> "transición no disponible (aún no está listo)"
        702 -> "no hay contenido"
        703 -> "error de lectura"
        704 -> "formato no soportado"
        705 -> "transporte bloqueado"
        706 -> "error de escritura"
        707 -> "contenido protegido"
        708 -> "no soportado para reproducción"
        709 -> "modo de grabación no soportado"
        710 -> "modo de búsqueda no soportado"
        711 -> "índice de búsqueda inválido"
        712 -> "modo de reproducción no soportado"
        714 -> "tipo MIME no soportado"
        715 -> "contenido ocupado"
        716 -> "recurso no encontrado (la TV no pudo abrir la URL)"
        718 -> "InstanceID inválido"
        719 -> "error de DRM"
        else -> "error UPnP $code"
    }
}
