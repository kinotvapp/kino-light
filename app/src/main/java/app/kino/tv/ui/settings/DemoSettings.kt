package app.kino.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The settings screens' values. They change in memory only, so the controls respond; nothing is saved. */
object KinoSettings {
    val audioLanguages = listOf("Español latino", "Castellano", "Español (genérico)", "Dual / multi-audio", "Inglés", "Japonés")
    val subtitleLanguages = listOf("Español latino", "Castellano", "Español (genérico)", "Inglés", "Japonés")

    val audioOrder = mutableStateListOf("Español latino", "Castellano", "Inglés")
    val understood = mutableStateListOf("Español latino", "Castellano", "Español (genérico)")
    val subtitleOrder = mutableStateListOf("Español latino", "Castellano")
    var subtitlesAuto by mutableStateOf(true)

    val sizes = (60..200 step 10).toList()
    val positions = listOf(2, 5, 8, 12, 16, 20, 25, 30)
    val opacities = (0..100 step 10).toList()
    val colors = listOf(0xFFFFFFFFL to "Blanco", 0xFFFFEB3BL to "Amarillo", 0xFF00E5FFL to "Cian", 0xFF00E676L to "Verde")
    val edges = listOf("Contorno", "Sombra", "Ninguno")

    var size by mutableIntStateOf(100)
    var textColor by mutableLongStateOf(0xFFFFFFFFL)
    var bold by mutableStateOf(false)
    var opacity by mutableIntStateOf(0)
    var edge by mutableStateOf("Contorno")
    var position by mutableIntStateOf(8)

    var funFacts by mutableStateOf(true)
    var animeSkip by mutableStateOf(true)
    var autoSkip by mutableStateOf(false)
    val dnsModes = listOf("Cloudflare (predeterminado)", "Google", "Ninguno")
    var dns by mutableStateOf(dnsModes.first())
    var effects by mutableStateOf(true)
    var navSounds by mutableStateOf(true)

    /** Toggles [item] in [list], never leaving it empty. */
    fun toggle(list: MutableList<String>, item: String) {
        if (item in list) {
            if (list.size > 1) list.remove(item)
        } else {
            list.add(item)
        }
    }

    fun moveUp(list: MutableList<String>, item: String) {
        val i = list.indexOf(item)
        if (i > 0) { list.removeAt(i); list.add(i - 1, item) }
    }

    fun moveDown(list: MutableList<String>, item: String) {
        val i = list.indexOf(item)
        if (i in 0 until list.lastIndex) { list.removeAt(i); list.add(i + 1, item) }
    }

    /** The value one step from [current] in [values]; [wrap] goes round at the ends. */
    fun step(values: List<Int>, current: Int, delta: Int, wrap: Boolean): Int {
        val i = values.indexOf(current).coerceAtLeast(0)
        val next = i + delta
        return when {
            next in values.indices -> values[next]
            !wrap -> values[i]
            delta > 0 -> values.first()
            else -> values.last()
        }
    }

    fun positionLabel(percent: Int) = if (percent == 8) "$percent % (normal)" else "$percent %"
}

/** A 16:9 stand-in for the picture with a sample line drawn with the current subtitle style. */
@Composable
internal fun SubtitlePreview(isTv: Boolean, modifier: Modifier = Modifier) {
    val s = KinoSettings
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF8FA9C4), Color(0xFF55708C), Color(0xFF2A3428)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val base = if (isTv) 14f else 16f
        Text(
            "Ejemplo de subtítulo",
            style = TextStyle(
                color = Color(s.textColor),
                fontSize = (base * s.size / 100f).sp,
                fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
                shadow = when (s.edge) {
                    "Contorno" -> Shadow(Color.Black, Offset.Zero, 6f)
                    "Sombra" -> Shadow(Color.Black, Offset(3f, 3f), 4f)
                    else -> null
                },
            ),
            modifier = Modifier
                .padding(bottom = (s.position * 1.6f).dp)
                .background(Color.Black.copy(alpha = s.opacity / 100f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
