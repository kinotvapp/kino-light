package com.arkiv.player.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.playback.LangOrderEdits
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.ui.settings.label
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * Same logic as the phone's editor ([com.arkiv.player.ui.settings.LanguageOrderEditor]) but with
 * `androidx.tv.material3`'s components: the TV library's `Surface` is the one that knows how to
 * paint itself on receiving remote focus. The reorder logic is shared ([LangOrderEdits]), so the
 * two screens can't drift out of sync.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLanguageOrderEditor(
    title: String,
    options: List<TrackLang>,
    order: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(title, color = ArkivTextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.forEachIndexed { i, lang ->
            Row(
                Modifier.fillMaxWidth(0.6f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvLangButton("✓ ${i + 1}. ${lang.label()}", Modifier.weight(1f)) {
                    onChange(LangOrderEdits.toggle(order, lang))
                }
                TvLangButton("▲") { onChange(LangOrderEdits.moveUp(order, lang)) }
                TvLangButton("▼") { onChange(LangOrderEdits.moveDown(order, lang)) }
            }
        }
        options.filterNot { it in order }.forEach { lang ->
            TvLangButton(lang.label(), Modifier.fillMaxWidth(0.6f)) {
                onChange(LangOrderEdits.toggle(order, lang))
            }
        }
    }
}

/**
 * TV version of [com.arkiv.player.ui.settings.LanguageChecklistEditor]: the same languages but
 * with NO arrows, since order means nothing here. Each row is a button that toggles the ✓.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvLanguageChecklist(
    title: String,
    subtitle: String,
    options: List<TrackLang>,
    selected: List<TrackLang>,
    onChange: (List<TrackLang>) -> Unit,
) {
    Text(title, color = ArkivTextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
    Text(subtitle, color = ArkivTextSecondary, modifier = Modifier.padding(bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { lang ->
            val mark = if (lang in selected) "✓ " else "   "
            TvLangButton("$mark${lang.label()}", Modifier.fillMaxWidth(0.6f)) {
                onChange(LangOrderEdits.toggle(selected, lang))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLangButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        // Same shared style as the rest of TV Settings' buttons (TvButtonStyle.kt): without
        // explicit colors, tv.material3's Surface falls back to the library's default light
        // scheme and the button looks white.
        colors = arkivTvSurfaceColors(),
        border = arkivTvSurfaceBorder(),
    ) {
        Text(label, color = Color.White, modifier = Modifier.padding(12.dp))
    }
}
