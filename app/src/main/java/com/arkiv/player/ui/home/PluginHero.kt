package com.arkiv.player.ui.home

import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.plugin.PluginHomeRow
import com.arkiv.player.data.plugin.PluginOutput

/**
 * What both homes' hero shows when "Continuar viendo" has nothing: the first item of the first
 * installed plugin's Home row ([PluginHomeRows] order, i.e. install order). Generic on purpose --
 * Xuper's rows reach Home as plugin rows like any other plugin's, so no source gets a carve-out.
 */
data class PluginHeroPick(val row: PluginHomeRow, val item: GatewayResult) {
    /** The landscape art first (the hero is a wide box), the portrait poster only if missing. */
    val imageUrl: String?
        get() = item.extra["backdrop"]?.ifBlank { null } ?: item.extra["poster"]?.ifBlank { null }
}

/** The hero fallback over [rows]; null while they load or when no plugin contributes an item. */
fun pluginHeroPick(rows: List<PluginHomeRow>): PluginHeroPick? =
    rows.firstNotNullOfOrNull { row -> row.items.firstOrNull()?.let { PluginHeroPick(row, it) } }

/**
 * "Xuper  ·  Película  ·  ★ 7.7": the plugin's name, the item's kind and its rating, omitting what
 * the plugin didn't send. Genres stay out: plugins send them in whatever vocabulary they like.
 */
fun PluginHeroPick.meta(): String = listOfNotNull(
    row.pluginName.ifBlank { null },
    when (item.kind) {
        "series" -> "Serie"
        PluginOutput.KIND_LIVE -> "En vivo"
        else -> "Película"
    },
    item.extra["rating"]?.ifBlank { null }?.let { "★ $it" },
).joinToString("  ·  ")
