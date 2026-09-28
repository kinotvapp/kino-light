package com.arkiv.player.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkiv.player.data.live.LiveProviderTab

/**
 * The provider's name on a channel card, drawn only when the module has more than one provider
 * ([providerBadge]). BasicText, so phone (Material3) and TV (tv-material) cards share it.
 */
@Composable
fun ProviderBadge(tab: LiveProviderTab, modifier: Modifier = Modifier) {
    BasicText(
        text = tab.name,
        style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(tab.color).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
