package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkiv.player.data.ArkivRepository

/**
 * Which chapter is playing and who its neighbors are: the pause overlay's header and the
 * "Previous chapter" / "Next episode" buttons.
 *
 * All three come from the same query and change together —on jumping chapter, all three— so they
 * travel together. And all three are only read from the overlay.
 */
@Stable
internal class HeaderState(private val repository: ArkivRepository) {
    /** Item title + episode name (series only), for the header. */
    var info by mutableStateOf<ArkivRepository.PlayerHeaderInfo?>(null)
        private set

    /**
     * Neighboring episodes, if any. Both are null on movies (a single section, see
     * EpisodeNavigation) and each is null at its own end: the season's first has no previous, the
     * last has no next. That nullity is the ONLY condition for showing the buttons.
     */
    var previous by mutableStateOf<String?>(null)
        private set

    var next by mutableStateOf<String?>(null)
        private set

    /** Title to show, with [fallback] for while the query hasn't returned yet. */
    fun title(fallback: String): String = info?.itemTitle ?: fallback

    /** Season/chapter, or null on movies and while there's no response yet. */
    val episodeLabel: String? get() = info?.episodeLabel

    internal suspend fun load(currentEpisode: String) {
        previous = repository.previousEpisode(currentEpisode)?.id
        next = repository.nextEpisode(currentEpisode)?.id
        info = repository.headerInfo(currentEpisode)
    }
}

@Composable
internal fun rememberHeaderState(repository: ArkivRepository): HeaderState =
    remember(repository) { HeaderState(repository) }

/** Reloads the header and its neighbors every time the playing chapter changes. */
@Composable
internal fun HeaderEffect(state: HeaderState, currentEpisode: String) {
    LaunchedEffect(currentEpisode) { state.load(currentEpisode) }
}
