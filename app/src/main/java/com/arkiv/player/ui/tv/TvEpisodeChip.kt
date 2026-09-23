package com.arkiv.player.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.ChapterLabel
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivSurfaceHigh

/**
 * Episode card for a horizontal carousel (used in the player's pause overlay and in a series'
 * detail): thumbnail + number + progress ("10 de 25 min" / "Visto") + thin bar.
 * Reusable with D-pad: [modifier] is where the caller hangs focusRequester/focusProperties.
 */
@Composable
fun TvEpisodeChip(
    episode: Episode,
    isCurrent: Boolean,
    progress: PlaybackEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Chapter still (TMDB). If null the background stays plain black -- the archive.org thumb
     *  that used to be the fallback was removed in this branch's pruning (see where it's drawn). */
    stillUrl: String? = null,
    /**
     * The chapter's real name (TMDB, or whatever the Magis gateway brought). Goes BELOW the
     * number, not in its place: the number identifies the chapter that's about to play and stays
     * the trustworthy datum even if the TMDB cross-reference drifts. Null (or a chapter with no
     * resolved name) leaves the chip exactly as it was.
     */
    episodeTitle: String? = null,
    /** Called when this chip TAKES focus, so the screen above follows the focused chapter
     *  (background + texts), same as the Home's hero follows the focused card. */
    onFocus: (() -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val totalMin = (episode.durationSeconds / 60).toInt().coerceAtLeast(0)
    val watchedFrac = if (progress != null && progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    // Only show progress in minutes when there's a real duration: a chapter with no duration
    // metadata yet (Magis/Ditu both save durationSeconds = 0.0 when first written; torrent packs
    // used to do the same before this branch's pruning) would show a meaningless "0 de 0 min".
    val progressLabel = when {
        progress == null || progress.positionMs <= 0 || totalMin <= 0 -> null
        progress.watched -> "Visto"
        else -> "${(progress.positionMs / 60000).toInt().coerceAtLeast(0)} de $totalMin min"
    }
    val episodeLabel = ChapterLabel.number(episode)

    Column(
        modifier = modifier
            .width(168.dp)
            .onFocusChanged {
                // Only on GAINING focus: if losing it also notified, moving from one chip to the
                // next would have the old one's "lost it" arrive after the new one's "got it" and
                // the hero would end up showing the wrong chapter.
                if (it.isFocused && !isFocused) onFocus?.invoke()
                isFocused = it.isFocused
            }
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) ArkivRed.copy(alpha = 0.25f) else ArkivSurfaceHigh)
            .border(
                width = if (isFocused) 3.dp else if (isCurrent) 2.dp else 0.dp,
                color = if (isFocused) Color.White else if (isCurrent) ArkivRed else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(94.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
        ) {
            // Preference: the chapter's real still (TMDB). The archive.org thumbnail that used to
            // follow was removed in this branch's pruning; with neither, it stays black.
            val thumb = stillUrl
            AsyncImage(
                model = thumb,
                contentDescription = episode.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (watchedFrac > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x66000000)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(watchedFrac)
                            .fillMaxSize()
                            .background(ArkivRed),
                    )
                }
            }
        }
        Text(
            episodeLabel,
            color = if (isCurrent) ArkivRed else Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        episodeTitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                // One line: the chip is 168 dp and the progress still goes below. A long name
                // ("La conspiración de los Saiyajin") gets cut off, not pushing the rest of the
                // carousel.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        progressLabel?.let {
            Text(
                it,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Chip for one of the series' sources ("web · 300 ep."), to pick which one to watch the chapters
 * from when the same series entered the library from several. Same focus treatment as
 * [TvEpisodeChip]: only notifies on GAINING focus, because the old chip's "lost it" arrives after
 * the new one's "got it" and would leave the screen showing the wrong source.
 */
@Composable
fun TvSourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ArkivRed.copy(alpha = 0.25f) else ArkivSurfaceHigh)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}
