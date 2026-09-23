package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.companion.LinkState
import com.arkiv.player.playback.LanIp
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * TV side of the companion LAN link: HOST only. Starts the WebSocket server + mDNS advertisement
 * while this screen is visible and shows the pairing code + address a phone needs to connect.
 *
 * Mirrors [com.arkiv.player.ui.settings.CompanionSettings], the phone's CONTROLLER-only screen --
 * each side of the link owns exactly one role, so this never browses or calls `connect*()`.
 *
 * Purely informational: no D-pad controls of its own (nothing here to act on besides reading the
 * code), so there's nothing to focus-request on entry -- the tab row above keeps the remote's
 * up/down landing predictably.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCompanionSettings() {
    val graph = rememberGraph()
    val companion = graph.companion
    val context = LocalContext.current

    val code by companion.code.collectAsStateWithLifecycle()
    val port by companion.port.collectAsStateWithLifecycle()
    val linkState by companion.linkState.collectAsStateWithLifecycle()
    // Re-resolve when the host binds its port: at first composition the Wi-Fi IP can still be
    // unavailable (returns null -> "Buscando…"), but by the time the port is known it's ready.
    var ip by remember { mutableStateOf(LanIp.current(context)) }
    LaunchedEffect(port) { if (port > 0) ip = LanIp.current(context) }

    // pairedPeers() isn't a Flow; re-read it whenever a phone just finished pairing so "Conectado"
    // can name it, the way the spec's copy expects ("Conectado: <nombre>").
    var peers by remember { mutableStateOf(companion.pairedPeers()) }
    LaunchedEffect(linkState) {
        if (linkState == LinkState.Connected) peers = companion.pairedPeers()
    }
    val connectedName = peers.lastOrNull()?.name

    // Display-only: hosting is started app-wide by ArkivApp's foreground lifecycle observer (it must
    // stay up on every screen, and even while on the TV home), so this screen only shows the code +
    // address + status. It no longer starts or stops the host.

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text("Conectar", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            "Abre la app en tu celular, entra a Ajustes > Conectar y usa este código.",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        Text("Código", style = MaterialTheme.typography.titleMedium, color = ArkivTextSecondary)
        Text(
            code.ifBlank { "······" },
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        Text("Dirección", style = MaterialTheme.typography.titleMedium, color = ArkivTextSecondary)
        Text(
            if (ip != null && port > 0) "$ip:$port" else "Buscando dirección de red…",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        Text("Estado", style = MaterialTheme.typography.titleMedium, color = ArkivTextSecondary)
        Text(
            linkState.hostLabel(connectedName),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun LinkState.hostLabel(connectedName: String?): String = when (this) {
    LinkState.Idle, LinkState.Connecting -> "Esperando…"
    LinkState.Pairing -> "Emparejando…"
    LinkState.Connected -> if (connectedName != null) "Conectado: $connectedName" else "Conectado"
    LinkState.Reconnecting -> "Reconectando…"
    LinkState.Error -> "Error"
}
