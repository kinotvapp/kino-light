package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class InstallReadFailureTest {
    @Test fun `no network reads as a connection problem, in Spanish`() {
        val expected = "No hay conexión a internet. Revisa tu conexión y vuelve a intentar."
        assertEquals(expected, installReadFailureMessage(UnknownHostException("Unable to resolve host \"raw.githubusercontent.com\"")))
        assertEquals(expected, installReadFailureMessage(ConnectException("Failed to connect")))
    }

    @Test fun `a timeout says GitHub took too long`() {
        assertEquals("GitHub tardó demasiado en responder. Intenta de nuevo en un rato.", installReadFailureMessage(SocketTimeoutException("timeout")))
    }

    @Test fun `an HTTP error names its code`() {
        assertEquals("GitHub respondió con un error (503). Intenta de nuevo en un rato.", installReadFailureMessage(PluginFetchStatusException(503)))
    }

    @Test fun `anything else never shows the raw exception text`() {
        val message = installReadFailureMessage(IOException("unexpected end of stream on https://raw.githubusercontent.com/..."))
        assertEquals("No se pudo leer el plugin de GitHub. Intenta de nuevo en un rato.", message)
        assertFalse(message.contains("unexpected"))
    }
}
