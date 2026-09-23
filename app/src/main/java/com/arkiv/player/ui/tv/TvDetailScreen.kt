package com.arkiv.player.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arkiv.player.data.model.Episode
import com.arkiv.player.thumbnails.ThumbnailChoice
import com.arkiv.player.ui.detail.DetailViewModel
import com.arkiv.player.ui.rememberGraph
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * An item's detail, Prime Video style: full-screen backdrop with gradient, info (title/episode
 * count/description/play) over the left side, and a horizontal episode carousel below — the same
 * [TvEpisodeChip] the player's pause overlay uses.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDetailScreen(
    /** Group key (`tv:46260`) or a raw identifier (an item that isn't grouped yet, or "Continue watching"). */
    groupKey: String,
    onPlayEpisode: (String) -> Unit,
) {
    val graph = rememberGraph()
    val vm: DetailViewModel = viewModel(
        factory = viewModelFactory { initializer { DetailViewModel(graph.repository, groupKey) } },
    )
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val data = detail
    if (data == null) {
        // This used to be `?: return`: a black screen with no notice at all, both while the
        // detail is loading and when the group key stopped existing (see the
        // LibraryGrouping.resolveMembers/observeGroupMembers bug — a group whose key moved out
        // from under the user, and this gave no notice, looked IDENTICAL to a crash). A simple
        // message is enough: no need to distinguish "still loading" from "not found".
        Box(Modifier.fillMaxSize().background(ArkivBlack), contentAlignment = Alignment.Center) {
            Text(
                "No se pudo cargar este contenido",
                style = MaterialTheme.typography.titleMedium,
                color = ArkivTextSecondary,
            )
        }
        return
    }
    // REAL identifier of the source being shown (not the route's group key): what `data` brings
    // already resolved `groupKey` to a concrete item. TMDB stills/titles and the focused-chapter
    // cache are indexed by that identifier, not the key.
    val identifier = data.identifier

    val playFR = remember { FocusRequester() }
    val resumeEpisodeFR = remember { FocusRequester() }
    // Anchor for the first "Fuentes" chip: without this, the direct Play<->resumable-chapter jump
    // (below) skipped the whole row and left it unreachable with the D-pad.
    val firstSourceFR = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()

    // The carousel opens positioned on the chapter that was being watched (the same one the Play
    // button plays). On long series it ended up off-screen and had to be found by hand.
    // Per-chapter TMDB stills: resolved once and cached in the database; Coil handles the images'
    // on-disk cache.
    val stills by graph.repository.observeEpisodeStills(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // Frames captured during playback: the chapter's real scene, when it exists it beats the TMDB
    // still (see ThumbnailChoice). Only has an entry if the chapter was started, so "only wins on
    // what was started" falls straight out of the key not being there.
    val frames by graph.repository.observeEpisodeFrames(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // The chapter's real titles (TMDB). The filename is usually useless ("s01e03"), and it shows
    // much more in the hero —which is large text— than in the list.
    val episodeTitles by graph.repository.observeEpisodeTitles(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    // Per-chapter synopsis (TMDB), for the description block below when one is focused in the
    // carousel.
    val episodeOverviews by graph.repository.observeEpisodeOverviews(identifier)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    LaunchedEffect(identifier) {
        // Before requesting stills: a Magis item saved with no `tmdbId` has nothing to request
        // them with, and `ensureEpisodeStills` would bail on its first line. This asks the
        // gateway once (skips itself if it already has an identity). See [repairMagisIdentity].
        com.arkiv.player.data.gateway.repairMagisIdentity(
            graph.repository, graph.contentSource, identifier,
        )
        runCatching { graph.repository.ensureEpisodeStills(identifier) }
    }

    // Chapter focused in the carousel: the background and the texts above follow it, same as the
    // Home's hero follows the focused card. Null = focus outside the carousel (e.g. on "Play"),
    // and then the series' info is shown. Also resets on switching sources (the "Fuentes" chip):
    // the focused chapter belongs to the old list.
    var focusedEpisode by remember(identifier) { mutableStateOf<Episode?>(null) }

    val resumeId = data.resumeEpisode?.id
    // Repositions the carousel ONLY on switching sources (the "Fuentes" chip), not on every
    // `resumeId` change. `episodesListState` carries no `key` by `identifier`, so it survives the
    // source switch; without this reset the carousel stayed scrolled to the old source's offset
    // when the new source's chapter to resume landed at index 0 — leaving the resumable chip (and
    // `resumeEpisodeFR`, which anchors focus from the first source chip) outside the LazyRow's
    // composed window.
    //
    // NOTE: the key is `identifier` alone, NOT `resumeId`. `resumeId` also changes WITHIN the
    // SAME source when the current chapter passes 60% and `savePlayback` marks it watched
    // (ArkivRepository.resumeEpisode moves on to offering the NEXT chapter): that's the most
    // common flow for returning to the detail, and if the effect ran on that key the carousel
    // would jump far from where the user was every time something finished. By depending only on
    // `identifier`, this LaunchedEffect doesn't restart in that case — we keep reading `data` and
    // `resumeId` "from behind the closure" of the composition where `identifier` changed, which is
    // exactly the newly chosen source.
    LaunchedEffect(identifier) {
        val idx = data.episodes.indexOfFirst { it.id == resumeId }
        episodesListState.scrollToItem(idx.coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        // The background follows the focused chapter. Falls back to the series' backdrop when
        // that chapter has no still (TMDB doesn't always bring them) or when focus isn't in the
        // carousel.
        // The hero ALWAYS describes what's going to happen if you press the button: with focus in
        // the carousel, the focused chapter; with focus outside (on "Play"), the chapter THAT
        // button resumes. Before, moving focus off the carousel fell back to the SERIES' info and
        // looked incoherent: the button said "Reproducir T1 · E8" while the background changed
        // image and the chapter's description disappeared.
        //
        // This does NOT contradict the button's `focusedEpisode = null`, it completes it: that
        // null exists so it doesn't stay describing the last chapter you scrolled through (which
        // isn't the one that plays). This fallback puts in its place the one that DOES play.
        //
        // Series only: in a movie `resumeEpisode` is the only "chapter", and describing it as a
        // chapter would lose the synopsis and the "Película" label.
        val focused = focusedEpisode ?: data.resumeEpisode?.takeIf { data.episodes.size > 1 }
        val heroImage = focused?.let { ep ->
            // The captured frame outranks the TMDB still. The archive.org thumb that used to
            // follow was removed in this branch's pruning along with that source.
            ThumbnailChoice.choose(
                frames[ep.id],
                stills[ep.id],
                null,
            )
        } ?: data.thumbnailUrl
        // Crossfade: without this, scrolling the carousel with the D-pad makes the whole
        // background flicker on every chip. With the fade the change reads as continuous.
        Crossfade(targetState = heroImage, label = "hero") { img ->
            AsyncImage(
                model = img,
                contentDescription = data.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Horizontal gradient: black on the left to read the text (same as the Home's hero).
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(ArkivBlack, ArkivBlack, ArkivBlack.copy(alpha = 0.15f), Color.Transparent)),
            ),
        )
        // Vertical gradient: black at the bottom to blend into the carousel.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, ArkivBlack.copy(alpha = 0.4f), ArkivBlack)),
            ),
        )

        Column(Modifier.fillMaxSize()) {
            // --- Info (title, count, description, play) ---
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                // With a chapter focused, the hero switches to describing THAT chapter; the
                // series' name moves down to the line above so the context of where you are isn't
                // lost.
                if (focused != null) {
                    Text(
                        data.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ArkivTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    focused?.let { episodeTitles[it.id] ?: it.displayName } ?: data.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (focused != null) Modifier.padding(top = 2.dp) else Modifier,
                )
                Text(
                    when {
                        focused != null -> episodeMeta(focused)
                        data.episodes.size > 1 -> com.arkiv.player.ui.ChapterLabel.progressSummary(data, "episodios")
                        else -> "Película"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // With a chapter focused, ITS synopsis (TMDB, saved by Task 5) if there is one; if
                // not, nothing — the series' doesn't describe THAT specific chapter. With no
                // chapter focused, the series' synopsis, as always.
                (if (focused != null) episodeOverviews[focused.id] else data.description)
                    ?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp),
                    )
                }
                data.resumeEpisode?.let { resume ->
                    Button(
                        onClick = { onPlayEpisode(resume.id) },
                        colors = arkivTvButtonColors(),
                        border = arkivTvButtonBorder(),
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .focusRequester(playFR)
                            // On returning up from the carousel, the hero goes back to the series:
                            // if the last chapter stayed focused, the "Play" button (which resumes
                            // a different one) would be describing something it isn't going to play.
                            .onFocusChanged { if (it.isFocused) focusedEpisode = null }
                            // If there's a source selector, going down lands there first; if not,
                            // straight to the resumable chapter (as before). Without this
                            // conditional the explicit jump skipped the whole "Fuentes" row.
                            .focusProperties { down = if (sources.size > 1) firstSourceFR else resumeEpisodeFR },
                    ) {
                        Text("▶  ${com.arkiv.player.ui.ChapterLabel.playButtonLabel(data)}")
                    }
                }
            }

            // --- Source selector: only shows up if the same series entered through more than one path ---
            if (sources.size > 1) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    Text(
                        "Fuentes",
                        style = MaterialTheme.typography.titleSmall,
                        color = ArkivTextSecondary,
                        modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(sources, key = { _, it -> it.identifier }) { index, src ->
                            // Source name + how many chapters it contributes: that's what lets you
                            // decide (e.g. "magis · 300 ep." vs "ditu · 267 ep.", or a legacy
                            // "web"/"torrent" source still saved in the library from before this
                            // branch's pruning).
                            TvSourceChip(
                                label = "${src.source} · ${src.episodeCount} ep.",
                                selected = src.identifier == selectedId,
                                onClick = { vm.selectSource(src.identifier) },
                                // Only the first chip anchors the explicit Play<->carousel jump:
                                // it's where those shortcuts land, so it has to be able to send
                                // them back both ways.
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(firstSourceFR)
                                        .focusProperties {
                                            // playFR and resumeEpisodeFR only have an attached node
                                            // when the current source has a chapter to resume (the
                                            // "Play" button and the resumable chip render
                                            // conditioned on that). A newly added source with 0
                                            // episodes (failed fetch) leaves both unattached:
                                            // continuing to point at them there makes Compose throw
                                            // IllegalStateException when moving focus. With
                                            // FocusRequester.Default the D-pad uses the default
                                            // algorithm instead of crashing.
                                            up = if (data.resumeEpisode != null) playFR else FocusRequester.Default
                                            down = if (data.resumeEpisode != null) resumeEpisodeFR else FocusRequester.Default
                                        }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }

            // --- Episode carousel (same component as the player's pause overlay) ---
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    if (data.episodes.size > 1) "Episodios" else "Detalles",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArkivTextSecondary,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
                LazyRow(
                    state = episodesListState,
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(data.episodes, key = { it.id }) { ep ->
                        val isResume = ep.id == resumeId
                        TvEpisodeChip(
                            episode = ep,
                            isCurrent = data.inProgressEpisode?.id == ep.id,
                            progress = data.progress[ep.id],
                            // Same order as the hero: frame -> TMDB still. The archive.org fallback
                            // that used to live inside TvEpisodeChip after these two was removed
                            // with the rest of that source; with neither of these, the chip just
                            // shows a plain black background (see TvEpisodeChip's own comment).
                            stillUrl = ThumbnailChoice.choose(frames[ep.id], stills[ep.id]),
                            onClick = { onPlayEpisode(ep.id) },
                            onFocus = { focusedEpisode = ep },
                            modifier = Modifier.then(
                                if (isResume) {
                                    // Symmetric to Play's `down`: if there's a source selector,
                                    // going up lands there; if not, straight to Play (as before).
                                    Modifier.focusRequester(resumeEpisodeFR)
                                        .focusProperties { up = if (sources.size > 1) firstSourceFR else playFR }
                                } else {
                                    Modifier
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Data line for the focused chapter: "T1 · E3 · 24 min".
 *
 * The numbering lives in [com.arkiv.player.ui.ChapterLabel] (shared with the phone detail
 * screen); this just adds the minutes, when the duration is known.
 */
private fun episodeMeta(ep: Episode): String {
    val minutes = (ep.durationSeconds / 60).toInt()
    val number = com.arkiv.player.ui.ChapterLabel.number(ep)
    return if (minutes > 0) "$number · $minutes min" else number
}
