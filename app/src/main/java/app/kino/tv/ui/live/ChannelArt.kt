package app.kino.tv.ui.live

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.kino.tv.data.KinoChannel

/** En vivo's texts shared by the phone and the TV. */
internal object LiveCopy {
    const val MY_SOURCES = "Mis canales y listas"
    const val SEARCH_HINT = "Buscar canal por nombre o número…"
    const val NO_FAVORITES = "Aún no tienes favoritos"
    const val NO_RECENTS = "Sin canales recientes"
    const val NO_RECENTS_BODY = "Los canales que abras van a aparecer acá."
    const val NO_RESULTS = "Sin resultados"
    const val NO_RESULTS_BODY = "Prueba con otro nombre o número de canal."
    const val NO_OWN = "Aún no tienes canales propios"

    /** The hint under the TV preview for the focused row. */
    fun watchRow(name: String): String = "OK para ver $name · mantén OK para ★"

    fun now(title: String): String = "Ahora: $title"
}

/**
 * What a channel tile shows when the channel has no logo: its number when it has one, else the initials
 * of its name ("Cine en casa" -> "CE"). A radio station shows [RadioGlyph] instead ([showsRadioArt]).
 */
internal fun channelPlaceholder(number: Int, name: String): String {
    if (number > 0) return number.toString()
    val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val initials = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> ""
    }
    return initials.uppercase().ifEmpty { "TV" }
}

/** Whether [channel]'s tile draws the radio glyph instead of its number: none of these channels has a logo. */
internal fun showsRadioArt(channel: KinoChannel): Boolean = channel.radio

/** A logo-less radio station's tile: a radio glyph in place of the number. */
@Composable
fun RadioGlyph(modifier: Modifier = Modifier, alpha: Float = 0.75f) {
    Icon(Icons.Filled.Radio, contentDescription = null, tint = Color.White.copy(alpha = alpha), modifier = modifier)
}
