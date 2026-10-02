package com.arkiv.player.ui.live

import com.arkiv.player.data.live.OwnLive

/** Spanish copy shared by the phone and TV dialogs. Plain Kotlin so it stays unit-testable. */
object OwnSourcesCopy {
    const val ADD_MENU = "Agregar canal o lista"
    const val ADD_CHANNEL = "Agregar un canal"
    const val ADD_PLAYLIST = "Agregar una lista (M3U, W3U, XSPF o texto)"
    const val ADD_XTREAM = "Agregar un servidor Xtream"
    const val ADD_IPTV_ORG = "Listas de iptv-org"
    const val MY_SOURCES = "Mis canales y listas"
    const val TITLE_CHANNEL = "Agregar un canal"
    const val TITLE_PLAYLIST = "Agregar una lista"
    const val TITLE_XTREAM = "Agregar un servidor Xtream"
    /** The kind chip of the add dialog: the format is told by the content, so one choice covers M3U and W3U. */
    const val KIND_PLAYLIST = "Lista"
    const val KIND_CHANNEL = "Canal"
    const val KIND_XTREAM = "Servidor Xtream"
    const val PLAYLIST_INFO = "M3U, W3U (Wiseplay), XSPF o un texto con una dirección por línea: se reconoce solo por su contenido."
    const val XTREAM_SERVER = "Servidor"
    const val XTREAM_SERVER_HINT = "Ej.: http://miservidor.com:8080"
    const val XTREAM_USER = "Usuario"
    const val XTREAM_PASS = "Contraseña"
    const val XTREAM_SHOW = "Mostrar contraseña"
    const val XTREAM_HIDE = "Ocultar contraseña"
    const val XTREAM_INFO = "Los datos que te dio tu proveedor. Solo se cargan los canales en vivo. Puedes pegar la dirección completa (get.php o player_api.php) en «Servidor» y se separa sola."
    const val IPTV_ORG_TITLE = "Listas de iptv-org"
    const val IPTV_ORG_INFO = "Canales gratuitos y públicos que mantiene la comunidad de iptv-org. Elige un país, un idioma o una categoría y se agrega como una lista más."
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
    /** The manager's label of a stored source: a Xtream server is told apart by its address. */
    fun kindLabel(kind: String, url: String) = if (kind == "PLAYLIST" && com.arkiv.player.data.live.XtreamUrl.isApi(url)) "Xtream" else kindLabel(kind == "PLAYLIST")
    fun fileLabel(name: String) = "Archivo «$name»"

    /** What the manager shows of an address: its server, so a long token never has to fit on one line. */
    fun hostOf(url: String): String =
        if (com.arkiv.player.data.live.OwnPastedList.isPasted(url)) PASTED_LABEL
        else com.arkiv.player.data.live.XtreamUrl.hostOf(url) ?: runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
