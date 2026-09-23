package com.arkiv.player.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.companion.DiscoveredHost
import androidx.compose.ui.graphics.Color
import com.arkiv.player.companion.LinkState
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.rememberGraph

/**
 * Phone side of the companion LAN link: CONTROLLER only. Browses for TV hosts on the LAN,
 * pairs with the 6-digit code the TV shows, and reconnects to already-paired hosts without it.
 *
 * Mirrors [com.arkiv.player.ui.tv.TvCompanionSettings], the TV's HOST-only screen -- each side of
 * the link owns exactly one role, so this never calls `startHost()`.
 */
@Composable
fun CompanionSettings() {
    val graph = rememberGraph()
    val companion = graph.companion

    val hosts by companion.hosts.collectAsStateWithLifecycle()
    val linkState by companion.linkState.collectAsStateWithLifecycle()
    val connectedDeviceId by companion.connectedDeviceId.collectAsStateWithLifecycle()
    var peers by remember { mutableStateOf(companion.pairedPeers()) }

    // Display + pairing. Browsing and auto-reconnect to a paired TV are started app-wide by
    // ArkivApp's foreground lifecycle observer, so `hosts` already reflects the app-wide browse.
    // Re-assert it on entering this screen too: startAutoConnect() is idempotent for the connect
    // job but re-runs startBrowsing(), which recovers a discovery that had failed and exhausted its
    // retries -- otherwise the user would face an empty host list here with no way to refresh it.
    LaunchedEffect(Unit) { companion.startAutoConnect() }

    // pairedPeers() isn't a Flow, so re-read it whenever a pairing just succeeded.
    LaunchedEffect(linkState) {
        if (linkState == LinkState.Connected) peers = companion.pairedPeers()
    }

    var pendingHost by remember { mutableStateOf<DiscoveredHost?>(null) }
    var pendingCode by remember { mutableStateOf("") }

    pendingHost?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingHost = null; pendingCode = "" },
            title = { Text("Código de emparejamiento") },
            text = {
                Column {
                    Text("Ingresa el código de 6 dígitos que muestra \"${host.name}\".")
                    OutlinedTextField(
                        value = pendingCode,
                        onValueChange = { if (it.length <= 6) pendingCode = it },
                        singleLine = true,
                        label = { Text("Código") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        companion.connect(host, pendingCode)
                        pendingHost = null
                        pendingCode = ""
                    },
                    enabled = pendingCode.length == 6,
                ) { Text("Conectar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingHost = null; pendingCode = "" }) { Text("Cancelar") }
            },
        )
    }

    var ipPortText by remember { mutableStateOf("") }
    var ipCode by remember { mutableStateOf("") }
    val parsedIpPort = remember(ipPortText) { parseIpPort(ipPortText) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Conectar", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Vincula este celular con un KINO de tu TV en la misma red Wi-Fi para controlarlo.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        Text("Estado: ${linkState.controllerLabel()}", style = MaterialTheme.typography.bodyMedium)
        // Explicit disconnect: the link is app-scoped now (not dropped on leaving the tab), so this
        // is the only way to end it on purpose.
        if (linkState != LinkState.Idle && linkState != LinkState.Error) {
            TextButton(onClick = { companion.disconnect() }) { Text("Desconectar") }
        }

        Text(
            "Dispositivos encontrados",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (hosts.isEmpty()) {
            Text("Buscando en la red Wi-Fi…", style = MaterialTheme.typography.bodySmall)
        } else {
            hosts.forEach { host ->
                val isPaired = peers.any { it.deviceId == host.deviceId }
                val isConnectedHost =
                    linkState == LinkState.Connected && host.deviceId == connectedDeviceId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The host we're already connected to isn't clickable: nothing to (re)connect.
                        .then(
                            if (isConnectedHost) Modifier
                            else Modifier.clickable {
                                if (isPaired) companion.connect(host, null) else pendingHost = host
                            },
                        )
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(host.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${host.ip}:${host.port}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        when {
                            isConnectedHost -> "Conectado"
                            isPaired -> "Conectar"
                            else -> "Emparejar"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isConnectedHost) ArkivRed else Color.Unspecified,
                    )
                }
            }
        }

        Text(
            "Conectar por IP",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        Text(
            "Si no aparece en la lista, ingresa su dirección y el código que muestra en pantalla.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = ipPortText,
            onValueChange = { ipPortText = it },
            singleLine = true,
            label = { Text("IP:puerto") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ipCode,
            onValueChange = { if (it.length <= 6) ipCode = it },
            singleLine = true,
            label = { Text("Código (si es la primera vez)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = {
                val (ip, port) = parsedIpPort ?: return@Button
                companion.connectByIp(ip, port, ipCode.ifBlank { null })
            },
            enabled = parsedIpPort != null,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Conectar por IP") }

        Text(
            "Dispositivos emparejados",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (peers.isEmpty()) {
            Text("Todavía no hay dispositivos emparejados.", style = MaterialTheme.typography.bodySmall)
        } else {
            peers.forEach { peer ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(peer.name, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = {
                        companion.forget(peer.deviceId)
                        peers = companion.pairedPeers()
                    }) { Text("Olvidar") }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun LinkState.controllerLabel(): String = when (this) {
    LinkState.Idle -> "Inactivo"
    LinkState.Connecting -> "Conectando…"
    LinkState.Pairing -> "Emparejando…"
    LinkState.Connected -> "Conectado"
    LinkState.Reconnecting -> "Reconectando…"
    LinkState.Error -> "Error"
}

/** "ip:port" -> (ip, port), or null if malformed. One field instead of two -- it's the same shape
 *  the TV screen shows its own address in, so there's nothing to translate between them. */
private fun parseIpPort(text: String): Pair<String, Int>? {
    val trimmed = text.trim()
    val idx = trimmed.lastIndexOf(':')
    if (idx <= 0 || idx == trimmed.length - 1) return null
    val ip = trimmed.substring(0, idx)
    val port = trimmed.substring(idx + 1).toIntOrNull() ?: return null
    if (ip.isBlank() || port !in 1..65535) return null
    return ip to port
}
