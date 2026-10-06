package app.kino.tv.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kino.tv.ui.FULL_APP_URL
import app.kino.tv.ui.TELEGRAM_URL
import app.kino.tv.ui.brand.KinoWordmark
import app.kino.tv.ui.openLink
import app.kino.tv.ui.theme.KinoRed
import app.kino.tv.ui.theme.KinoTextSecondary
import app.kino.tv.ui.tv.TvActionOption
import app.kino.tv.ui.tv.landingFocus
import app.kino.tv.ui.tv.rememberLandingFocus

internal const val ABOUT_FULL_APP =
    "Kino completo (plugins, En vivo, cast, sincronización…) se descarga en $FULL_APP_URL"

internal const val ABOUT_CONTENT =
    "Las películas de esta app son de dominio público en EE. UU. según el Internet Archive y se reproducen directo desde archive.org. " +
        "Kino es un reproductor: no aloja ni distribuye contenido."

internal const val ABOUT_DOWNLOAD = "Códigos de Downloader: 6793041 / 7152029"

/** "Acerca de" on the phone: what this app is and where the full one is. */
@Composable
fun AboutScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = contentPadding.calculateTopPadding())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Acerca de", style = MaterialTheme.typography.headlineMedium)
            KinoWordmark(height = 40.dp, modifier = Modifier.padding(vertical = 8.dp))
            Text(ABOUT_FULL_APP, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(ABOUT_CONTENT, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
            Text(ABOUT_DOWNLOAD, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
            Button(
                onClick = { openLink(context, FULL_APP_URL) },
                colors = ButtonDefaults.buttonColors(containerColor = KinoRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Descargar Kino completo") }
            OutlinedButton(onClick = { openLink(context, TELEGRAM_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Telegram: t.me/Kinoapptv", color = Color.White)
            }
            Text(
                "Kino 0.9.45 · Código bajo licencia Apache-2.0. El nombre y el logo de Kino no están licenciados.",
                style = MaterialTheme.typography.bodySmall,
                color = KinoTextSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            )
        }
    }
}

/** "Acerca de" on the TV: the same text, read from the couch; the links are written out to type elsewhere. */
@Composable
fun TvAboutScreen() {
    val context = LocalContext.current
    val landing = rememberLandingFocus()
    Row(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KinoWordmark(height = 56.dp)
            androidx.tv.material3.Text(ABOUT_FULL_APP, style = androidx.tv.material3.MaterialTheme.typography.titleMedium, color = Color.White)
            androidx.tv.material3.Text(ABOUT_CONTENT, style = androidx.tv.material3.MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
            androidx.tv.material3.Text(ABOUT_DOWNLOAD, style = androidx.tv.material3.MaterialTheme.typography.titleSmall, color = Color.White)
            androidx.tv.material3.Text("Telegram: t.me/Kinoapptv", style = androidx.tv.material3.MaterialTheme.typography.titleSmall, color = Color.White)
            TvActionOption("Abrir archive.org/details/kino-app", modifier = Modifier.landingFocus(landing)) { openLink(context, FULL_APP_URL) }
        }
    }
}
