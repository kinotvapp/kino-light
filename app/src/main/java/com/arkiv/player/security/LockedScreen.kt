package com.arkiv.player.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The only thing shown when the device fails the checks. No continue button, on purpose.
 *
 * The reasons are listed: a warning that explains nothing is indistinguishable from a bug, and
 * whoever sees this on a device they believe is clean needs to know what was found to be able to
 * argue about it.
 */
@Composable
fun LockedScreen(reasons: List<String>) {
    Column(
        modifier = Modifier.fillMaxSize().background(ArkivBlack).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Este dispositivo no puede ejecutar Kino",
            style = MaterialTheme.typography.headlineSmall,
            color = ArkivRed,
            textAlign = TextAlign.Center,
        )
        Text(
            "Se detectó acceso root o una modificación del sistema.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (reasons.isNotEmpty()) {
            Text(
                reasons.joinToString("\n") { "· $it" },
                style = MaterialTheme.typography.bodySmall,
                color = ArkivTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}
