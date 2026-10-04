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
)

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
        ),
        DemoSource(
            id = "own-server",
            name = "Tu servidor",
            description = "Tus propios videos, servidos desde un equipo de tu casa.",
            tags = listOf("Personal"),
            color = 0xFF2F855A,
            hosts = "tu red local",
        ),
        DemoSource(
            id = "example",
            name = "Plugin de ejemplo",
            description = "Un plugin de muestra para ver cómo se ve una fuente en Kino.",
            tags = listOf("Ejemplo"),
            color = 0xFF9B2C2C,
            hosts = "example.org",
            version = "0.3.1",
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
        ),
    )

    val all = recommended + community
}

/**
 * The demo's installed example plugins and their on/off switch. Only the switch changes, in memory:
 * installing and uninstalling belong to the full app.
 */
object DemoSession {
    val installed = listOf("internet-archive", "example")
    val enabled = mutableStateMapOf("internet-archive" to true, "example" to true)

    fun isInstalled(id: String) = id in installed
}

/** A fictional live channel for En vivo: it "airs" one of the catalog films ([filmIndex] into the catalog). */
data class DemoChannel(val number: Int, val name: String, val category: String, val now: String, val progress: Float, val filmIndex: Int)

object DemoChannels {
    val categories = listOf("Cine", "Comedia", "Animación", "Misterio")
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
    )
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
