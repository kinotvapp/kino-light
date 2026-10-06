package app.kino.demo.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf

/** A plugin card of the demo's Plugins screens. Names and texts are illustrative; nothing is installed for real. */
data class DemoSource(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val color: Long,
    val hosts: String,
    val version: String = "1.0.0",
    val community: Boolean = false,
    val kind: PluginKind = PluginKind.KINO,
    val categories: Set<PluginCategory> = emptySet(),
    /** A free, legal live-TV addon: its card carries "Gratis y legal". */
    val freeLive: Boolean = false,
)

/** The three plugin formats Kino installs. A Kino-format card wears no badge: "Kino" there would read as "made by Kino". */
enum class PluginKind(val label: String, val color: Long) {
    KINO("Kino", 0xFFE50914),
    NUVIO("Nuvio", 0xFF1E7FA8),
    STREMIO("Stremio", 0xFF7B4FC9),
    ;

    val badge: String? get() = if (this == KINO) null else label
}

/** The Plugins screen's category chips, in their fixed order ("Todos" is no category). A plugin can be in several. */
enum class PluginCategory(val label: String) {
    MOVIES("Películas"),
    SERIES("Series"),
    ANIME("Anime"),
    LIVE("En vivo"),
    RADIO("Radio"),
    SUBTITLES("Subtítulos"),
    UTILITIES("Utilidades"),
}

/** The chips to offer over [plugins]: "Todos" (null) first, then each category one of them has. */
fun categoryChips(plugins: List<DemoSource>): List<PluginCategory?> {
    val present = plugins.flatMapTo(HashSet()) { it.categories }
    return listOf<PluginCategory?>(null) + PluginCategory.entries.filter { it in present }
}

/** [plugins] under [category] (null: all) whose name or description contains [query]. */
fun filterPlugins(plugins: List<DemoSource>, query: String, category: PluginCategory?): List<DemoSource> =
    plugins.filter { p ->
        (category == null || category in p.categories) &&
            (query.isBlank() || p.name.contains(query.trim(), ignoreCase = true) || p.description.contains(query.trim(), ignoreCase = true))
    }

/** The demo's plugin catalog: the two public example plugins plus fictional ones. */
object DemoSources {
    val recommended = listOf(
        DemoSource(
            id = "internet-archive",
            name = "Internet Archive",
            description = "Películas y series de dominio público del Internet Archive.",
            tags = listOf("Películas", "Dominio público"),
            color = 0xFF2B6CB0,
            hosts = "archive.org",
            version = "1.2.0",
            categories = setOf(PluginCategory.MOVIES, PluginCategory.SERIES),
        ),
        DemoSource(
            id = "own-server",
            name = "Tu servidor",
            description = "Tus propios videos, servidos desde un equipo de tu casa.",
            tags = listOf("Personal"),
            color = 0xFF2F855A,
            hosts = "tu red local",
            categories = setOf(PluginCategory.MOVIES, PluginCategory.SERIES),
        ),
        DemoSource(
            id = "example",
            name = "Plugin de ejemplo",
            description = "Un plugin de muestra para ver cómo se ve una fuente en Kino.",
            tags = listOf("Ejemplo"),
            color = 0xFF9B2C2C,
            hosts = "example.org",
            version = "0.3.1",
            categories = setOf(PluginCategory.MOVIES),
        ),
        DemoSource(
            id = "open-channels",
            name = "Canales Abiertos",
            description = "Canales de TV gratuitos que sus dueños transmiten abiertos por internet.",
            tags = listOf("En vivo"),
            color = 0xFF0F766E,
            hosts = "canales-abiertos.example",
            kind = PluginKind.STREMIO,
            categories = setOf(PluginCategory.LIVE),
            freeLive = true,
        ),
        DemoSource(
            id = "world-radio",
            name = "Radio del Mundo",
            description = "Emisoras de radio de muchos países, en vivo.",
            tags = listOf("Radio"),
            color = 0xFFB7791F,
            hosts = "radio-del-mundo.example",
            kind = PluginKind.STREMIO,
            categories = setOf(PluginCategory.RADIO),
        ),
        DemoSource(
            id = "open-subtitles",
            name = "Subtítulos Abiertos",
            description = "Subtítulos en español y otros idiomas, hechos por voluntarios.",
            tags = listOf("Subtítulos"),
            color = 0xFF4A5568,
            hosts = "subtitulos-abiertos.example",
            version = "2.1.0",
            kind = PluginKind.STREMIO,
            categories = setOf(PluginCategory.SUBTITLES),
        ),
        DemoSource(
            id = "classic-anime",
            name = "Anime Clásico",
            description = "Series animadas antiguas cuyos derechos ya vencieron.",
            tags = listOf("Anime"),
            color = 0xFFC53030,
            hosts = "anime-clasico.example",
            categories = setOf(PluginCategory.ANIME, PluginCategory.SERIES),
        ),
        DemoSource(
            id = "old-series",
            name = "Series de Antaño",
            description = "Episodios de series de televisión de los años cincuenta.",
            tags = listOf("Series"),
            color = 0xFF2C5282,
            hosts = "series-antano.example",
            version = "1.3.0",
            kind = PluginKind.NUVIO,
            categories = setOf(PluginCategory.SERIES),
        ),
        DemoSource(
            id = "intro-marks",
            name = "Marcas de intro",
            description = "Avisa dónde empiezan y terminan las intros para saltarlas.",
            tags = listOf("Utilidades"),
            color = 0xFF553C9A,
            hosts = "marcas-intro.example",
            categories = setOf(PluginCategory.UTILITIES),
        ),
    )

