package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginSetting
import com.arkiv.player.data.plugin.PluginSettings
import com.arkiv.player.data.plugin.SettingType

/**
 * The Configurar screen's values while the person edits them. Pure: [problem] is the same check
 * `PluginConfigStore.save` makes, run before anything is written so the screen can say it at once.
 */
data class PluginConfigDraft(
    val pluginId: String,
    val pluginName: String,
    val settings: List<PluginSetting>,
    val values: Map<String, Any?>,
    /** Spanish: why the last save was refused, or null. */
    val error: String? = null,
    val saving: Boolean = false,
) {
    fun text(key: String): String = values[key] as? String ?: ""
    fun toggle(key: String): Boolean = values[key] as? Boolean ?: false
    fun with(key: String, value: Any?): PluginConfigDraft = copy(values = values + (key to value), error = null)

    /** The first reason the values can't be saved, or null. */
    fun problem(): String? = settings.firstNotNullOfOrNull { s ->
        val v = values[s.key]
        val blank = v == null || (v is String && v.isBlank())
        when {
            blank && s.required -> "Completa \"${s.label}\""
            blank -> null
            else -> PluginSettings.validateValue(s, v)
        }
    }

    companion object {
        /** Every setting present, so each field has a value to show (a toggle false, a select its default). */
        fun of(pluginId: String, pluginName: String, settings: List<PluginSetting>, stored: Map<String, Any>): PluginConfigDraft =
            PluginConfigDraft(
                pluginId, pluginName, settings,
                settings.associate { s -> s.key to (stored[s.key] ?: s.default ?: if (s.type == SettingType.TOGGLE) false else "") },
            )
    }
}
