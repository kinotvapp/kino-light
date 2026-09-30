package com.arkiv.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnSourcesCopyTest {
    @Test fun `a source is shown by its server, not by its long address`() {
        assertEquals("raw.githubusercontent.com", OwnSourcesCopy.hostOf("https://raw.githubusercontent.com/ice-dev-x/x/main/ecuador.m3u"))
        assertEquals("8.8.8.8", OwnSourcesCopy.hostOf("http://8.8.8.8:8080/live/a.m3u8?token=abc"))
    }

    @Test fun `something that is not an address is shown as it is`() {
        assertEquals("hola", OwnSourcesCopy.hostOf("hola"))
    }

    @Test fun `the URL fields carry an example placeholder`() {
        assertEquals("Ej.: http://miservidor.com/canal.m3u8", OwnSourcesCopy.URL_CHANNEL_HINT)
        assertEquals("Ej.: http://miservidor.com/get.php?username=...&password=...&type=m3u_plus", OwnSourcesCopy.URL_PLAYLIST_HINT)
    }
}
