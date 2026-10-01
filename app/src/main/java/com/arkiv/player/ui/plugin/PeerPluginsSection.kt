package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.plugin.sync.PeerOfferStatus
import com.arkiv.player.data.plugin.sync.PeerPluginOffer
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/** The title of the list of plugins the person has on another device and not on this one (phone and TV). */
const val PEER_PLUGINS_TITLE = "Plugins de tus otros aparatos"

/** What a row of that list says about its plugin here (phone and TV). */
fun peerOfferStatusText(status: PeerOfferStatus): String = when (status) {
    PeerOfferStatus.WAITING -> "Se instalará solo en un momento"
    PeerOfferStatus.INSTALLING -> "Instalando…"
    PeerOfferStatus.NEEDS_CONSENT -> "Pide más permisos que en tu otro aparato: revísalos para instalarlo"
    PeerOfferStatus.FAILED -> "No se pudo instalar solo"
}

/** Whether a row's "Instalar" can be pressed: never while that plugin is already installing by itself, or another action runs. */
fun peerOfferInstallEnabled(status: PeerOfferStatus, busy: Boolean): Boolean = !busy && status != PeerOfferStatus.INSTALLING

/**
 * Phone: the plugins from the person's other devices that did not install here by themselves, each with
 * "Instalar" (the normal consent sheet). Drawn above the tabs, nothing at all when there are none.
 */
@Composable
internal fun PeerPluginsSection(offers: List<PeerPluginOffer>, busy: Boolean, onInstall: (PeerPluginOffer) -> Unit, modifier: Modifier = Modifier) {
    if (offers.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(PEER_PLUGINS_TITLE, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
        offers.forEach { offer ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(offer.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(peerOfferStatusText(offer.status), style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onInstall(offer) }, enabled = peerOfferInstallEnabled(offer.status, busy)) {
                    Text("Instalar", color = ArkivRed)
                }
            }
        }
    }
}
