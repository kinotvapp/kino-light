package com.arkiv.player.data.ditu

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Caracol's errors said for the person, not for whoever programs.
 *
 * Before, the screen showed the error as-is: in search, the exception's message after "Caracol no
 * respondió:"; in the player, ExoPlayer's error code name (`errorCodeName`). That's useful for
 * diagnosing —and that's why it still goes to the log where it's produced—, but it doesn't tell
 * the person what happened.
 *
 * This is the only place it gets translated: the search's line ([inSearch]), what couldn't be
 * opened ([onOpen]), what cut off while playing ([onPlayback]) and what didn't load in Caracol's
 * section ([onLoadCatalog], [onLoadChannels]). Whatever isn't recognized falls into a generic one,
 * never into the raw text. The exception is [DituEntitlement]'s reasons: they already come written
 * for the person and pass through as-is.
 *
 * Caracol only: Magis's errors are shown as always.
 */
internal object CaracolFailure {

    /** What happened, in terms that matter to the person. */
    sealed interface Reason {
        /** Caracol said why it won't let something be watched: [DituEntitlement.block]'s text. */
        data class Blocked(val reason: String) : Reason
        /** Caracol's name doesn't resolve, which is what happens with no internet. */
        object NoConnection : Reason
        /** The connection didn't get made (refused, no route): can happen with internet up. */
        object NoResponse : Reason
        object Timeout : Reason
        object ServerDown : Reason
        object Unknown : Reason
    }

    /**
     * What happened per [error] and its whole cause chain: `DituSource` wraps `DituClient`'s in a
     * `GatewayException`, and `DituClient` wraps OkHttp's in a [DituException], so what says what
     * happened is usually a couple of causes down.
     *
     * The exceptions' type goes first; if none says anything, their text and [text], which is all
     * there is when the exception didn't arrive.
     */
    fun classify(error: Throwable?, text: String? = null): Reason {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        chain.firstNotNullOfOrNull { (it as? DituException)?.blockReason }?.let { return Reason.Blocked(it) }
        chain.firstNotNullOfOrNull { byType(it) }?.let { return it }
        return (chain.mapNotNull { it.message } + listOfNotNull(text))
            .firstNotNullOfOrNull { byText(it) }
            ?: Reason.Unknown
    }

    /** The search's line when Caracol brought no results because of an error. */
    fun inSearch(error: Throwable?, text: String?): String =
        phrase(classify(error, text), fallback = "Caracol no respondió")

    /** What the player says when Caracol's couldn't be opened (resolving it failed). */
    fun onOpen(error: Throwable?): String =
        phrase(classify(error), fallback = PLAYBACK_FALLBACK)

    /** What Caracol's section says when its catalog didn't load: nothing plays there. */
    fun onLoadCatalog(error: Throwable?): String =
        phrase(classify(error), fallback = "No se pudo cargar el catálogo de Caracol")

    /** What Caracol's section's "En vivo" tab says when the channels didn't load. */
    fun onLoadChannels(error: Throwable?): String =
        phrase(classify(error), fallback = "No se pudieron cargar los canales de Caracol")

    /**
     * What the player says when `DituExoPlayer` gave up, by [code]'s `PlaybackException` family.
     * The families go by thousands: the `ERROR_CODE_IO_*` are the 2000s, the decoding ones
     * (`ERROR_CODE_DECODER_*`, `ERROR_CODE_DECODING_*`) the 4000s, the `ERROR_CODE_AUDIO_TRACK_*`
     * the 5000s and the `ERROR_CODE_DRM_*` the 6000s.
     */
    fun onPlayback(code: Int, isTv: Boolean): String = when (code) {
        in 2000..2999 -> "Se cortó la conexión con Caracol"
        in 6000..6999 -> "Caracol no autorizó la reproducción"
        in 4000..5999 ->
            if (isTv) "El televisor no pudo reproducir este video" else "Este celular no pudo reproducir este video"
        else -> PLAYBACK_FALLBACK
    }

    private fun phrase(reason: Reason, fallback: String): String = when (reason) {
        // "Caracol: <reason>" is how `DituResolve` builds it: the same text that was shown before.
        is Reason.Blocked -> "Caracol: ${reason.reason}"
        Reason.NoConnection -> "Caracol no respondió: sin conexión a internet"
        Reason.NoResponse -> "Caracol no respondió"
        Reason.Timeout -> "Caracol tardó demasiado en responder"
        Reason.ServerDown -> "Caracol está fallando en este momento"
        Reason.Unknown -> fallback
    }

    private fun byType(e: Throwable): Reason? = when {
        e is DituException && e.httpCode?.let { it in 500..599 } == true -> Reason.ServerDown
        // No internet is when the name doesn't resolve. A connection that doesn't get made
        // (refused, no route) can happen with internet up: the person can't be told they have no
        // internet.
        e is UnknownHostException -> Reason.NoConnection
        e is ConnectException || e is NoRouteToHostException -> Reason.NoResponse
        e is SocketTimeoutException -> Reason.Timeout
        // A bare `InterruptedIOException` counts as a timeout only if its message says so.
        e is InterruptedIOException && e.message.orEmpty().contains("timeout", ignoreCase = true) -> Reason.Timeout
        else -> null
    }

    private fun byText(t: String): Reason? = when {
        t.contains("Unable to resolve host", ignoreCase = true) -> Reason.NoConnection
        t.contains("Failed to connect", ignoreCase = true) -> Reason.NoResponse
        t.contains("timeout", ignoreCase = true) || t.contains("timed out", ignoreCase = true) -> Reason.Timeout
        // "Caracol respondió 503 en <path>": the message `DituClient` builds with an error status.
        SERVER_DOWN.containsMatchIn(t) -> Reason.ServerDown
        else -> null
    }

    private val SERVER_DOWN = Regex("""respondió 5\d\d\b""")

    private const val PLAYBACK_FALLBACK = "No se pudo reproducir en Caracol"

    /** A cap on the cause chain, in case one loops back on itself. */
    private const val MAX_CAUSES = 8
}
