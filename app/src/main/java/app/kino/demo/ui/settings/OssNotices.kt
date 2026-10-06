package app.kino.demo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary

/** One third-party component the app ships, as Ajustes ▸ App ▸ "Licencias de software libre" lists it. */
data class OssNotice(val name: String, val license: String, val copyright: String, val use: String)

/** The libraries in the APK (build.gradle.kts, THIRD_PARTY_NOTICES.md); test-only ones are not shipped. */
val OSS_NOTICES = listOf(
    OssNotice("Kotlin y kotlinx.coroutines", "Apache-2.0", "Copyright JetBrains s.r.o. y colaboradores de Kotlin", "El lenguaje de la app y su código asíncrono."),
    OssNotice("AndroidX Core, SplashScreen, Activity y Lifecycle", "Apache-2.0", "Copyright The Android Open Source Project", "La base de la app en Android."),
    OssNotice("Jetpack Compose (UI, Foundation, Material 3, Material Icons)", "Apache-2.0", "Copyright The Android Open Source Project", "Las pantallas del celular."),
    OssNotice("Compose para TV (tv-material)", "Apache-2.0", "Copyright The Android Open Source Project", "Las pantallas del TV."),
    OssNotice("AndroidX Media3 (ExoPlayer y UI)", "Apache-2.0", "Copyright The Android Open Source Project", "El reproductor de video."),
    OssNotice("Coil", "Apache-2.0", "Copyright Coil Contributors", "Carga las carátulas."),
)

/** The short Apache-2.0 notice shown under a component; the full text is at the URL it names. */
const val APACHE_2_NOTICE =
    "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in " +
        "compliance with the License. You may obtain a copy of the License at\n\n" +
        "http://www.apache.org/licenses/LICENSE-2.0\n\n" +
        "Unless required by applicable law or agreed to in writing, software distributed under the License is " +
        "distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or " +
        "implied. See the License for the specific language governing permissions and limitations under the License."

/** What a component's page says: its use, copyright and license notice. */
fun ossNoticeText(notice: OssNotice): String = "${notice.use}\n\n${notice.copyright}\n\n$APACHE_2_NOTICE"

const val OSS_INTRO = "Kino es software libre (Apache-2.0). Estas son las bibliotecas de terceros que incluye, cada una con su licencia."

/** "Licencias de software libre" on the phone: the list, and one component's notice in the same dialog. */
@Composable
fun OssNoticesDialog(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<OssNotice?>(null) }
    val notice = open
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KinoSurface,
        title = { Text(notice?.name ?: "Licencias de software libre") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (notice == null) {
                    Text(OSS_INTRO, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    OSS_NOTICES.forEach { n ->
                        Column(Modifier.fillMaxWidth().clickable { open = n }.padding(vertical = 10.dp)) {
                            Text(n.name, style = MaterialTheme.typography.bodyLarge)
                            Text(n.license, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
                        }
                    }
                } else {
                    Text(notice.license, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    Text(ossNoticeText(notice), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = if (notice != null) {
            { TextButton(onClick = { open = null }) { Text("Atrás") } }
        } else {
            null
        },
    )
}
