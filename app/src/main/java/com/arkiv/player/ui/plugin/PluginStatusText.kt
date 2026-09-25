package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.InstallException
import com.arkiv.player.data.plugin.PluginDamagedException
import com.arkiv.player.data.plugin.PluginException
import com.arkiv.player.data.plugin.PluginStatus
import com.arkiv.player.data.plugin.PluginTimeoutException
import java.io.IOException

/** How a plugin's state reads in Ajustes ▸ Plugins. */
fun pluginStatusText(status: PluginStatus): String = when (status) {
    PluginStatus.ACTIVE -> "Activo"
    PluginStatus.DISABLED -> "Desactivado"
    PluginStatus.UNRESPONSIVE -> "No responde — actívalo para volver a intentar"
    PluginStatus.UPDATE_PENDING -> "Actualización disponible — requiere tu aprobación"
    PluginStatus.DAMAGED -> "Archivos dañados, reinstálalo"
    PluginStatus.NEEDS_SETUP -> "Falta configurar"
}

/**
 * What the person reads when an Ajustes ▸ Plugins action throws. Only [InstallException] carries a
 * message written for the screen (the installer builds it in Spanish); every other exception's
 * message is an engine/network detail -- [PluginTimeoutException]'s even embeds the capability's
 * English name ("search no respondió…") -- so it is replaced, never shown.
 */
fun pluginErrorText(e: Throwable): String = when (e) {
    is InstallException -> e.message?.takeIf { it.isNotBlank() } ?: GENERIC_ERROR
    is PluginTimeoutException -> "El plugin no respondió a tiempo"
    is PluginDamagedException -> "Archivos dañados, reinstálalo"
    is PluginException -> "El plugin falló, vuelve a intentarlo"
    is IOException -> "No hay conexión, vuelve a intentarlo"
    else -> GENERIC_ERROR
}

private const val GENERIC_ERROR = "No se pudo completar, vuelve a intentarlo"
