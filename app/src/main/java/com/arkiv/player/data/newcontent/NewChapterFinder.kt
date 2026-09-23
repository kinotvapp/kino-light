package com.arkiv.player.data.newcontent

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.DituEntities
import com.arkiv.player.data.db.ItemDao
import com.arkiv.player.data.gateway.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Looks for new chapters of the series you're watching, when the app opens.
 *
 * Before this, a library series never found out on its own that a chapter came out: archive only
 * refreshed if you opened its detail screen, and web/magis never did (their chapters only came in
 * when you added them by hand from the catalog).
 *
 * It's **opportunistic**: runs in the background, blocks nothing, and fails silently if it fails.
 * No network when the app opens isn't an error to report to anyone -- it's retried on the next
 * launch. That's why no failure here bubbles up: it's logged and moves on to the next series.
 *
 * Who to ask is decided by [SeriesToCheck] and what to request by [MissingChapters]; the caps
 * live there because they're what makes running this every time cheap.
 */
class NewChapterFinder(
    private val repo: ArkivRepository,
    private val itemDao: ItemDao,
    private val gateway: ContentSource,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** Runs one full pass. Returns how many new chapters showed up. */
    suspend fun findNewChapters(): Int = withContext(Dispatchers.IO) {
        val candidates = runCatching { itemDao.seriesWithProgress() }.getOrNull()
            ?.map { SeriesCandidate(it.itemId, it.source, it.ultimoVistoMs, it.episodios) }
            ?: return@withContext 0
        val chosen = SeriesToCheck.choose(candidates, now())
        if (chosen.isEmpty()) return@withContext 0
        Log.i(TAG, "checking ${chosen.size} series out of ${candidates.size} in the library")

        var newCount = 0
        for (series in chosen) {
            newCount += runCatching {
                when (series.source) {
                    "magis" -> checkMagis(series)
                    "ditu" -> checkDitu(series)
                    else -> 0
                }
            }.getOrElse { e ->
                // A series that fails can't take the others down with it.
                Log.i(TAG, "couldn't check ${series.itemId}: ${e.message}")
                0
            }
        }
        Log.i(TAG, "done: $newCount new chapter(s)")
        newCount
    }

    /**
     * Magis is the only source whose chapters the gateway exposes (`MagisCatalog.detail`; the
     * rest respond 422), so it can be asked directly.
     *
     * The saved `ref` **gets re-issued on every portal search** (see `addMagisSource`), so ours
     * can be expired. This failing is expected and not a user error: it's logged at info level
     * and moves on.
     *
     * Asks for `episodesWithSeries` (not `episodes`) so a chapter added here comes out enriched
     * exactly as if it had been added by hand: still, real name, synopsis and the real season from
     * `GatewaySeries`. That last one isn't cosmetic -- it's what avoids the bug that started this
     * block of parameters: a chapter added with no `season` leaves the item with mixed episodes
     * (some with a season set, some without) and `ArkivRepository.ensureEpisodeStills` falls to
     * its flattening branch (see `MagisEntities.chapterEntity`'s KDoc), silently overwriting the
     * whole season's correct stills. If the gateway couldn't resolve TMDB (`GatewaySeries` null),
     * all of this comes out null and the chapter is saved exactly as before: with the portal's
     * data, no still row.
     */
    private suspend fun checkMagis(series: SeriesCandidate): Int {
        val ref = itemDao.getItem(series.itemId)?.torrentData.orEmpty()
        if (ref.isBlank()) return 0
        val (atSource, gatewaySeries) = gateway.episodesWithSeries(ref)
        if (atSource.isEmpty()) {
            Log.i(TAG, "magis ${series.itemId}: no chapters (stale ref?)")
            return 0
        }
        val have = itemDao.getEpisodesOf(series.itemId).mapNotNull { it.episode }
        val toFetch = MissingChapters.toFetch(have, atSource.map { it.number })
        if (toFetch.isEmpty()) return 0

        val item = itemDao.getItem(series.itemId) ?: return 0
        var added = 0
        for (number in toFetch) {
            val ep = atSource.firstOrNull { it.number == number } ?: continue
            val id = repo.addMagisSource(
                ref = ep.ref,
                contentId = contentIdFor(series.itemId) ?: continue,
                title = item.title,
                episode = number,
                posterUrl = item.thumbnailUrl,
                season = gatewaySeries?.seasonNumber,
                // `optInt` in parsing gives 0 if the field were missing, and a 0 isn't null (see
                // the same shielding in SearchPlayback.playMagisSeason).
                tmdbId = gatewaySeries?.tmdbId?.takeIf { it > 0 },
                still = ep.still,
                tmdbTitle = ep.tmdbTitle,
                overview = ep.overview,
            )
            if (id != null) added++
        }
        if (added > 0) Log.i(TAG, "magis ${series.itemId}: +$added")
        return added
    }

    /**
     * Caracol chapters, the same shape as [checkMagis] but season-aware: `gateway.episodesWithSeries`
     * (routed to `DituSource` by `CompositeSource`, since [ref] is a Caracol ref) lists what's on
     * the source today, and [MissingChapters.toFetchBySeason] decides what's actually new.
     *
     * Season-aware on purpose, unlike [checkMagis]'s plain [MissingChapters.toFetch]: Caracol
     * numbers chapters PER SEASON, so comparing against the highest NUMBER stored would make season
     * 2's chapter 1 look like it's already covered by a season 1 with ten chapters.
     *
     * Filters through [DituEntities.saveableChapters] before comparing: a chapter Caracol lists
     * with number 0 can never be saved ([DituEntities.itemContentId] rejects it), so it must
     * never count as missing -- that would retry it forever for nothing.
     *
     * Each missing chapter is saved with [ArkivRepository.addDituSource], which upserts just that
     * one chapter and does NOT re-seal `episodiosVistosEnLista` (unlike `addDituSeason`), so the
     * item's new-chapter badge picks it up -- same as a chapter [checkMagis] adds.
     */
    private suspend fun checkDitu(series: SeriesCandidate): Int {
        val item = itemDao.getItem(series.itemId) ?: return 0
        val ref = item.torrentData.orEmpty()
        if (ref.isBlank()) return 0
        val (atSource, gatewaySeries) = gateway.episodesWithSeries(ref)
        if (atSource.isEmpty()) {
            Log.i(TAG, "ditu ${series.itemId}: no chapters (stale ref?)")
            return 0
        }

        // Same season rule the save path uses, so a chapter is keyed here exactly as it would be
        // once saved.
        val candidates = atSource.map { ep -> DituEntities.caracolChapter(ep, gatewaySeries) }
        val saveable = DituEntities.saveableChapters(ref, candidates)
        if (saveable.isEmpty()) return 0

        // Both sides keyed through DituEntities.savedSeason: the stored `season` column is
        // always written through it (null/0 -> 1), so comparing the raw source season directly
        // would miss a chapter whose source season is null or 0 -- its key would land on `0`,
        // never past a stored high-water mark that's really `1`.
        val have = itemDao.getEpisodesOf(series.itemId)
            .mapNotNull { ep -> ep.episode?.let { DituEntities.savedSeason(ep.season) to it } }
        val inSource = saveable.map { DituEntities.savedSeason(it.season) to it.number }
        val missing = MissingChapters.toFetchBySeason(have, inSource).toSet()
        if (missing.isEmpty()) return 0

        var added = 0
        for (cap in saveable) {
            if (DituEntities.savedSeason(cap.season) to cap.number !in missing) continue
            val id = repo.addDituSource(
                ref = cap.ref,
                title = item.title,
                episode = cap.number,
                posterUrl = item.thumbnailUrl,
                episodeTitle = cap.title,
                seriesRef = ref,
                season = cap.season,
                tmdbId = gatewaySeries?.tmdbId?.takeIf { it > 0 },
            )
            if (id != null) added++
        }
        if (added > 0) Log.i(TAG, "ditu ${series.itemId}: +$added")
        return added
    }

    /**
     * `magis:<contentId>` or `magis:<contentId>:e<n>` → the contentId.
     *
     * Pulled from the identifier instead of stored separately because it's already there: the id
     * was derived from it precisely so it survives refs that expire.
     */
    private fun contentIdFor(itemId: String): String? = itemId
        .removePrefix("magis:")
        .substringBefore(":e")
        .takeIf { it.isNotBlank() && itemId.startsWith("magis:") }

    private companion object {
        const val TAG = "ArkivNewContent"
    }
}
