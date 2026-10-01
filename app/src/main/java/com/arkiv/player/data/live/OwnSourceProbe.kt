package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PrivateAddressException
import kotlinx.coroutines.CancellationException

sealed interface OwnProbe {
    data class Ok(val message: String) : OwnProbe
    data class Failed(val message: String) : OwnProbe
}

/**
 * "Probar": downloads the address (through the gated own-hosts client) and says, in Spanish, whether
 * it looks like what the person said it is. Optional: saving never depends on it, because some
 * servers only answer a real player.
 */
object OwnSourceProbe {
    private const val CHANNEL_BYTES = 256_000L

    fun classify(kind: OwnKind, bytes: ByteArray): OwnProbe {
        if (bytes.isEmpty()) return OwnProbe.Failed("El servidor no devolvió nada")
        val text = M3uParser.decode(bytes)
        val head = text.trimStart().take(64).lowercase()
        val isWebPage = head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml")
        // The format is told by the content, never by the extension: a .m3u may answer W3U JSON and the other way round.
        val json = LenientJson.looksLikeJson(text)
        return when (kind) {
            OwnKind.CHANNEL -> when {
                isWebPage -> OwnProbe.Failed("La dirección devuelve una página web, no un video")
                json && bytes.size <= W3uPlaylistFetcher.MAX_W3U_BYTES && W3uParser.parse(text) != null ->
                    OwnProbe.Failed("Esto es una lista Wiseplay (W3U), no un canal: agrégala como lista")
                text.trimStart().startsWith("#EXTM3U") && !text.contains("#EXT-X-") && M3uParser.parse(text, maxEntries = 1).total > 0 ->
                    OwnProbe.Failed("Esto es una lista M3U de canales, no un canal: agrégala como lista")
                text.trimStart().startsWith("#EXTM3U") -> OwnProbe.Ok("Se ve bien: es una lista de reproducción HLS")
                else -> OwnProbe.Ok("La dirección responde (parece un stream directo)")
            }
            OwnKind.PLAYLIST -> if (json) w3u(text, bytes.size) else {
                val n = if (isWebPage) 0 else M3uParser.parse(text, maxEntries = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER).entries.size
                if (n == 0) OwnProbe.Failed("No parece una lista M3U ni W3U: no encontré canales")
                else OwnProbe.Ok("Encontré $n ${if (n == 1) "canal" else "canales"}")
            }
        }
    }

    private fun w3u(text: String, size: Int): OwnProbe {
        if (size > W3uPlaylistFetcher.MAX_W3U_BYTES) return OwnProbe.Failed("La lista es demasiado grande")
        val list = W3uParser.parse(text) ?: return OwnProbe.Failed("Es un archivo JSON, pero no una lista Wiseplay (W3U) que se pueda leer")
        val n = list.entries.size
        val links = list.links.size
        if (n == 0 && links == 0) return OwnProbe.Failed("La lista Wiseplay (W3U) no tiene canales que se puedan reproducir")
        val channels = "encontré $n ${if (n == 1) "canal" else "canales"}"
        val linked = when (links) {
            0 -> ""
            1 -> " y 1 lista enlazada, que se carga al guardar"
            else -> " y $links listas enlazadas, que se cargan al guardar"
        }
        return OwnProbe.Ok("Lista Wiseplay (W3U): $channels$linked")
    }

    suspend fun run(kind: OwnKind, url: String, headers: Map<String, String>, fetcher: LivePlaylistFetcher): OwnProbe {
        val max = if (kind == OwnKind.CHANNEL) CHANNEL_BYTES else PluginLiveContract.MAX_PLAYLIST_BYTES
        return try {
            classify(kind, fetcher.fetch(url, headers, max))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlaylistTooLargeException) {
            // A live stream (.ts, .mp4) never ends: it answered, and that is all a channel needs to prove.
            if (kind == OwnKind.CHANNEL) OwnProbe.Ok("La dirección responde (parece un stream directo)")
            else OwnProbe.Failed("La lista es demasiado grande")
        } catch (e: Exception) {
            OwnProbe.Failed("No se pudo leer la dirección: ${friendly(e)}")
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is PrivateAddressException -> "apunta a tu red local, que no se puede usar"
        is java.net.UnknownHostException -> "no encontré ese servidor"
        is java.net.SocketTimeoutException -> "el servidor tardó demasiado"
        else -> e.message?.take(120) ?: "error desconocido"
    }
}
