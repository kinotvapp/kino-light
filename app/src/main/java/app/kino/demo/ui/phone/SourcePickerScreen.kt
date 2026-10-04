package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kino.demo.data.DemoSources
import app.kino.demo.data.DemoSession
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoTextSecondary

internal const val SOURCE_PICKER_TITLE = "Elige tus fuentes"
internal const val SOURCE_PICKER_LINE = "Instala las fuentes que quieras usar. Puedes cambiarlas cuando quieras en Plugins."

/** "Elige tus fuentes", the first-run screen: your plugins, the recommended ones, the community's, and "Continuar". */
@Composable
fun SourcePickerScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(KinoBlack).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SOURCE_PICKER_TITLE, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(SOURCE_PICKER_LINE, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "installed-header", span = { GridItemSpan(maxLineSpan) }) {
                Text("Tus plugins", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            items(DemoSources.all.filter { DemoSession.isInstalled(it.id) }, key = { "installed-${it.id}" }) { p ->
                CatalogCard(p, installed = true, onAction = {})
            }
            item(key = "recommended-header", span = { GridItemSpan(maxLineSpan) }) {
                Text("Recomendados", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            items(DemoSources.recommended.filterNot { DemoSession.isInstalled(it.id) }, key = { "card-${it.id}" }) { p ->
                CatalogCard(p, installed = false, onAction = { fullAppOnly(context) })
            }
            communityItems(DemoSources.community) { fullAppOnly(context) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onFinish, colors = ButtonDefaults.buttonColors(containerColor = KinoRed, contentColor = Color.White)) {
                Text("Continuar")
            }
        }
    }
}
