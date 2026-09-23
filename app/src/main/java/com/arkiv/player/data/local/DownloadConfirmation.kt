package com.arkiv.player.data.local

/** What can be done with a chapter's download from its own row. */
enum class DownloadAction {
    /** Nothing has downloaded yet: the row is removed and the chapter offers the download button again. */
    REMOVE_FROM_QUEUE,

    /** It's downloading: it's stopped keeping what already downloaded, so it can resume from there. */
    CANCEL,

    /** Already on the device: the file is deleted. */
    DELETE,
}

/** The texts of the dialog that confirms a [DownloadAction]. */
data class ConfirmationText(
    val title: String,
    val body: String,
    val confirm: String,
    val dismiss: String,
)

/**
 * What a chapter row offers depending on how its download is going, and in what words it asks.
 *
 * It always asks: the control is tiny (a 48dp slot that shares a row with play and "mark as
 * watched") and all three actions are expensive if tapped by accident — cancelling throws away
 * minutes of download, deleting throws away the whole file.
 */
object DownloadConfirmation {

    fun actionFor(state: DownloadDisplayState): DownloadAction? = when (state) {
        DownloadDisplayState.Queued -> DownloadAction.REMOVE_FROM_QUEUE
        is DownloadDisplayState.Downloading -> DownloadAction.CANCEL
        DownloadDisplayState.Done -> DownloadAction.DELETE
        // Retrying destroys nothing, so it doesn't ask. And what nobody queued has nothing to undo.
        is DownloadDisplayState.Failed, DownloadDisplayState.NotDownloaded, DownloadDisplayState.NeedsConfirmation -> null
    }

    fun text(action: DownloadAction, chapterName: String?): ConfirmationText {
        val subject = if (chapterName.isNullOrBlank()) "El capítulo" else "«$chapterName»"
        return when (action) {
            DownloadAction.REMOVE_FROM_QUEUE -> ConfirmationText(
                title = "¿Sacarla de la cola?",
                body = "$subject todavía no empezó a bajar, así que no se pierde nada.",
                confirm = "Sacar de la cola",
                dismiss = "Dejarla",
            )
            DownloadAction.CANCEL -> ConfirmationText(
                title = "¿Cancelar la descarga?",
                body = "Se conserva lo que ya bajó de $subject: al reintentar sigue desde ahí, no " +
                    "empieza de cero.",
                confirm = "Cancelar descarga",
                // Can't just say "Cancelar": next to "Cancelar descarga" nobody would know which is which.
                dismiss = "Seguir bajando",
            )
            DownloadAction.DELETE -> ConfirmationText(
                title = "¿Borrar la descarga?",
                body = "$subject se borra del dispositivo. Sigue en tu biblioteca y se puede ver por " +
                    "internet.",
                confirm = "Borrar",
                dismiss = "No borrar",
            )
        }
    }
}