    val community = listOf(
        DemoSource(
            id = "community-example",
            name = "Plugin de ejemplo (comunidad)",
            description = "Así se ve un plugin publicado por la comunidad. Kino no revisa estos plugins.",
            tags = listOf("Comunidad"),
            color = 0xFF6B46C1,
            hosts = "example.net",
            community = true,
            categories = setOf(PluginCategory.MOVIES),
        ),
        DemoSource(
            id = "free-docs",
            name = "Documentales Libres",
            description = "Documentales con licencia libre de varios archivos públicos.",
            tags = listOf("Películas"),
            color = 0xFF276749,
            hosts = "documentales.example",
            community = true,
            kind = PluginKind.NUVIO,
            categories = setOf(PluginCategory.MOVIES, PluginCategory.SERIES),
        ),
        DemoSource(
            id = "animated-shorts",
            name = "Cortos Animados",
            description = "Cortometrajes animados de dominio público.",
            tags = listOf("Anime"),
            color = 0xFFD53F8C,
            hosts = "cortos.example",
            community = true,
            kind = PluginKind.STREMIO,
            categories = setOf(PluginCategory.ANIME),
        ),
        DemoSource(
            id = "community-radio",
            name = "Radios comunitarias",
            description = "Emisoras comunitarias y universitarias en vivo.",
            tags = listOf("Radio"),
            color = 0xFF975A16,
            hosts = "radios-comunitarias.example",
            community = true,
            categories = setOf(PluginCategory.RADIO),
        ),
    )

    val all = recommended + community
}

/**
 * The demo's installed example plugins and their on/off switch. Only the switch changes, in memory:
 * installing and uninstalling belong to the full app.
 */
object DemoSession {
    val installed = listOf("internet-archive", "example", "open-subtitles", "old-series")
    val enabled = mutableStateMapOf("internet-archive" to true, "example" to true, "open-subtitles" to true, "old-series" to true)

    fun isInstalled(id: String) = id in installed

