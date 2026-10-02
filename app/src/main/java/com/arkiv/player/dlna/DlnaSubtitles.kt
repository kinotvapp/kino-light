package com.arkiv.player.dlna

/** One subtitle a renderer can fetch: an SRT URL on the phone's subtitle server, its language and name. */
internal data class DlnaSubtitle(val url: String, val language: String, val name: String)

/**
 * The subtitles that go with a DLNA cast: [selected] (the phone's choice; null = off) and the
 * title's [others], for a TV that lists external subtitles in its own menu.
 */
internal data class DlnaSidecar(val selected: DlnaSubtitle?, val others: List<DlnaSubtitle>)

/**
 * How a DLNA renderer is told about external subtitles. There is no standard for it; these are the
 * fields the common TVs read, all pointing at the same SRT (UTF-8 with BOM) the phone serves:
 *
 * - Samsung: the `CaptionInfo.sec: <url>` HTTP header on the VIDEO response, and
 *   `<sec:CaptionInfoEx sec:type="srt">` / `<sec:CaptionInfo sec:type="srt">` in the DIDL-Lite item.
 *   Each takes ONE file: the selected one.
 * - LG and other PacketVideo-based renderers: `pv:subtitleFileUri` + `pv:subtitleFileType` on the
 *   video's `<res>`. One file too: the selected one.
 * - Generic: extra `<res protocolInfo="http-get:*:text/srt:*">` entries, the selected one first and
 *   then the rest (at most [MAX] in all), so a TV that offers a subtitle menu can list them. They go
 *   AFTER the video's `<res>`: renderers play the first `<res>` they find.
 *
 * With the subtitles off on the phone ([DlnaSidecar.selected] null) nothing is sent at all: a TV that
 * gets any of these tends to show it, and the person turned them off.
 *
 * A renderer cannot switch subtitles mid-play; the one chosen when the video is sent is the one the
 * TV gets (the phone's controls are hidden while DLNA plays). Pure: the DIDL is pinned by tests.
 */
internal object DlnaSubtitles {

    /** Subtitle `<res>` entries at most: enough for any real title, small enough for any renderer. */
    const val MAX = 8

    const val SEC_NS = "http://www.sec.co.kr/"
    const val PV_NS = "http://www.pv.com/pvns/"

    /** The xmlns declarations the fields below need, for the `<DIDL-Lite>` element; "" without subtitles. */
    fun namespaces(sidecar: DlnaSidecar?): String =
        if (sidecar?.selected == null) "" else " xmlns:sec=\"$SEC_NS\" xmlns:pv=\"$PV_NS\""

    /** Attributes for the VIDEO's `<res>` (LG/PacketVideo); "" without subtitles. */
    fun videoResAttributes(sidecar: DlnaSidecar?, escape: (String) -> String): String {
        val sel = sidecar?.selected ?: return ""
        return " pv:subtitleFileType=\"srt\" pv:subtitleFileUri=\"${escape(sel.url)}\""
    }

    /** The item's subtitle elements, to go after the video's `<res>`; "" without subtitles. */
    fun itemElements(sidecar: DlnaSidecar?, escape: (String) -> String): String {
        val sel = sidecar?.selected ?: return ""
        val url = escape(sel.url)
        val all = (listOf(sel) + sidecar.others.filter { it.url != sel.url }).take(MAX)
        return buildString {
            append("<sec:CaptionInfoEx sec:type=\"srt\">").append(url).append("</sec:CaptionInfoEx>")
            append("<sec:CaptionInfo sec:type=\"srt\">").append(url).append("</sec:CaptionInfo>")
            all.forEach { s ->
                append("<res protocolInfo=\"http-get:*:text/srt:*\">").append(escape(s.url)).append("</res>")
            }
        }
    }

    /**
     * The extra header for the video response (Samsung), CRLF included; "" without subtitles. A URL
     * is never allowed to carry a line break into the header block.
     */
    fun captionHeader(sidecar: DlnaSidecar?): String {
        val url = sidecar?.selected?.url ?: return ""
        if (url.any { it == '\r' || it == '\n' }) return ""
        return "CaptionInfo.sec: $url\r\n"
    }
}
