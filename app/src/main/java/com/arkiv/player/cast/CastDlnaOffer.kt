package com.arkiv.player.cast

import com.arkiv.player.dlna.DlnaDevice

/**
 * "Probar por DLNA en <TV>" in the Chromecast's trouble dialog. Many TVs with a built-in
 * Chromecast are DLNA renderers too (the KALLEY, Android TVs, LG and Samsung with Cast), and a
 * title the Cast receiver will not play may still play over DLNA, where the phone has more routes
 * (the remux to a whole MP4, the continuous TS). Pure.
 *
 * Offered only after the Chromecast failed the same title [MIN_FAILURES] times (its own fallbacks,
 * the direct→proxy reload and the automatic retry, already spent) and only for a renderer that is
 * the SAME TV: found at the Cast device's IP address, or, failing that, with its very name.
 */
object CastDlnaOffer {
    /** Failures of the same title on the Chromecast before DLNA is offered. */
    const val MIN_FAILURES = 2

    /** Whether to look for the TV over DLNA: failed often enough, and the player screen shows that title (it does the casting). */
    fun worthLooking(failures: Int, screenOnTitle: Boolean): Boolean = failures >= MIN_FAILURES && screenOnTitle

    /**
     * The renderer among [devices] that is the Cast device at [castIp] named [castName]: the one whose
     * control URL is on that IP first, else the one with the same name (case, spaces and punctuation
     * aside). Null when none is that TV: a different TV is never offered.
     */
    fun match(castIp: String?, castName: String?, devices: List<DlnaDevice>): DlnaDevice? {
        val ip = castIp?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() }
        if (ip != null) devices.firstOrNull { hostOf(it.controlUrl) == ip }?.let { return it }
        val name = normalized(castName) ?: return null
        return devices.firstOrNull { normalized(it.friendlyName) == name }
    }

    /** The button's text. */
    fun label(device: DlnaDevice): String = "Probar por DLNA en ${device.friendlyName}"

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()?.trim('[', ']')

    private fun normalized(name: String?): String? =
        name?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length >= 3 }
}
