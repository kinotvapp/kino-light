package com.arkiv.player.ui.update

import com.arkiv.player.data.update.UpdateCheckResult

/** What Settings' "Buscar actualizaciones" tells the person (phone and TV share it). */
object ManualUpdateCheck {
    const val UP_TO_DATE = "Ya tienes la última versión"
    const val FAILED = "No pudimos revisar si hay una versión nueva. Revisa tu conexión e intenta de nuevo."

    /** The toast for [result], or null when there is an update to show in the dialog instead. */
    fun message(result: UpdateCheckResult): String? = when (result) {
        is UpdateCheckResult.Available -> null
        UpdateCheckResult.UpToDate -> UP_TO_DATE
        is UpdateCheckResult.Failed -> FAILED
    }
}
