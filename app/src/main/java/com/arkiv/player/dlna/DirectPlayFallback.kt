package com.arkiv.player.dlna

/**
 * When a VOD file sent to the TV as it is (a bare MPEG-TS the TV's list mentions) gets rejected, the remux to a real MP4 is the
 * container every TV takes: worth one more try instead of leaving the person with "the TV rejected the video". Measured on an
 * LG webOS that listed `video/mp2t` and still answered `Play` with `HTTP 500 · upnp=501 Action Failed` after fetching the file.
 */
internal object DirectPlayFallback {
    /** UPnP AVTransport: 714 illegal MIME type, 716 resource not found (the renderer does not take that format). */
    private val SET_URI_REJECTIONS = setOf(714, 716)
    private const val ACTION_FAILED = 501

    /** [stage] is the failing step ("set_uri", "play"...); [upnpCode] the UPnP fault code, [http] the HTTP status of the SOAP reply. */
    fun shouldRemux(stage: String, upnpCode: Int?, http: Int): Boolean = when {
        http != 500 || upnpCode == null -> false
        stage == "play" -> upnpCode == ACTION_FAILED
        stage == "set_uri" -> upnpCode in SET_URI_REJECTIONS
        else -> false
    }
}
