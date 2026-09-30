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
        return when (kind) {
            OwnKind.CHANNEL -> when {
                isWebPage -> OwnProbe.Failed("La dirección devuelve una página web, no un video")
                text.trimStart().startsWith("#EXTM3U") -> OwnProbe.Ok("Se ve bien: es una lista de reproducción HLS")
                else -> OwnProbe.Ok("La dirección responde (parece un stream directo)")
            }
            OwnKind.PLAYLIST -> {
                val n = if (isWebPage) 0 else M3uParser.parse(text, maxEntries = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER).entries.size
                if (n == 0) OwnProbe.Failed("No parece una lista M3U: no encontré canales")
                else OwnProbe.Ok("Encontré $n ${if (n == 1) "canal" else "canales"}")
            }
        }
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