    /** The installed, active plugins that search films, series or anime: the sources "Buscar por fuente" offers. */
    fun searchScopes(): List<DemoSource> {
        val searchable = setOf(PluginCategory.MOVIES, PluginCategory.SERIES, PluginCategory.ANIME)
        return DemoSources.all.filter { isInstalled(it.id) && enabled[it.id] == true && it.categories.any { c -> c in searchable } }
    }

    /** "Modo debug" per installed plugin, in memory. */
    val debug = mutableStateMapOf("example" to true)
}

/** The few lines a plugin's "Registro" shows while its "Modo debug" is on. Illustrative only. */
fun demoPluginLog(plugin: DemoSource): List<String> = listOf(
    "18:42:15  buscar \"caligari\" · 3 resultados · 412 ms",
    "18:42:09  abrir \"El gabinete del doctor Caligari\" · 2 copias",
    "18:41:58  error: ${plugin.hosts} respondió 503 (servicio no disponible) · se reintentó",
    "18:41:57  inicio · ${plugin.name} ${plugin.version}",
)

/** A plugin update waiting for the person's approval: its version now and the one waiting. */
data class WaitingUpdate(val id: String, val name: String, val currentVersion: String, val pendingVersion: String)

/** A plugin update applied by itself, and the day ("dd/MM") it was. */
data class AppliedUpdate(val name: String, val fromVersion: String, val toVersion: String, val day: String)

/** What the plugin-updates bell shows. Illustrative: nothing updates in this app. */
object DemoPluginUpdates {
    val waiting = listOf(WaitingUpdate("old-series", "Series de Antaño", "1.3.0", "1.4.0"))
    val updated = listOf(
        AppliedUpdate("Internet Archive", "1.1.0", "1.2.0", "04/10"),
        AppliedUpdate("Subtítulos Abiertos", "2.0.3", "2.1.0", "02/10"),
    )

    /** The red number: updates waiting for approval. */
    val count: Int get() = waiting.size
}

/** An addon collection the person added ("Tus colecciones de Stremio"): its addons, none installed by itself. */
data class DemoCollection(val name: String, val addons: List<DemoSource>)

/** The person's Stremio collections and Nuvio repositories, kept while the app runs. */
object DemoPluginLists {
    val collections = listOf(
        DemoCollection(
            name = "Addons abiertos",
            addons = listOf(
                DemoSource(
                    id = "silent-classics",
                    name = "Clásicos Mudos",
                    description = "Películas mudas restauradas, con su música.",
                    tags = listOf("Películas"),
                    color = 0xFF744210,
                    hosts = "clasicos-mudos.example",
                    kind = PluginKind.STREMIO,
                    categories = setOf(PluginCategory.MOVIES),
                ),
                DemoSource(
                    id = "old-newsreels",
                    name = "Noticieros de Época",
                    description = "Noticieros de cine de los años treinta y cuarenta.",
                    tags = listOf("Series"),
                    color = 0xFF2A4365,
                    hosts = "noticieros.example",
                    kind = PluginKind.STREMIO,
                    categories = setOf(PluginCategory.SERIES),
                ),
                DemoSource(
                    id = "live-music",
                    name = "Música en Vivo",
                    description = "Conciertos y emisoras musicales abiertas.",
                    tags = listOf("Radio"),
                    color = 0xFF97266D,
                    hosts = "musica-en-vivo.example",
                    kind = PluginKind.STREMIO,
                    categories = setOf(PluginCategory.RADIO),
                ),
            ),
        ),
    )
    val nuvioRepos = mutableStateListOf("ejemplo/nuvio-scrapers")
}

/**
 * A fictional live channel for En vivo: it "airs" one of the catalog films ([filmIndex] into the catalog).
 * [provider] is the [DemoLiveProvider] it comes from; a [radio] station with no logo shows a radio glyph.
 */
data class DemoChannel(
    val number: Int,
    val name: String,
    val category: String,
    val now: String,
    val progress: Float,
    val filmIndex: Int,
    val provider: String = DemoLiveProviders.TV,
    val radio: Boolean = false,
)

