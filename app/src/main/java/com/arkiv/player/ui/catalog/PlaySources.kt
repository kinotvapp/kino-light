package com.arkiv.player.ui.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.arkiv.player.ui.components.DownloadControl
import com.arkiv.player.ui.components.RowDownload
import com.arkiv.player.ui.components.DownloadStatusLine
import com.arkiv.player.ui.theme.ArkivSurfaceHigh
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * A playable source: Magis, Caracol Streaming (Ditu) or an installed plugin.
 *
 * Up until this branch's pruning (light-magis) there was also an `Archive` variant, deleted along
 * with the rest of archive.org. Ditu was deleted in that same pruning and came back with a direct
 * client, no server of its own (`com.arkiv.player.data.ditu`).
 */
sealed interface PlaySource {
    /** Result from the Magis portal (VOD only). The `ref` is opaque: it's sent as-is to
     *  `MagisResolve.resolveVod` and the app never interprets it. */
    data class Magis(val result: com.arkiv.player.data.gateway.GatewayResult) : PlaySource

    /** Result from Caracol Streaming. Unlike Magis, its `ref` CAN be saved to the library: it
     *  encodes Caracol ids, which are stable (see `DituRef`). */
    data class Ditu(val result: com.arkiv.player.data.gateway.GatewayResult) : PlaySource

    /**
     * Result from an installed plugin. Like Caracol, its ref CAN be saved to the library: a
     * plugin item's id is stable by contract (see `PluginEntities`). [color] is opaque ARGB from
     * the manifest (`PluginColors.parse`).
     */
    data class Plugin(
        val pluginId: String,
        val pluginName: String,
        val color: Long,
        val result: com.arkiv.player.data.gateway.GatewayResult,
    ) : PlaySource
}

/** Magis blue: the accent color of its row, its section and its filter chip. */
val ArkivMagisBlue = Color(0xFF64B5F6)

/** Caracol green: the accent color of its row, its section and its filter chip. */
val ArkivCaracolVerde = Color(0xFF66BB6A)

/** A plugin's accent: its manifest color, or the neutral default. */
val PlaySource.Plugin.accent: Color get() = Color(color)

/** A plugin result is a series when the plugin said `kind: "series"`. Same rule on phone and TV. */
fun PlaySource.Plugin.isSeries(): Boolean = result.kind == "series"

fun accentOf(source: PlaySource): Color = when (source) {
    is PlaySource.Magis -> ArkivMagisBlue
    is PlaySource.Ditu -> ArkivCaracolVerde
    is PlaySource.Plugin -> source.accent
}

/**
 * Whether a Caracol result is a series --a chapter has to be chosen before playing-- or a movie.
 * `DituSource` sets `kind = "series"` on everything that isn't a `VOD` (a `BUNDLE` or a
 * `GROUP_OF_BUNDLES`). A single rule for the phone and the TV.
 */
fun PlaySource.Ditu.isSeries(): Boolean = result.kind == "series"

/** A source's loose datum (quality, language, seeds, size) as a pill. Reading a run-on line like
 *  "Latino · 1080p · 12 seeds · 4.2 GB" costs effort; separated they scan at a glance. */
@Composable
fun MetaChip(text: String, color: Color = ArkivTextSecondary, strong: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (strong) 0.22f else 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text, color = color, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A section's header, kept apart so it can be used loose inside a LazyColumn. */
@Composable
fun SourceSectionHeader(
    tag: String,
    tagColor: Color,
    count: Int,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(tagColor))
        Text(
            tag, color = Color.White, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text("$count", color = ArkivTextSecondary, style = MaterialTheme.typography.labelMedium)
        if (loading) CircularProgressIndicator(color = tagColor, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Colapsar" else "Expandir", tint = ArkivTextSecondary,
        )
    }
}

