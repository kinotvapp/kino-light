package app.kino.demo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kino.demo.ui.theme.KinoRed
import app.kino.demo.ui.theme.KinoSurfaceHigh
import app.kino.demo.ui.theme.KinoTextPrimary
import app.kino.demo.ui.theme.KinoTextSecondary
import java.util.Locale

/*
 * The phone's Ajustes building blocks: each tab is a run of [SettingsSection]s, a small muted title over a
 * rounded grey [SettingsCard] whose children are rows or free blocks. The card draws a thin divider between
 * consecutive children, so a new setting looks right by just being one more child. The TV has its own widgets.
 */

/** Left/right margin of the phone's Ajustes: tab row, section titles and cards. */
internal val SETTINGS_MARGIN = 16.dp

/** Padding inside a card, also the dividers' inset. */
private val CARD_INSET = 16.dp

private val CardShape = RoundedCornerShape(16.dp)

/** A section's title: small, upper case, muted; TalkBack reads it as a heading. */
@Composable
internal fun SettingsSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(Locale.forLanguageTag("es")),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        color = KinoTextSecondary,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** [title] (none: just the card) over a [SettingsCard] holding [content]. */
@Composable
internal fun SettingsSection(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        if (title != null) SettingsSectionTitle(title)
        SettingsCard(content = content)
    }
}

/** The raised grey card: children stacked top to bottom, an inset divider between two that have some height. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dividerColor = MaterialTheme.colorScheme.outline
    var dividers = IntArray(0)
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(KinoSurfaceHigh)
            .drawWithContent {
                drawContent()
                val inset = CARD_INSET.toPx()
                dividers.forEach { y -> drawLine(dividerColor, Offset(inset, y.toFloat()), Offset(size.width - inset, y.toFloat()), 1.dp.toPx()) }
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = width, minHeight = 0)) }
        val tops = IntArray(placeables.size)
        val lines = ArrayList<Int>()
        var y = 0
        placeables.forEachIndexed { i, p ->
            tops[i] = y
            if (p.height > 0) {
                if (y > 0) lines += y
                y += p.height
            }
        }
        dividers = lines.toIntArray()
        layout(width, y) { placeables.forEachIndexed { i, p -> p.place(0, tops[i]) } }
    }
}

/**
 * One row of a card: [title] with an optional muted [supporting] line, [trailing] at the end. With [onClick]
 * the whole row is the touch target. [titleColor] is only for destructive rows.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    titleColor: Color = KinoTextPrimary,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = CARD_INSET, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing?.invoke(this)
    }
}

/** A row that opens something else: optional [value] and a chevron at the end. */
@Composable
internal fun SettingsNavRow(title: String, onClick: () -> Unit, supporting: String? = null, value: String? = null) {
    SettingsRow(title, supporting = supporting, onClick = onClick) {
        if (value != null) SettingsTrailingValue(value)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KinoTextSecondary)
    }
}

/** A fact ([title] … [value]); tappable when [onClick] is given (a value that cycles). */
@Composable
internal fun SettingsValueRow(title: String, value: String, supporting: String? = null, onClick: (() -> Unit)? = null) {
    SettingsRow(title, supporting = supporting, onClick = onClick) { SettingsTrailingValue(value) }
}

/** An on/off setting: the whole row toggles, the switch only shows it. */
@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, supporting: String? = null) {
    SettingsRow(
        title,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        supporting = supporting,
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** A destructive action: the only rows in red. */
@Composable
internal fun SettingsDestructiveRow(title: String, onClick: () -> Unit, supporting: String? = null) {
    SettingsRow(title, supporting = supporting, titleColor = KinoRed, onClick = onClick)
}

/** Free content inside a card (chips, sliders, fields) with the rows' padding; counts as one child. */
@Composable
internal fun SettingsBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = CARD_INSET, vertical = 12.dp), content = content)
}

/** A muted explanation inside a card, right where it applies. */
@Composable
internal fun SettingsNote(text: String) {
    SettingsBlock { Text(text, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary) }
}

@Composable
private fun SettingsTrailingValue(value: String) {
    Text(value, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
}
