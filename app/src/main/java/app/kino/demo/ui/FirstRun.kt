package app.kino.demo.ui

import android.content.Context

private const val PREFS = "demo"
private const val PICKER_DONE = "first_run_done"

/** Whether "Elige tus fuentes" was already passed once on this device. */
fun isPickerDone(context: Context): Boolean =
    runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PICKER_DONE, false) }.getOrDefault(false)

fun markPickerDone(context: Context) {
    runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PICKER_DONE, true).apply() }
}
