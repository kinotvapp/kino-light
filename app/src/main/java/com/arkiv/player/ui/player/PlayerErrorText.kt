package com.arkiv.player.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import com.arkiv.player.data.plugin.HostNotAllowedException
import com.arkiv.player.data.plugin.PrivateAddressException

/**
 * The sentence the player shows when a stream can't play (`StreamExoPlayer`'s final route, shown as
 * "<fuente>: <frase>"): short, Spanish, about what the person can understand -- this device, this
 * format, the video's server -- and never ExoPlayer's own English ("Unexpected runtime error",
 * "Source error"). The technical detail (error code name, cause chain) stays in the log and the crash
 * report, where the caller already puts it.
 *
 * [videoHeight] and [videoMime] describe the video being played when known (the failing renderer's
 * format, else the player's current video format; 0 / null otherwise): they turn a decoder failure
 * or a stall into "(4K/HEVC)" when that is what the stream is, since most TVs and phones here can't
 * decode 4K HEVC.
 */
internal fun playerErrorMessage(error: PlaybackException, videoHeight: Int, videoMime: String?): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES).toList()
    causes.firstNotNullOfOrNull { it as? HostNotAllowedException }?.let { return "El video pidió un servidor no permitido (${it.host.take(100)})" }
    if (causes.any { it is PrivateAddressException }) return "El video apunta a una dirección de tu red local"
    val heavy = heavyFormatTag(videoHeight, videoMime)
    val code = error.errorCode
    return when {
        code in DECODER_CODES || causes.any { it.javaClass.name.endsWith("DecoderInitializationException") } ->
            "Este aparato no puede reproducir este formato de video" + (heavy?.let { " ($it)" } ?: "")
        code in AUDIO_CODES -> "Este aparato no puede reproducir el audio de este video"
        causes.any { it is StuckPlayerException } || code == PlaybackException.ERROR_CODE_TIMEOUT ->
            if (heavy != null) "El video se quedó detenido: puede que este aparato no pueda con este formato ($heavy)"
            else "El video se quedó detenido y no avanzó"
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No se pudo conectar con el servidor del video"
        code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "El servidor del video respondió con un error"
        code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "El video ya no está en el servidor"
        // An extractor that crashed on the bytes (Loader.UnexpectedLoaderException) or refused them
        // (ParserException) is filed under IO_UNSPECIFIED: the data arrived, it could not be read.
        code in PARSING_CODES || causes.any { it is androidx.media3.common.ParserException || it.javaClass.simpleName == "UnexpectedLoaderException" } ->
            "El video llegó dañado o en un formato que Kino no reconoce"
        code in IO_CODES -> "Se cortó la conexión con el servidor del video"
        else -> "No se pudo reproducir este video"
    }
}

/** "4K", "HEVC", "4K/HEVC"… for a video most devices here can't decode; null for an ordinary one. */
internal fun heavyFormatTag(videoHeight: Int, videoMime: String?): String? {
    val mime = videoMime.orEmpty().lowercase()
    val tags = buildList {
        if (videoHeight > 1_080) add("4K")
        when {
            "hevc" in mime || "h265" in mime -> add("HEVC")
            "dolby-vision" in mime -> add("Dolby Vision")
            "av01" in mime || "av1" in mime -> add("AV1")
        }
    }
    return tags.takeIf { it.isNotEmpty() }?.joinToString("/")
}

private const val MAX_CAUSES = 12
private val DECODER_CODES = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
private val AUDIO_CODES = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED..PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
private val IO_CODES = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
private val PARSING_CODES = PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
