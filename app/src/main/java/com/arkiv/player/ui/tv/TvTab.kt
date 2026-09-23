package com.arkiv.player.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurface

private val TAB_HEIGHT = 52.dp

/**
 * Tab in a TV horizontal row: the "switch big section" gesture with the remote.
 *
 * Started with the catalog's roots ([TvCatalogSections]) and Settings ([TvSettingsScreen])
 * shares it, so both rows look and focus the same way. If they diverged, the same remote
 * movement would look different depending on the screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(TAB_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            // Selected and focused CANNOT be the same red: with both the same, looking at the
            // screen you can't tell which tab you're on from which is open.
            containerColor = if (selected) ArkivRed else ArkivSurface,
            focusedContainerColor = if (selected) ArkivRed else ArkivSurface,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, Color.White),
                shape = RoundedCornerShape(24.dp),
            ),
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
