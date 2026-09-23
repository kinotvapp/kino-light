package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadLabelTest {

    @Test
    fun `downloading says the percentage`() {
        assertEquals("Bajando 42%", DownloadLabel.of(DownloadDisplayState.Downloading(0.42f)))
    }

    @Test
    fun `downloading with no known remaining does not make up a number`() {
        assertEquals("Bajando…", DownloadLabel.of(DownloadDisplayState.Downloading(null)))
    }

    @Test
    fun `queued says so`() {
        assertEquals("En cola", DownloadLabel.of(DownloadDisplayState.Queued))
    }

    @Test
    fun `downloaded says so`() {
        assertEquals("Descargado", DownloadLabel.of(DownloadDisplayState.Done))
    }

    @Test
    fun `the failure shows its reason, not a generic message`() {
        assertEquals(
            "Este episodio no tiene un archivo descargable",
            DownloadLabel.of(DownloadDisplayState.Failed("Este episodio no tiene un archivo descargable")),
        )
    }

    @Test
    fun `a failure with no reason is still announced`() {
        assertEquals("Falló la descarga", DownloadLabel.of(DownloadDisplayState.Failed(null)))
    }

    @Test
    fun `the heavy torrent explains what it is waiting for`() {
        assertEquals(
            "Pesa mucho: confírmala en Descargas",
            DownloadLabel.of(DownloadDisplayState.NeedsConfirmation),
        )
    }

    @Test
    fun `what nobody queued adds no line`() {
        assertNull(DownloadLabel.of(DownloadDisplayState.NotDownloaded))
    }
}
