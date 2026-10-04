package app.kino.demo.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.demo.data.DemoSource
import app.kino.demo.ui.LocalReducedEffects
import app.kino.demo.ui.cardFocusScale
import app.kino.demo.ui.phone.cardInitial
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurface
import app.kino.demo.ui.theme.KinoTextSecondary

/**
 * A plugin as a TV card: the icon tile in its colour, name, one line of description and what OK
 * does ("Instalar" in red, a grey "Instalado ✓", or the installed plugin's state).
 */
@Composable
internal fun TvCatalogCard(plugin: DemoSource, actionLabel: String, actionIsQuiet: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = cardFocusScale(LocalReducedEffects.current),
        colors = CardDefaults.colors(containerColor = KinoSurface),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(12.dp))),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(plugin.color).copy(alpha = 0.35f), Color.Transparent))),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(plugin.color)), contentAlignment = Alignment.Center) {
                    Text(cardInitial(plugin.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
                }
                if (plugin.community) {
                    Text(
                        "De la comunidad",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(KinoRed).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plugin.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (actionIsQuiet) KinoTextSecondary else KinoRed,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
