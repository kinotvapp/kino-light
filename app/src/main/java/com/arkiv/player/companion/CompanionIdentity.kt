package com.arkiv.player.companion

import android.content.Context
import android.os.Build
import java.util.UUID

class CompanionIdentity(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_companion", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        runCatching { prefs.edit().putString("device_id", it).apply() }
    }

    var deviceName: String
        get() = prefs.getString("device_name", null)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        set(value) { runCatching { prefs.edit().putString("device_name", value.trim()).apply() } }
}