/**
 * A source row, as a card: accent bar in the origin's color on the left, name in white and the
 * loose data (quality/language/seeds/size) as pills. The bar's color says the origin without
 * spending a text label on every row.
 *
 * [onLongClick] is a Magis movie's watch-or-download choice (see `SearchScreen`'s
 * `longPressResult`): a tap plays it directly now, so that choice moved off the tap gesture --
 * asking on every single card got in the way when someone's just browsing to watch. `null` for
 * anything that doesn't have that choice to offer (series, Caracol).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SourceRow(
    source: PlaySource,
    enabled: Boolean,
    download: RowDownload? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = accentOf(source)
    Row(
        // height(IntrinsicSize.Min) so the left color bar can measure itself against the row's
        // real height: with pills wrapping to two lines, a fixed 56 dp bar was left as a short
        // stub next to a tall row.
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ArkivSurfaceHigh.copy(alpha = 0.55f))
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        // Magis and Caracol bring a cover per result ([posterFor]). With no poster, nothing gets drawn.
        val thumbnail = posterFor(source)
        if (thumbnail.isNotBlank()) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 8.dp).size(width = 38.dp, height = 56.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Icon(
            Icons.Default.PlayArrow, contentDescription = null, tint = accent,
            modifier = Modifier.padding(horizontal = 10.dp).size(20.dp),
        )
        Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 2.dp)) {
            when (source) {
                is PlaySource.Magis -> {
                    val r = source.result
                    Text(
                        r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetaChip("Xuper", ArkivMagisBlue)
                        if (r.extra["program_type"] == "teleplay") MetaChip("Serie")
                        if (r.year.isNotBlank()) MetaChip(r.year)
                        if (r.lang.isNotBlank()) MetaChip(r.lang)
                    }
                }
                is PlaySource.Ditu -> {
                    val r = source.result
                    Text(
                        r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetaChip("Caracol", ArkivCaracolVerde)
                        if (source.isSeries()) MetaChip("Serie")
                        if (r.year.isNotBlank()) MetaChip(r.year)
                    }
                }
                is PlaySource.Plugin -> {
                    val r = source.result
                    Text(
                        r.title, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetaChip(source.pluginName, source.accent)
                        if (source.isSeries()) MetaChip("Serie")
                        if (r.year.isNotBlank()) MetaChip(r.year)
                        if (r.lang.isNotBlank()) MetaChip(r.lang)
                        if (r.quality.isNotBlank()) MetaChip(r.quality)
                    }
                }
            }
            if (download != null) DownloadStatusLine(download.state)
        }
        if (download != null) DownloadControl(download, enabled = enabled)
        Spacer(Modifier.width(8.dp))
    }
}

/** A source's cover, or "" if that source has none. Magis, Caracol and plugins bring it in
 *  `extra["poster"]`. */
fun posterFor(source: PlaySource): String = when (source) {
    is PlaySource.Magis -> source.result.extra["poster"].orEmpty()
    is PlaySource.Ditu -> source.result.extra["poster"].orEmpty()
    is PlaySource.Plugin -> source.result.extra["poster"].orEmpty()
}

/**
 * A source as a cover CARD, to paint in two columns.
 *
 * The alternative to [SourceRow] when the source brings an image: twenty Magis results in text
 * rows are a wall where every title looks alike; with the cover, which is which is recognized at
 * a glance. Sources with no image stay in a row -- see [SourceRow].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceCard(source: PlaySource, enabled: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val poster = posterFor(source)
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(ArkivSurfaceHigh.copy(alpha = 0.55f))
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(ArkivSurfaceHigh)) {
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(
                Icons.Default.PlayArrow, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp),
            )
        }
        Text(
            titleOf(source), color = Color.White, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp),
        ) {
            when (source) {
                is PlaySource.Magis -> {
                    MetaChip("Xuper", ArkivMagisBlue)
                    if (source.result.extra["program_type"] == "teleplay") MetaChip("Serie")
                    if (source.result.year.isNotBlank()) MetaChip(source.result.year)
                }
                is PlaySource.Ditu -> {
                    MetaChip("Caracol", ArkivCaracolVerde)
                    if (source.isSeries()) MetaChip("Serie")
                    if (source.result.year.isNotBlank()) MetaChip(source.result.year)
                }
                is PlaySource.Plugin -> {
                    MetaChip(source.pluginName, source.accent)
                    if (source.isSeries()) MetaChip("Serie")
                    if (source.result.year.isNotBlank()) MetaChip(source.result.year)
                }
            }
        }
    }
}

private fun titleOf(source: PlaySource): String = when (source) {
    is PlaySource.Magis -> source.result.title
    is PlaySource.Ditu -> source.result.title
    is PlaySource.Plugin -> source.result.title
}
