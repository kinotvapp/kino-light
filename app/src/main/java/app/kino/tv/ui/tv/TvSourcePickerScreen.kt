package app.kino.tv.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.tv.data.KinoSources
import app.kino.tv.data.KinoSession
import app.kino.tv.ui.fullAppOnly
import app.kino.tv.ui.phone.SOURCE_PICKER_LINE
import app.kino.tv.ui.phone.SOURCE_PICKER_TITLE
import app.kino.tv.ui.theme.KinoBlack
import app.kino.tv.ui.theme.KinoTextSecondary

/** "Elige tus fuentes" on the TV: compact cards in a grid and "Continuar" at the bottom. */
@Composable
fun TvSourcePickerScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val landing = rememberLandingFocus()
    Column(Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(SOURCE_PICKER_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(SOURCE_PICKER_LINE.replace("en Plugins", "en Ajustes ▸ Plugins"), style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Tus plugins", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            items(KinoSources.all.filter { KinoSession.isInstalled(it.id) }, key = { "inst-${it.id}" }) { p ->
                val first = p.id == KinoSources.all.first { KinoSession.isInstalled(it.id) }.id
                TvCatalogCard(p, "Instalado ✓", actionIsQuiet = true, onClick = {}, modifier = if (first) Modifier.landingFocus(landing) else Modifier)
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Recomendados", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            items(KinoSources.recommended.filterNot { KinoSession.isInstalled(it.id) }, key = { "rec-${it.id}" }) { p ->
                TvCatalogCard(p, "Instalar", actionIsQuiet = false, onClick = { fullAppOnly(context) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text("De la comunidad", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            items(KinoSources.community, key = { "com-${it.id}" }) { p ->
                TvCatalogCard(p, "Instalar", actionIsQuiet = false, onClick = { fullAppOnly(context) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TvCompactAction(label = "Continuar", onClick = onFinish)
        }
    }
}
