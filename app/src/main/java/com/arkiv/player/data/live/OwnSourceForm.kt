package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnLiveSourceEntity

enum class OwnKind { CHANNEL, PLAYLIST }
enum class OwnField { NAME, URL, LOGO, EPG, USER_AGENT, REFERER }

/** What the form holds while the person types: plain strings, blank = not set. */
data class OwnSourceForm(
    val kind: OwnKind,
    val name: String,
    val url: String,
    val groupName: String = "",
    val logo: String = "",
    val epgUrl: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val refreshHours: Int = 0,
    /**
     * A list's text the person pasted or opened from a file, replacing any address ([OwnPastedList]).
     * Null = no new text: an address list, or an edit of a pasted list (its [url] is `kino-list:<id>`)
     * that keeps the text it has.
     */
    val pastedText: String? = null,
)

sealed interface OwnFormResult {
    /**
     * [source] is ready to store (canonical URLs, blanks as null); [cleartext] = its address is `http://`;
     * [content] = the checked new text of a pasted list (its digest is already in [source]).
     */
    data class Valid(val source: OwnLiveSourceEntity, val cleartext: Boolean, val content: OwnPastedCheck.Ok? = null) : OwnFormResult
    data class Invalid(val errors: Map<OwnField, String>) : OwnFormResult
}

private const val MAX_REFRESH_HOURS = 168

/** Validates the whole form at once. [otherUrls]: the addresses of the OTHER live sources (for the duplicate rule). */
fun OwnSourceForm.validate(id: String, otherUrls: Collection<String>): OwnFormResult {
    val errors = LinkedHashMap<OwnField, String>()
    OwnSourceValidator.checkName(name)?.let { errors[OwnField.NAME] = it }
    var cleartext = false
    var canonical = url.trim()
    var content: OwnPastedCheck.Ok? = null
    val pasted = kind == OwnKind.PLAYLIST && (pastedText != null || OwnPastedList.isPasted(canonical))
    if (pasted) {
        // A list with no address: its url is its own id's, never another source's (a peer's row could say anything).
        canonical = OwnPastedList.urlFor(id)
        if (pastedText != null) {
            when (val c = OwnPastedList.check(pastedText)) {
                is OwnPastedCheck.Refused -> errors[OwnField.URL] = c.message
                is OwnPastedCheck.Ok -> content = c
            }
        } else if (url.trim() != canonical) {
            errors[OwnField.URL] = "Esta lista pegada no corresponde a esta fuente"
        }
    } else when (val r = OwnSourceValidator.checkUrl(url)) {
        is OwnUrlCheck.Refused -> errors[OwnField.URL] = r.message
        is OwnUrlCheck.Ok -> {
            canonical = r.url
            cleartext = r.cleartext
            if (OwnSourceValidator.isDuplicate(r.url, otherUrls)) errors[OwnField.URL] = "Ya agregaste esa dirección"
        }
    }
    OwnSourceValidator.checkHeaderValue(userAgent)?.let { errors[OwnField.USER_AGENT] = it }
    OwnSourceValidator.checkHeaderValue(referer)?.let { errors[OwnField.REFERER] = it }
    var epg: String? = null
    var logoText: String? = null
    when (kind) {
        OwnKind.CHANNEL -> {
            OwnSourceValidator.checkLogo(logo)?.let { errors[OwnField.LOGO] = it }
            logoText = logo.trim().ifEmpty { null }
        }
        OwnKind.PLAYLIST -> if (epgUrl.isNotBlank()) {
            when (val r = OwnSourceValidator.checkUrl(epgUrl)) {
                is OwnUrlCheck.Refused -> errors[OwnField.EPG] = r.message
                is OwnUrlCheck.Ok -> epg = r.url
            }
        }
    }
    if (errors.isNotEmpty()) return OwnFormResult.Invalid(errors)
    return OwnFormResult.Valid(
        OwnLiveSourceEntity(
            id = id, kind = kind.name, name = name.trim(), url = canonical,
            groupName = if (kind == OwnKind.CHANNEL) groupName.trim().ifEmpty { null } else null,
            logo = logoText, epgUrl = epg,
            userAgent = userAgent.ifEmpty { null }, referer = referer.ifEmpty { null },
            refreshHours = if (kind == OwnKind.PLAYLIST) refreshHours.coerceIn(0, MAX_REFRESH_HOURS) else 0,
            contentDigest = content?.digest,
        ),
        cleartext,
        content,
    )
}

fun OwnLiveSourceEntity.toForm() = OwnSourceForm(
    kind = OwnKind.valueOf(kind), name = name, url = url, groupName = groupName.orEmpty(), logo = logo.orEmpty(),
    epgUrl = epgUrl.orEmpty(), userAgent = userAgent.orEmpty(), referer = referer.orEmpty(), refreshHours = refreshHours,
)
