package app.kino.demo.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kino.demo.ui.fullAppOnly
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTextSecondary

/** Kinobot, the AI assistant, as a placeholder: its welcome and suggestions; asking says where it works. */
@Composable
fun AssistantScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(KinoBlack).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White) }
            Text("Kinobot", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { text = "" }) { Icon(Icons.Default.Add, contentDescription = "Nueva conversación", tint = Color.White) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Hola, soy Kinobot", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "Te ayudo con pelis, series y anime: recomendaciones, datos curiosos y más.",
                style = MaterialTheme.typography.bodyMedium,
                color = KinoTextSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            listOf("¿Qué peli clásica me recomiendas?", "Un dato curioso del cine mudo").forEach { suggestion ->
                AssistChip(onClick = { fullAppOnly(context) }, label = { Text(suggestion) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Pregúntame de pelis, series o anime…") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { fullAppOnly(context) }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = Color.White)
            }
        }
    }
}
