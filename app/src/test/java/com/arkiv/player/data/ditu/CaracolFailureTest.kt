package com.arkiv.player.data.ditu

import androidx.media3.common.PlaybackException
import com.arkiv.player.data.gateway.GatewayException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** That no technical Caracol error reaches the person raw. */
class CaracolFailureTest {

    /** How it reaches search and the player: `DituSource` wraps `DituClient`'s, which wraps OkHttp's. */
    private fun wrapped(cause: Throwable): Throwable {
        val fromClient = DituException("Caracol no responde: ${cause.message}", cause)
        return GatewayException(fromClient.message!!, fromClient)
    }

    // --- search and resolution ---------------------------------------------------------

    @Test fun `no internet connection`() {
        val noDns = wrapped(UnknownHostException("Unable to resolve host \"middleware.ditu.caracoltv.com\""))
        assertEquals("Caracol no respondió: sin conexión a internet", CaracolFailure.inSearch(noDns, noDns.message))
        assertEquals("Caracol no respondió: sin conexión a internet", CaracolFailure.onOpen(noDns))
    }

    /** A refused connection can happen with internet up: it isn't told "no internet". */
    @Test fun `a refused connection isn't no internet`() {
        for (cause in listOf(ConnectException("Failed to connect to /10.0.0.1:443"), NoRouteToHostException("No route to host"))) {
            val e = wrapped(cause)
            assertEquals("Caracol no respondió", CaracolFailure.inSearch(e, e.message))
            assertEquals("Caracol no respondió", CaracolFailure.onOpen(e))
        }
        assertEquals(
            "Caracol no respondió",
            CaracolFailure.inSearch(null, "Caracol no responde: Failed to connect to /10.0.0.1:443"),
        )
    }

    @Test fun `a timeout`() {
        for (cause in listOf(SocketTimeoutException("Read timed out"), InterruptedIOException("timeout"))) {
            val e = wrapped(cause)
            assertEquals("Caracol tardó demasiado en responder", CaracolFailure.inSearch(e, e.message))
            assertEquals("Caracol tardó demasiado en responder", CaracolFailure.onOpen(e))
        }
    }

    @Test fun `a 5xx means Caracol is down and a 4xx doesn't`() {
        val five = GatewayException("x", DituException("Caracol respondió 503 en TRAY/SEARCH/VOD", httpCode = 503))
        assertEquals("Caracol está fallando en este momento", CaracolFailure.inSearch(five, null))
        assertEquals("Caracol está fallando en este momento", CaracolFailure.onOpen(five))

        val four = GatewayException("x", DituException("Caracol respondió 404 en TRAY/SEARCH/VOD", httpCode = 404))
        assertEquals("Caracol no respondió", CaracolFailure.inSearch(four, null))
        assertEquals("No se pudo reproducir en Caracol", CaracolFailure.onOpen(four))
    }

    /** Block reasons already come written for the person: the text doesn't change at all. */
    @Test fun `a Caracol block looks the same as before`() = runTest {
        val fake = FakeDituClient()
        fake.respond("CONTENT/DETAIL/VOD/42", """
        {"resultObj":{"containers":[{"assets":[{"assetType":"MASTER","assetId":7}]}]}}
        """)
        fake.respond("CONTENT/USERDATA/VOD/42", """
        {"resultObj":{"containers":[{"entitlement":{"isGeoBlocked":true}}]}}
        """)
        val fromResolve = runCatching { DituResolve(fake).vod(DituRef("42", "VOD")) }.exceptionOrNull()!!
        // As `DituSource.resolve` wraps it.
        val e = GatewayException(fromResolve.message!!, fromResolve)

        assertEquals("Caracol: solo disponible en Colombia", e.message)
        assertEquals("Caracol: solo disponible en Colombia", CaracolFailure.onOpen(e))
    }

    /** Caracol's section plays nothing: its fallback can't talk about playback. */
    @Test fun `Caracol's section has its own fallbacks`() {
        val weird = wrapped(IllegalStateException("JSONObject[\"resultObj\"] not found"))
        assertEquals("No se pudo cargar el catálogo de Caracol", CaracolFailure.onLoadCatalog(weird))
        assertEquals("No se pudieron cargar los canales de Caracol", CaracolFailure.onLoadChannels(weird))

        val noDns = wrapped(UnknownHostException("Unable to resolve host \"x\""))
        assertEquals("Caracol no respondió: sin conexión a internet", CaracolFailure.onLoadCatalog(noDns))
        assertEquals("Caracol no respondió: sin conexión a internet", CaracolFailure.onLoadChannels(noDns))
    }

    @Test fun `whatever isn't recognized is a fallback`() {
        val e = wrapped(IllegalStateException("JSONObject[\"resultObj\"] not found"))
        assertEquals("Caracol no respondió", CaracolFailure.inSearch(e, e.message))
        assertEquals("No se pudo reproducir en Caracol", CaracolFailure.onOpen(e))
        assertEquals("Caracol no respondió", CaracolFailure.inSearch(null, null))
        assertEquals("No se pudo reproducir en Caracol", CaracolFailure.onOpen(null))
    }

    /** `CompositeSource` can send a source's error with no exception: the text is what's left. */
    @Test fun `just the text is understood too`() {
        assertEquals(
            "Caracol no respondió: sin conexión a internet",
            CaracolFailure.inSearch(null, "Caracol no responde: Unable to resolve host \"x\""),
        )
        assertEquals("Caracol tardó demasiado en responder", CaracolFailure.inSearch(null, "Caracol no responde: timeout"))
        assertEquals("Caracol está fallando en este momento", CaracolFailure.inSearch(null, "Caracol respondió 502 en TRAY/SEARCH/VOD"))
    }

    @Test fun `never the raw text`() {
        val raws = listOf(
            "Unable to resolve host \"middleware.ditu.caracoltv.com\"",
            "timeout",
            "Caracol respondió 503 en TRAY/SEARCH/VOD",
            "java.lang.NullPointerException: boom",
        )
        for (raw in raws) {
            val search = CaracolFailure.inSearch(RuntimeException(raw), raw)
            assertFalse(search, search.contains(raw))
            val open = CaracolFailure.onOpen(RuntimeException(raw))
            assertFalse(open, open.contains(raw))
        }
    }

    // --- the player ----------------------------------------------------------------------

    @Test fun `the player speaks by the code's family`() {
        val network = listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        for (c in network) assertEquals("Se cortó la conexión con Caracol", CaracolFailure.onPlayback(c, isTv = true))

        val drm = listOf(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, PlaybackException.ERROR_CODE_DRM_UNSPECIFIED)
        for (c in drm) assertEquals("Caracol no autorizó la reproducción", CaracolFailure.onPlayback(c, isTv = true))

        val device = listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
        for (c in device) {
            assertEquals("El televisor no pudo reproducir este video", CaracolFailure.onPlayback(c, isTv = true))
            assertEquals("Este celular no pudo reproducir este video", CaracolFailure.onPlayback(c, isTv = false))
        }

        assertEquals(
            "No se pudo reproducir en Caracol",
            CaracolFailure.onPlayback(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, isTv = true),
        )
    }

    @Test fun `the player never shows the code's name`() {
        for (c in listOf(1000, 1002, 2001, 3002, 4003, 5001, 6004, 7000, 123456)) {
            for (tv in listOf(true, false)) {
                val text = CaracolFailure.onPlayback(c, tv)
                assertFalse(text, text.contains("ERROR_CODE"))
                assertFalse(text, text.contains(c.toString()))
            }
        }
    }
}
