package com.arkiv.player.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.local.DownloadGroup
import com.arkiv.player.data.local.DownloadGroupPolicy
import com.arkiv.player.data.local.DownloadItemMeta
import com.arkiv.player.data.local.DuplicateDownloadPolicy
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.local.LocalDownloadManager
import com.arkiv.player.data.model.Episode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The state comes from Room, which the worker updates. There's no polling anymore: the
 * `refreshProgress()` every 1.5s used to exist because progress lived in the system's
 * DownloadManager and had to be fetched.
 *
 * Groups `downloads` rows by item (see [DownloadGroupPolicy], the pure and tested part). To show
 * ALL of a series' chapters -- not just the ones that went through the queue -- it keeps a cache
 * of `repository.episodesOf(itemId)` per itemId, which only refreshes when the SET of items in
 * the queue changes (not on every progress tick, which arrives several times a second while
 * something is downloading).
 */
class DownloadsViewModel(
    private val manager: LocalDownloadManager,
    private val repository: ArkivRepository,
) : ViewModel() {

    private val downloadRows = manager.observeRows()

    private val episodesByItem = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())

    init {
        viewModelScope.launch {
            downloadRows
                .map { rows -> rows.map { it.itemId }.toSet() }
                .distinctUntilChanged()
                .collect { itemIds ->
                    episodesByItem.value = itemIds.associateWith { repository.episodesOf(it) }
                }
        }
    }

    val groups: StateFlow<List<DownloadGroup>> = combine(
        downloadRows,
        repository.observeLibrary(),
        episodesByItem,
    ) { downloads, library, episodes ->
        val meta = library.associate { it.identifier to DownloadItemMeta(it.title, it.thumbnailUrl, it.source) }
        DownloadGroupPolicy.buildGroups(downloads, episodes, meta)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The user accepted a download that exceeded the size threshold. Legacy of the torrent gate (source removed in this branch's pruning): nothing triggers this state today. */
    fun confirm(episodeId: String) {
        viewModelScope.launch { manager.confirmSize(episodeId) }
    }

    /** Retries a failed download: puts it back in the queue and wakes the worker. */
    fun retry(episodeId: String) {
        viewModelScope.launch { manager.retry(episodeId) }
    }

    /** Stops the download while keeping the partial (can be retried and resumes from where it was). */
    fun cancel(episodeId: String) {
        viewModelScope.launch { manager.cancel(episodeId) }
    }

    fun remove(episodeId: String) {
        viewModelScope.launch { manager.remove(episodeId) }
    }

    /**
     * One-time notice for the user ("ya lo tienes bajado"). Lives here and not in the screen
     * because the case that needs it is exactly the one that leaves NO trace: if the queue skips
     * the download as a duplicate, no row gets created, so the chapter keeps showing as "not
     * downloaded" and the tap looks like it does nothing. The screen shows it and calls [messageShown].
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun messageShown() {
        _message.value = null
    }

    /** Queues a chapter that hadn't been downloaded yet, from the group's expanded row. */
    fun download(episodeId: String, source: String) {
        viewModelScope.launch {
            val outcome = manager.enqueue(episodeId, source)
            if (outcome == EnqueueOutcome.ALREADY_DOWNLOADED) {
                _message.value = DuplicateDownloadPolicy.skippedNotice(1)
            }
        }
    }

    /**
     * Cancels EVERYTHING active in the group (queued + in flight). Goes row by row through
     * [LocalDownloadManager.cancel] -- it's the only path that actually cuts off the worker when
     * the one in flight is one of these (see its KDoc); calling it for the extra queued ones too
     * does nothing odd, because `cancel` already tells which row is running. Without this,
     * "cancel all" would only cross out queue rows and leave the in-progress download running on
     * its own -- the orphan-file bug that already got fixed once.
     */
    fun cancelGroup(group: DownloadGroup) {
        viewModelScope.launch {
            for (episodeId in DownloadGroupPolicy.activeEpisodeIds(group)) manager.cancel(episodeId)
        }
    }

    /** Removes every downloaded/in-progress row of the group (the "not downloaded" ones have nothing to remove). */
    fun removeGroup(group: DownloadGroup) {
        viewModelScope.launch {
            for (episodeId in DownloadGroupPolicy.trackedEpisodeIds(group)) manager.remove(episodeId)
        }
    }

    /** Re-queues only the group's failed chapters. */
    fun retryFailedGroup(group: DownloadGroup) {
        viewModelScope.launch {
            for (episodeId in DownloadGroupPolicy.failedEpisodeIds(group)) manager.retry(episodeId)
        }
    }
}
