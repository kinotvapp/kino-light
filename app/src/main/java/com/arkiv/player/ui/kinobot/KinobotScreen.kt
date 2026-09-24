package com.arkiv.player.ui.kinobot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Kinobot — the movie/series/anime chat. Streams the reply into a live bubble and offers the
 * suggested titles as chips that open the internal search ([onSearch]). Phone only (reached from
 * `ArkivRoot`; TV has no entry).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KinobotScreen(onBack: () -> Unit, onSearch: (String) -> Unit) {
    val graph = rememberGraph()
    val vm: KinobotViewModel = viewModel(
        factory = viewModelFactory {
            initializer { KinobotViewModel(graph.database.kinobotDao(), graph.kinobotClient) }
        },
    )
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val busy = thinking || streaming != null

    fun submit(text: String) {
        vm.send(text)
        input = ""
    }

    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(messages.size, streaming, thinking) {
        val count = messages.size + (if (streaming != null || thinking) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().background(ArkivBlack).imePadding()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White)
            }
            BotAvatar(size = 36.dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("Kinobot", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("el asistente", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = { vm.clear() }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Borrar conversación", tint = ArkivTextSecondary)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && !busy) {
                item(key = "welcome") { Welcome(onExample = ::submit) }
            }
            items(messages.size, key = { messages[it].id }) { i ->
                val m = messages[i]
                MessageBubble(text = m.text, fromUser = m.fromUser)
                if (!m.fromUser && m.suggestions.isNotEmpty()) {
                    SuggestionChips(m.suggestions, onSearch)
                }
            }
            if (streaming != null) {
                item(key = "streaming") { MessageBubble(text = streaming + " ▌", fromUser = false) }
            } else if (thinking) {
                item(key = "thinking") { ThinkingBubble() }
            }
        }

        // Input
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Pregúntame de pelis, series o anime…") },
                enabled = !busy,
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy) submit(input) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ArkivSurfaceHigh,
                    unfocusedContainerColor = ArkivSurfaceHigh,
                    disabledContainerColor = ArkivSurfaceHigh,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = ArkivRed,
                ),
            )
            Spacer(Modifier.size(8.dp))
            IconButton(
                onClick = { if (!busy && input.isNotBlank()) submit(input) },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.size(48.dp).background(if (busy || input.isBlank()) ArkivSurfaceHigh else ArkivRed, CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = Color.White)
            }
        }
    }
}

@Composable
private fun BotAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).background(ArkivRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("🎬", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MessageBubble(text: String, fromUser: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (fromUser) ArkivRed else ArkivSurfaceHigh,
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (fromUser) 16.dp else 4.dp,
                        bottomEnd = if (fromUser) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .background(ArkivSurfaceHigh, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = ArkivRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
                "Kinobot está pensando…",
                color = ArkivTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(titles: List<String>, onSearch: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        titles.forEach { title ->
            Row(
                modifier = Modifier
                    .background(Color(0xFF2A2A31), RoundedCornerShape(20.dp))
                    .clickable { onSearch(title) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = ArkivRed, modifier = Modifier.size(16.dp))
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Welcome(onExample: (String) -> Unit) {
    val examples = listOf(
        "¿Qué peli de terror me recomiendas?",
        "Un anime parecido a Attack on Titan",
        "Dato curioso de El Padrino",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        BotAvatar(size = 64.dp)
        Text(
            "Hola, soy Kinobot",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Te ayudo con pelis, series y anime: recomendaciones, datos curiosos y más.",
            style = MaterialTheme.typography.bodyMedium,
            color = ArkivTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
        )
        Spacer(Modifier.size(20.dp))
        examples.forEach { ex ->
            Text(
                ex,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(vertical = 5.dp)
                    .background(ArkivSurfaceHigh, RoundedCornerShape(20.dp))
                    .clickable { onExample(ex) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
