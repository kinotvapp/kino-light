package com.arkiv.player.data.ai

import android.content.Context

/** Model memory, persisted in SharedPreferences. Losing it only costs having to learn again. */
internal class PreferencesStore(context: Context) : MemoryStore {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_ia", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun save(json: String) { prefs.edit().putString(KEY, json).apply() }
    private companion object { const val KEY = "memoria_de_modelos" }
}
