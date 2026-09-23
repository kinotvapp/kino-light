package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.ui.tv.TvEpisodeChip
import kotlinx.coroutines.delay

/**
 * How many frames it retries hooking focus to the current chip for. On low-end TV (Fire Stick)
 * the freshly-revealed LazyRow isn't composed or measured on the first frame.
 */
private const val FOCUS_ATTEMPTS = 12
private const val WAIT_BETWEEN_ATTEMPTS_MS = 32L

/**
 * Chapter carousel for the pause overlay (TV only): every episode of the series in horizontal
 * scroll, one step below the icon row, with the current one highlighted and centered.
 *
 * Split out of [PlayerContent] because it was six variables and three effects that only touch
 * each other; the only things the screen still reads are the overlay's two guards ([revealed] for
 * the auto-hide timer, and how many episodes there are to know if the carousel exists).
 */
@Stable
internal class ChaptersState(
    private val repository: ArkivRepository,
    val listState: LazyListState,
) {
    /** The carousel is expanded. Opening/closing it resets the overlay's auto-hide timer. */
    var revealed by mutableStateOf(false)
        private set

    var episodes by mutableStateOf<List<Episode>>(emptyList())
        private set

    /**
     * Progress (position/duration/watched) of each episode, to show "10 of 25 min" on the
     * cards — the bar alone isn't enough to know how many minutes are left.
     */
    var progressByEpisode by mutableStateOf<Map<String, PlaybackEntity>>(emptyMap())
        private set

    /** TMDB stills by chapter (already cached by the detail screen; only read here). */
    var stills by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /**
     * Real chapter names, from the SAME table as the stills (`episode_still`) and the same path
     * the two detail screens already use. The chip showed the picture but not the name, so in the
     * pause overlay a series was still just a row of "E1 E2 E3" without saying what each one is,
     * exactly the data that other work brought in from the gateway.
     */
    var titles by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The requester lives here because the chip that receives it is chosen by [indexOf], not the screen. */
    val focusRequester = FocusRequester()

    /** There's a carousel to show: a movie or a standalone chapter doesn't build a row. */
    val hasCarousel: Boolean get() = episodes.size > 1

    /**
     * Index of the current chapter, or 0 if not found (e.g. torrent packs with a different id).
     * Used both to center the scroll and to hang the [focusRequester] on THAT chip: before, the
     * requester only hung on the `isCurrent` chip, so if the current one wasn't in the list focus
     * could never enter the carousel.
     */
    fun indexOf(currentEpisode: String): Int =
        episodes.indexOfFirst { it.id == currentEpisode }.coerceAtLeast(0)

    fun reveal() {
        revealed = true
    }

    fun hide() {
        revealed = false
    }

    internal suspend fun load(itemId: String) {
        episodes = repository.episodesOf(itemId)
        progressByEpisode = repository.playbackForItem(itemId)
        runCatching { repository.ensureEpisodeStills(itemId) }
        repository.observeEpisodeStills(itemId).collect { stills = it }
    }

    internal suspend fun observeTitles(itemId: String) {
        repository.observeEpisodeTitles(itemId).collect { titles = it }
    }

    internal suspend fun onReveal(itemId: String, currentIndex: Int) {
        // Refresh the progress on opening: the just-paused current episode's position might not
        // be reflected yet in the initial load.
        progressByEpisode = repository.playbackForItem(itemId)
        listState.scrollToItem(currentIndex)
        // Move focus to the current chip. A single requestFocus() would fail on slow hardware
        // (FocusRequester not initialized) and the runCatching swallowed it silently: focus stayed
        // on the button row -> left/right seeked instead of navigating. Retries until the
        // requester is hooked (wait for the condition, not a fixed delay that isn't enough on slow
        // hardware).
        var landed = false
        repeat(FOCUS_ATTEMPTS) {
            if (landed || !revealed) return@repeat
            landed = runCatching { focusRequester.requestFocus() }.isSuccess
            if (!landed) delay(WAIT_BETWEEN_ATTEMPTS_MS)
        }
    }
}

@Composable
internal fun rememberChaptersState(repository: ArkivRepository): ChaptersState {
    val listState = rememberLazyListState()
    return remember(repository, listState) { ChaptersState(repository, listState) }
}

/**
 * The three effects that feed the carousel. Only run on TV: on the phone the pause overlay
 * doesn't show it, and requesting the episodes would be pure waste.
 *
 * Three coroutines and not a `combine` on purpose: the first one hangs forever on the stills
 * flow's `collect` (it's the last thing it does), so the titles need their own. The second
 * doesn't repeat `ensureEpisodeStills` — both columns come from the same row, which the first one
 * already requested.
 */
@Composable
internal fun ChaptersEffects(
    state: ChaptersState,
    episodeId: String,
    currentEpisode: String,
    isTv: Boolean,
) {
    val itemId = episodeId.substringBefore("::")
    LaunchedEffect(episodeId, isTv) { if (isTv) state.load(itemId) }
    LaunchedEffect(episodeId, isTv) { if (isTv) state.observeTitles(itemId) }
    LaunchedEffect(state.revealed) {
        if (state.revealed) state.onReveal(itemId, state.indexOf(currentEpisode))
    }
}

/**
 * The chapter row itself. [upFocus] is where focus goes back to with the up arrow — the
 * play/pause button in the icon row.
 */
@Composable
internal fun ChapterCarousel(
    state: ChaptersState,
    currentEpisode: String,
    upFocus: FocusRequester,
    onChooseEpisode: (String) -> Unit,
) {
    val currentIndex = state.indexOf(currentEpisode)
    AnimatedVisibility(visible = state.revealed, enter = fadeIn(), exit = fadeOut()) {
        LazyRow(
            state = state.listState,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(state.episodes, key = { _, it -> it.id }) { index, ep ->
                TvEpisodeChip(
                    episode = ep,
                    isCurrent = ep.id == currentEpisode,
                    progress = state.progressByEpisode[ep.id],
                    stillUrl = state.stills[ep.id],
                    episodeTitle = state.titles[ep.id],
                    onClick = { onChooseEpisode(ep.id) },
                    modifier = Modifier
                        // The requester goes on the current-index chip (not isCurrent): that way
                        // focus always has somewhere to land even if the current id isn't in the
                        // list (the index falls back to 0).
                        .then(
                            if (index == currentIndex) Modifier.focusRequester(state.focusRequester) else Modifier,
                        )
                        .focusProperties { up = upFocus }
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (e.key == Key.DirectionUp) {
                                state.hide()
                                runCatching { upFocus.requestFocus() }
                                return@onKeyEvent true
                            }
                            // Swallow the keys that would fall off the row: below the carousel
                            // there's nothing, so Compose's spatial search would hook the video
                            // view (focusable on TV) — focus would go there and, since
                            // controlsVisible stayed true, its listener ignored everything and no
                            // key responded. Same at the ends with left/right. Consumed here
                            // (return true) instead of using FocusRequester.Cancel because that
                            // API is experimental. (The auto-hide timer is reset by the
                            // container's onPreviewKeyEvent, which sees these keys first.)
                            e.key == Key.DirectionDown ||
                                (e.key == Key.DirectionLeft && index == 0) ||
                                (e.key == Key.DirectionRight && index == state.episodes.lastIndex)
                        },
                )
            }
        }
    }
}
