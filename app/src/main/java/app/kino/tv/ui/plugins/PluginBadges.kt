package app.kino.tv.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kino.tv.data.PluginKind

/** The tab and section title of the plugins found by the community. */
internal const val COMMUNITY_TITLE = "De la comunidad"

/** The note under "De la comunidad", phone and TV, always shown. */
internal const val COMMUNITY_NOTE = "Plugins de la comunidad — Kino no los revisa ni responde por su contenido."

/** What a plugins list says when the search and the chip leave none of its cards. */
internal const val NO_MATCH_LINE = "No hay plugins que coincidan."

/** The note a free, legal live-TV addon's card carries. */
internal const val FREE_LIVE_NOTE = "Gratis y legal"

/** The pill of a free live addon's card: green, so it never reads as the red "De la comunidad". */
internal const val FREE_LIVE_COLOR = 0xFF2F855AL

/** The title of the person's addon collections, phone and TV. */
internal const val STREMIO_COLLECTIONS_TITLE = "Tus colecciones de Stremio"

/** The title of the person's Nuvio repositories (phone). */
internal const val NUVIO_REPOS_TITLE = "Tus repositorios de Nuvio"

/** What a collection says under its name: how many of its addons work here. */
internal fun collectionLine(count: Int): String = when (count) {
    0 -> "Esta lista no tiene addons que funcionen en Kino."
    1 -> "1 addon que funciona en Kino. Tú decides cuál instalar."
    else -> "$count addons que funcionan en Kino. Tú decides cuáles instalar."
}

/** The label of the Instalados tab. */
internal fun installedTabLabel(count: Int): String = if (count > 0) "Instalados ($count)" else "Instalados"

/**
 * The small Nuvio / Stremio badge after a plugin's name, phone and TV alike, in the format's colour.
 * A Kino-format plugin draws none.
 */
@Composable
internal fun PluginKindBadge(kind: PluginKind, modifier: Modifier = Modifier) {
    val label = kind.badge ?: return
    Box(modifier.background(Color(kind.color), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false)
    }
}
