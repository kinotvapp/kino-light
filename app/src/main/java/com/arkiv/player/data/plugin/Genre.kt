package com.arkiv.player.data.plugin

import java.text.Normalizer

/**
 * The closed vocabulary of genres a plugin may put on its Home rows, live categories and playlists (`genre`, see
 * docs/plugins). Closed so different plugins' categories can be grouped and filtered together; a value outside it is
 * simply no genre. When nothing is declared, [infer] guesses from a title, so M3U lists, the person's own channels and
 * Xuper's categories are filterable too. Pure.
 */
object Genre {
    val IDS: List<String> = listOf(
        "peliculas", "series", "anime", "infantil", "documentales", "deportes", "noticias", "musica", "entretenimiento", "otros",
    )

    private val LABELS = mapOf(
        "peliculas" to "Películas", "series" to "Series", "anime" to "Anime", "infantil" to "Infantil",
        "documentales" to "Documentales", "deportes" to "Deportes", "noticias" to "Noticias", "musica" to "Música",
        "entretenimiento" to "Entretenimiento", "otros" to "Otros",
    )

    fun label(id: String): String = LABELS[id] ?: id

    /** A declared genre: the id when it is in the vocabulary (case and outer spaces ignored), else null. */
    fun parse(raw: Any?): String? = (raw as? String)?.trim()?.lowercase()?.takeIf { it in IDS }

    /** More specific genres first: "Series de anime" is anime, "Noticias deportivas" is deportes. */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "anime" to listOf("anime", "manga"),
        "infantil" to listOf("infantil", "kids", "nino", "cartoon", "dibujo", "disney", "nick", "junior", "toon"),
        "deportes" to listOf("deport", "sport", "futbol", "soccer", "espn", "nba", "ufc", "boxeo", "tenis"),
        "noticias" to listOf("noticia", "news", "informativ", "cnn"),
        "documentales" to listOf("document", "natgeo", "discovery", "naturaleza"),
        "musica" to listOf("music", "concierto", "mtv"),
        "peliculas" to listOf("pelicula", "movie", "cine", "film"),
        "series" to listOf("serie"),
        "entretenimiento" to listOf("entretenimiento", "entertainment", "variedad", "reality", "comedia"),
    )

    /** A best guess from a category or group [title]; null when nothing in it says a genre. */
    fun infer(title: String): String? {
        val text = plain(title)
        if (text.isEmpty()) return null
        return KEYWORDS.firstOrNull { (_, words) -> words.any { it in text } }?.first
    }

    /** What a category is: what its plugin declared, else what its title suggests, else nothing. */
    fun of(declared: String?, title: String): String? = parse(declared) ?: infer(title)

    private fun plain(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}
