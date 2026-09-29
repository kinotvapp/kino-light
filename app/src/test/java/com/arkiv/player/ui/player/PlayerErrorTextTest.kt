package com.arkiv.player.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import com.arkiv.player.data.plugin.HostNotAllowedException
import com.arkiv.player.data.plugin.PrivateAddressException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * What the player SAYS when a stream can't play: a short Spanish sentence for the person, never
 * ExoPlayer's English ("Unexpected runtime error", "Source error", "Decoder init failed"); the
 * technical detail stays in the log. 4KHDHub's 4K stream (HEVC, EAC3/TrueHD) failed on the KALLEY
 * R3 with "4KHDHub: Unexpected runtime error" (a StuckPlayerException).
 */
class PlayerErrorTextTest {
    private fun say(code: Int, cause: Throwable? = null, height: Int = 0, mime: String? = null) =
        playerErrorMessage(PlaybackException("english detail", cause, code), height, mime)

    @Test fun `a decoder that cannot take the format says so, naming 4K and HEVC when that is what it is`() {
        assertEquals(
            "Este aparato no puede reproducir este formato de video (4K/HEVC)",
            say(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, height = 2160, mime = "video/hevc"),
        )
        assertEquals("Este aparato no puede reproducir este formato de video (4K)", say(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, height = 2160, mime = "video/avc"))
        assertEquals("Este aparato no puede reproducir este formato de video (HEVC)", say(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, height = 1080, mime = "video/hevc"))
        assertEquals("Este aparato no puede reproducir este formato de video", say(PlaybackException.ERROR_CODE_DECODING_FAILED, height = 720, mime = "video/avc"))
        assertEquals("Este aparato no puede reproducir este formato de video", say(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
    }

    @Test fun `a stuck 4K stream says the device may not cope, a stuck small one says it stopped`() {
        val stuck = IllegalStateException("wrapped", StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, 10_000))
        assertEquals(
            "El video se quedó detenido: puede que este aparato no pueda con este formato (4K/HEVC)",
            say(PlaybackException.ERROR_CODE_UNSPECIFIED, stuck, height = 2160, mime = "video/hevc"),
        )
        assertEquals("El video se quedó detenido y no avanzó", say(PlaybackException.ERROR_CODE_TIMEOUT, stuck, height = 720, mime = "video/avc"))
    }

    @Test fun `audio the device cannot play is told apart`() {
        assertEquals("Este aparato no puede reproducir el audio de este video", say(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
    }

    @Test fun `network and file problems say what happened on the server's side`() {
        assertEquals("No se pudo conectar con el servidor del video", say(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals("No se pudo conectar con el servidor del video", say(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals("El servidor del video respondió con un error", say(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals("El video ya no está en el servidor", say(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals("Se cortó la conexión con el servidor del video", say(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("x")))
        assertEquals("El video llegó dañado o en un formato que Kino no reconoce", say(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test fun `a refused server is named, the home network said plainly`() {
        assertEquals(
            "El video pidió un servidor no permitido (seg.other.example)",
            say(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("open", HostNotAllowedException("seg.other.example"))),
        )
        assertEquals(
            "El video apunta a una dirección de tu red local",
            say(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("open", PrivateAddressException("cdn.rebind.example"))),
        )
    }

    @Test fun `anything else gets a Spanish fallback, never ExoPlayer's English`() {
        val fallback = say(PlaybackException.ERROR_CODE_UNSPECIFIED, RuntimeException("Unexpected runtime error"), height = 1080)
        assertEquals("No se pudo reproducir este video", fallback)
        for (code in listOf(PlaybackException.ERROR_CODE_UNSPECIFIED, PlaybackException.ERROR_CODE_REMOTE_ERROR, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, 7001, 42)) {
            val text = say(code, RuntimeException("Unexpected runtime error"))
            assertFalse(text, "english" in text || "Unexpected" in text || "error" == text)
        }
    }
}
