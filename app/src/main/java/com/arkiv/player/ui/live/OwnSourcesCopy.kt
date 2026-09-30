package com.arkiv.player.ui.live

import com.arkiv.player.data.live.OwnLive

/** Spanish copy shared by the phone and TV dialogs. Plain Kotlin so it stays unit-testable. */
object OwnSourcesCopy {
    const val ADD_MENU = "Agregar canal o lista"
    const val ADD_CHANNEL = "Agregar un canal"
    const val ADD_PLAYLIST = "Agregar una lista M3U"
    const val MY_SOURCES = "Mis canales y listas"
    const val TITLE_CHANNEL = "Agregar un canal"
    const val TITLE_PLAYLIST = "Agregar una lista M3U"
    const val TITLE_EDIT = "Editar"
    const val NAME = "Nombre"
    const val URL_CHANNEL = "Dirección del canal (.m3u8)"
    const val URL_PLAYLIST = "Dirección de la lista (.m3u)"
    const val GROUP = "Grupo (opcional)"
    const val LOGO = "Logo (opcional)"
    const val EPG = "Guía de programación (opcional, XMLTV)"
    const val USER_AGENT = "User-Agent (opcional)"
    const val REFERER = "Referer (opcional)"
    const val ADVANCED = "Opciones avanzadas"
    const val PROBE = "Probar"
    const val SAVE = "Guardar"
    const val CANCEL = "Cancelar"
    const val CLOSE = "Cerrar"
    const val EDIT = "Editar"
    const val DELETE = "Eliminar"
    const val CLEARTEXT = "Esta dirección no va cifrada (http). Funciona, pero quien controle tu red podría ver qué miras."
    const val EMPTY_TITLE = "Aún no tienes canales propios"
    const val EMPTY_BODY = "Toca «+» para agregar un canal .m3u8 o una lista M3U. Si tienes un celular o una TV vinculados, se copiarán solos."
    const val TOO_MANY = "Llegaste al máximo de ${OwnLive.MAX_SOURCES} canales y listas. Elimina alguno para agregar otro."
    const val SAVE_FAILED = "No se pudo guardar. Intenta de nuevo."

    fun confirmDelete(name: String) = "¿Eliminar «$name»? También se quitará del otro aparato vinculado."
    fun kindLabel(playlist: Boolean) = if (playlist) "Lista" else "Canal"

    /** What the manager shows of an address: its server, so a long token never has to fit on one line. */
    fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
