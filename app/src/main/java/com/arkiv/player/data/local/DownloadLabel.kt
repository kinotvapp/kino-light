package com.arkiv.player.data.local

/**
 * The status line a chapter row shows under its name, or null if there's nothing to say.
 *
 * The bar and the icon say the same thing in colors and shapes, but that's only understood if you
 * already know what they mean: the percentage in words is what makes "it's downloading" and "it
 * failed because of this" readable without translating anything.
 */
object DownloadLabel {

    fun of(state: DownloadDisplayState): String? = when (state) {
        DownloadDisplayState.NotDownloaded -> null
        DownloadDisplayState.Queued -> "En cola"
        is DownloadDisplayState.Downloading ->
            state.fraction?.let { "Bajando ${(it * 100).toInt()}%" } ?: "Bajando…"
        DownloadDisplayState.Done -> "Descargado"
        // The real reason, not a bare "failed": it's the only thing that tells the user whether
        // this fixes itself by retrying or isn't worth it.
        is DownloadDisplayState.Failed -> state.reason?.takeIf { it.isNotBlank() } ?: "Falló la descarga"
        DownloadDisplayState.NeedsConfirmation -> "Pesa mucho: confírmala en Descargas"
    }
}