/** A source of live channels: a plugin with channels, or the person's own ("Mis canales"). */
data class DemoLiveProvider(val id: String, val name: String, val color: Long)

object DemoLiveProviders {
    const val TV = "tv"
    const val RADIO = "radio"
    const val OWN = "own"

    val all = listOf(
        DemoLiveProvider(TV, "Canales Abiertos", 0xFF0F766E),
        DemoLiveProvider(RADIO, "Radio del Mundo", 0xFFB7791F),
        DemoLiveProvider(OWN, "Mis canales", 0xFF6B46C1),
    )

    fun of(id: String): DemoLiveProvider = all.firstOrNull { it.id == id } ?: all.first()
}

object DemoChannels {
    val categories = listOf("Cine", "Comedia", "Animación", "Misterio")

    /** The TV rail's group of a category; a category with none shows without a header. */
    val groups = mapOf("Cine" to "Películas", "Misterio" to "Películas", "Comedia" to "Entretenimiento", "Animación" to "Entretenimiento")

    val channels = listOf(
        DemoChannel(1, "Cine Clásico", "Cine", "Ayuno de amor", 0.35f, 0),
        DemoChannel(2, "Cine Mudo 24h", "Cine", "El gabinete del doctor Caligari", 0.6f, 5),
        DemoChannel(3, "Matiné", "Cine", "El mundo perdido", 0.2f, 7),
        DemoChannel(4, "Comedia Clásica", "Comedia", "The Flying Deuces", 0.75f, 9),
        DemoChannel(5, "Risas Mudas", "Comedia", "Charlot prestamista", 0.5f, 10),
        DemoChannel(6, "Animación Retro", "Animación", "Los viajes de Gulliver", 0.4f, 2),
        DemoChannel(7, "Cuentos Animados", "Animación", "Blancanieves (1933)", 0.1f, 12),
        DemoChannel(8, "Noche de Misterio", "Misterio", "El carnaval de las almas", 0.55f, 1),
        DemoChannel(9, "Terror de Época", "Misterio", "El fantasma de la ópera", 0.3f, 3),
        DemoChannel(10, "Aventura TV", "Cine", "El libro de la selva", 0.65f, 13),
        DemoChannel(11, "Radio Clásica", "Radio", "Sinfonías de la mañana", 0.45f, 0, DemoLiveProviders.RADIO, radio = true),
        DemoChannel(12, "Jazz de Medianoche", "Radio", "Clásicos del swing", 0.7f, 0, DemoLiveProviders.RADIO, radio = true),
        DemoChannel(13, "Noticias al Día", "Radio", "Resumen informativo", 0.2f, 0, DemoLiveProviders.RADIO, radio = true),
        DemoChannel(14, "Tropical Estéreo", "Radio", "Cumbias de siempre", 0.85f, 0, DemoLiveProviders.RADIO, radio = true),
        DemoChannel(15, "Cine en casa", "Mi lista", "Juan Nadie", 0.5f, 4, DemoLiveProviders.OWN),
        DemoChannel(16, "Clásicos de la familia", "Mi lista", "Los viajes de Gulliver", 0.25f, 2, DemoLiveProviders.OWN),
    )

    /** [provider]'s categories, in the order its channels give them. */
    fun categoriesOf(provider: String): List<String> = channels.filter { it.provider == provider }.map { it.category }.distinct()

    /** The channels whose name contains [query], or whose number is it, across every provider. */
    fun search(query: String): List<DemoChannel> =
        channels.filter { it.name.contains(query.trim(), ignoreCase = true) || it.number.toString() == query.trim() }
}

/** Favorites and recents of the demo's channels, kept while the app runs. */
object DemoLive {
    val favorites = mutableStateListOf(2, 6)
    val recent = mutableStateListOf(1, 8)

    fun open(channel: DemoChannel) {
        recent.remove(channel.number)
        recent.add(0, channel.number)
    }
}
