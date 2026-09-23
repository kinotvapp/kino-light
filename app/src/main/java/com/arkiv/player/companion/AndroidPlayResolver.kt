package com.arkiv.player.companion

import android.content.Context
import android.content.Intent
import com.arkiv.player.AppGraph
import com.arkiv.player.MainActivity
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID
import com.arkiv.player.playback.PlayerSource

/**
 * Android glue for [CompanionPlayReceiver] on the TV: re-resolve a [CompanionPlayItem] against THIS
 * device's Magis/Caracol session (minting a LOCAL episodeId), and open playback through the
 * existing `ACTION_OPEN_PLAYER` deep link that both roots already route to the player. No stream URL
 * is ever received -- only an identity to re-resolve here.
 *
 * Holds the [AppGraph], not `repository`/`magisSession` directly, so wiring this at app startup does
 * NOT force those lazies (and their `credentialsStore.read()!!`) before activation -- they are read
 * only inside [resolve], which runs when a `play` actually arrives (the app is activated by then).
 */
internal class AndroidPlayResolver(
    private val context: Context,
    private val graph: AppGraph,
) : CompanionPlayReceiver.PlayResolver {

    override suspend fun resolve(item: CompanionPlayItem): CompanionPlayReceiver.PlayOutcome {
        val episodeId: String? = when (item.kind) {
            CompanionPlayItem.KIND_LIVE -> {
                if (!graph.magisSession.hasAccountLinked) return CompanionPlayReceiver.PlayOutcome.Fail("no_link")
                "${PlayerSource.LIVE_PREFIX}${item.liveCode}"
            }
            CompanionPlayItem.KIND_MAGIS -> graph.repository.addMagisSource(
                ref = item.ref, contentId = item.contentId, title = item.title,
                episode = item.episode, posterUrl = item.poster, backdropUrl = item.backdrop,
                episodeTitle = item.episodeTitle, seriesRef = item.seriesRef,
                season = item.season.takeIf { it > 0 },
            )
            CompanionPlayItem.KIND_DITU -> graph.repository.addDituSource(
                ref = item.ref, title = item.title, episode = item.episode,
                posterUrl = item.poster, backdropUrl = item.backdrop,
                episodeTitle = item.episodeTitle, seriesRef = item.seriesRef,
                season = item.season.takeIf { it > 0 },
            )
            else -> return CompanionPlayReceiver.PlayOutcome.Fail("unknown_kind")
        }
        return episodeId?.let { CompanionPlayReceiver.PlayOutcome.Open(it, item.title) }
            ?: CompanionPlayReceiver.PlayOutcome.Fail("resolve_failed")
    }

    /** Brings the player to the front for [episodeId]; the receiver calls this on a resolved play. */
    fun openPlayer(episodeId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            putExtra(EXTRA_EPISODE_ID, episodeId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
