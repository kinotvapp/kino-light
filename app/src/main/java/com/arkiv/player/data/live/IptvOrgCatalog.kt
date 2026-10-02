package com.arkiv.player.data.live

/** One ready-made iptv-org list: what the person reads ([title]) and the playlist address. */
data class IptvOrgList(val kind: IptvOrgKind, val code: String, val title: String) {
    val url: String get() = "${IptvOrgCatalog.BASE}/${kind.folder}/$code.m3u"
    /** The name the saved list gets: "iptv-org · Colombia". */
    val listName: String get() = "iptv-org · $title"
}

enum class IptvOrgKind(val folder: String, val label: String) {
    COUNTRY("countries", "País"),
    LANGUAGE("languages", "Idioma"),
    CATEGORY("categories", "Categoría"),
}

/**
 * The official iptv-org playlists (github.com/iptv-org/iptv: free-to-air channels the community keeps up to date), by
 * country, language and category. A small curated set, baked in: nothing touches the network until the person
 * picks one, and the pick is saved as an ordinary list (an M3U on `iptv-org.github.io`, a public host), so it
 * refreshes, shows its guide when the list names one, and syncs like any other. Adult categories are left out.
 */
object IptvOrgCatalog {
    const val BASE = "https://iptv-org.github.io/iptv"

    val countries: List<IptvOrgList> = listOf(
        "co" to "Colombia", "mx" to "México", "ar" to "Argentina", "cl" to "Chile", "pe" to "Perú", "ve" to "Venezuela",
        "ec" to "Ecuador", "bo" to "Bolivia", "uy" to "Uruguay", "py" to "Paraguay", "br" to "Brasil", "cr" to "Costa Rica",
        "pa" to "Panamá", "gt" to "Guatemala", "sv" to "El Salvador", "hn" to "Honduras", "ni" to "Nicaragua",
        "do" to "República Dominicana", "cu" to "Cuba", "pr" to "Puerto Rico", "es" to "España", "us" to "Estados Unidos",
        "ca" to "Canadá", "gb" to "Reino Unido", "fr" to "Francia", "de" to "Alemania", "it" to "Italia", "pt" to "Portugal",
    ).map { (code, title) -> IptvOrgList(IptvOrgKind.COUNTRY, code, title) }

    val languages: List<IptvOrgList> = listOf(
        "spa" to "Español", "eng" to "Inglés", "por" to "Portugués", "fra" to "Francés", "ita" to "Italiano", "deu" to "Alemán",
        "ara" to "Árabe", "rus" to "Ruso", "zho" to "Chino", "hin" to "Hindi", "tur" to "Turco",
    ).map { (code, title) -> IptvOrgList(IptvOrgKind.LANGUAGE, code, title) }

    val categories: List<IptvOrgList> = listOf(
        "news" to "Noticias", "sports" to "Deportes", "movies" to "Películas", "series" to "Series", "kids" to "Niños",
        "music" to "Música", "entertainment" to "Entretenimiento", "documentary" to "Documentales", "general" to "Generales",
        "education" to "Educación", "science" to "Ciencia", "culture" to "Cultura", "family" to "Familia", "comedy" to "Comedia",
        "cooking" to "Cocina", "travel" to "Viajes", "religious" to "Religión", "weather" to "Clima", "lifestyle" to "Estilo de vida",
        "outdoor" to "Aire libre", "animation" to "Animación", "classic" to "Clásicos", "relax" to "Relax", "business" to "Negocios",
    ).map { (code, title) -> IptvOrgList(IptvOrgKind.CATEGORY, code, title) }

    fun of(kind: IptvOrgKind): List<IptvOrgList> = when (kind) {
        IptvOrgKind.COUNTRY -> countries
        IptvOrgKind.LANGUAGE -> languages
        IptvOrgKind.CATEGORY -> categories
    }

    /** The form a pick is saved with: a list, no headers, refreshed daily. */
    fun form(list: IptvOrgList) = OwnSourceForm(OwnKind.PLAYLIST, list.listName, list.url, refreshHours = 24)
}
