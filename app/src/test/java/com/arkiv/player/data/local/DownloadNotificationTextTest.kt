package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNotificationTextTest {

    @Test
    fun `shows the percentage when it's known`() {
        assertEquals("42%", DownloadNotificationText.subtitle(0.42f, queued = 0))
    }

    @Test
    fun `with no known size says it's preparing`() {
        assertEquals("Preparando…", DownloadNotificationText.subtitle(null, queued = 0))
    }

    @Test
    fun `warns how many are waiting their turn`() {
        assertEquals("42% · 3 más en cola", DownloadNotificationText.subtitle(0.42f, queued = 3))
    }

    @Test
    fun `a single one pending goes singular`() {
        assertEquals("42% · 1 más en cola", DownloadNotificationText.subtitle(0.42f, queued = 1))
    }

    @Test
    fun `the title joins series and chapter`() {
        assertEquals("Bajando Daima · E1", DownloadNotificationText.title("Daima", "E1"))
    }

    @Test
    fun `with only one of the two it doesn't leave the separator dangling`() {
        assertEquals("Bajando E1", DownloadNotificationText.title(null, "E1"))
        assertEquals("Bajando Daima", DownloadNotificationText.title("Daima", "  "))
    }

    @Test
    fun `with no name it doesn't show the raw id`() {
        assertEquals("Bajando un capítulo", DownloadNotificationText.title(null, null))
    }

    @Test
    fun `the done notification says which chapter it was`() {
        assertEquals("Daima · E1", DownloadNotificationText.done("Daima", "E1"))
    }

    @Test
    fun `if the chapter isn't known the done notification still says something useful`() {
        assertEquals("Ya lo puedes ver sin conexión", DownloadNotificationText.done(null, null))
    }
}
