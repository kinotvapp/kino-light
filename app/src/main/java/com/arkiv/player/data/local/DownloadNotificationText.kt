package com.arkiv.player.data.local

/**
 * The download notification's texts.
 *
 * Before the notification was published ONCE at start, said "Descargando" and as text the raw
 * episode id (`magis:2AD2591D…::e1`), and wasn't touched again for the minutes the download took:
 * there was no way to tell if it was progressing or how many chapters were waiting their turn.
 */
object DownloadNotificationText {

    /**
     * The series and the chapter, whatever is known of the two, or null if neither is known.
     * Never the raw id (`magis:2AD2591D…::e1`), which is what it used to show and tells nobody
     * anything.
     */
    fun name(series: String?, chapter: String?): String? =
        listOf(series, chapter).filterNot { it.isNullOrBlank() }
            .joinToString(" · ")
            .ifBlank { null }

    /** Notification title while downloading. */
    fun title(series: String?, chapter: String?): String =
        name(series, chapter)?.let { "Bajando $it" } ?: "Bajando un capítulo"

    /**
     * Subtitle of the "Download complete" notification: WHICH chapter finished. The title already
     * says it finished; without this, with several downloads in a row, there was no way to tell
     * which was which.
     */
    fun done(series: String?, chapter: String?): String =
        name(series, chapter) ?: "Ya lo puedes ver sin conexión"

    /**
     * Subtitle: the percentage, and how many are waiting their turn (the queue is one at a time,
     * so without that data it looks like the other chapters were lost).
     *
     * [fraction] null = how much is left isn't known; it says so, it doesn't make up a 0%.
     */
    fun subtitle(fraction: Float?, queued: Int): String {
        val progress = fraction?.let { "${(it * 100).toInt()}%" } ?: "Preparando…"
        val rest = if (queued > 0) " · $queued más en cola" else ""
        return progress + rest
    }
}
