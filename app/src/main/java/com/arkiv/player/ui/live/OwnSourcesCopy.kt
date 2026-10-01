package com.arkiv.player.ui.live

import com.arkiv.player.data.live.OwnLive

/** Spanish copy shared by the phone and TV dialogs. Plain Kotlin so it stays unit-testable. */
object OwnSourcesCopy {
    const val ADD_MENU = "Agregar canal o lista"
    const val ADD_CHANNEL = "Agregar un canal"
    const val ADD_PLAYLIST = "Agregar una lista (M3U o W3U)"
    const val MY_SOURCES = "Mis canales y listas"
    const val TITLE_CHANNEL = "Agregar un canal"
    const val TITLE_PLAYLIST = "Agregar una lista (M3U o W3U)"
    /** The kind chip of the add dialog: the format is told by the content, so one choice covers M3U and W3U. */
    const val KIND_PLAYLIST = "Lista M3U o W3U"
    const val TITLE_EDIT = "Editar"
    const val NAME = "Nombre"
    const val URL_CHANNEL = "Dirección del canal (.m3u8)"
    const val URL_PLAYLIST = "Dirección de la lista (.m3u o .w3u)"
    const val URL_CHANNEL_HINT = "Ej.: http://miservidor.com/canal.m3u8"
    const val URL_PLAYLIST_HINT = "Ej.: http://miservidor.com/get.php?username=...&password=...&type=m3u_plus"
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
    const val EMPTY_BODY = "Toca «+» para agregar un canal .m3u8 o una lista M3U o W3U (Wiseplay). Si tienes un celular o una TV vinculados, se copiarán solos."
    const val TOO_MANY = "Llegaste al máximo de ${OwnLive.MAX_SOURCES} canales y listas. Elimina alguno para agregar otro."
    const val SAVE_FAILED = "No se pudo guardar. Intenta de nuevo."

    /** Lists with no address (phone only: pasting and picking files is awkward on a TV). */
    const val PASTE = "Pegar lista"
    const val OPEN_FILE = "Abrir archivo"
    const val PASTE_HINT = "¿No tienes la dirección? Copia el texto de la lista y toca «Pegar lista», o abre un archivo .m3u, .m3u8, .w3u o .json (máximo 2 MB)."
    const val PASTED_LABEL = "Lista pegada"
    const val PASTED_SAVED = "Lista pegada o abierta desde un archivo (sin dirección)"
    const val PASTED_REPLACE = "Para cambiar sus canales, vuelve a pegar la lista o abre otro archivo."
    const val USE_URL = "Usar una dirección"
    const val PASTED_TV = "Esta lista se pegó o se abrió como archivo en el celular. Para cambiar sus canales, edítala allá."

    fun confirmDelete(name: String) = "¿Eliminar «$name»? También se quitará del otro aparato vinculado."
    fun kindLabel(playlist: Boolean) = if (playlist) "Lista" else "Canal"
    fun fileLabel(name: String) = "Archivo «$name»"

    /** What the manager shows of an address: its server, so a long token never has to fit on one line. */
    fun hostOf(url: String): String =
        if (com.arkiv.player.data.live.OwnPastedList.isPasted(url)) PASTED_LABEL
        else runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
