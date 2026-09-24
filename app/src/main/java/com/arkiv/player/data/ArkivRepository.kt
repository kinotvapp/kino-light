package com.arkiv.player.data

import com.arkiv.player.data.catalog.TmdbApi
import com.arkiv.player.data.catalog.TmdbItem
import com.arkiv.player.data.db.ArkivDatabase
import com.arkiv.player.data.db.ArtworkEntity
import com.arkiv.player.data.db.ContinueRow
import com.arkiv.player.data.db.EpisodeStillEntity
import com.arkiv.player.data.db.LibraryRow
import com.arkiv.player.data.db.PlaybackEntity
import com.arkiv.player.data.model.Episode
import com.arkiv.player.data.model.EpisodeNumbering
import com.arkiv.player.thumbnails.FrameStore
import com.arkiv.player.thumbnails.FrameDestroyer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * Minimum playback to enter "Continue watching". Below this it was opening and closing (or a
 * quick pass over the wrong chapter), not something you're actually watching.
 */
private const val CONTINUE_WATCHING_MIN_MS = 2 * 60 * 1000L

/** An item's detail with each episode's playback progress. */
data class ItemDetail(
    val identifier: String,
    val title: String,
    val description: String?,
    val thumbnailUrl: String,
    val episodes: List<Episode>,
    val progress: Map<String, PlaybackEntity>,
) {
    /**
     * The last episode **touched** and unfinished (the "chapter I'm on"), or null if there's none.
     *
     * The chapter with the most recent REAL playback wins; ones that only have the row
     * [markInProgress] writes on opening them (`positionMs == 0`, no duration yet) are a fallback
     * and only answer if there's no other.
     *
     * Both steps are needed and each fixes a different case:
     *
     * - Without the fallback, playing E5 and leaving after three seconds left the detail saying
     *   "you're on E1": `PlayerViewModel.saveProgress` writes nothing until it knows the duration,
     *   and on Magis the probe can take a while (TS stream), so there's no position to look at yet.
     * - Without preferring the one that DOES have a position, opening a chapter that never got far
     *   enough to play turned it into "where you're at" ahead of one with real progress, just for
     *   being more recent. Measured on Dragon Ball on 2026-08-12: e126 with 3:30 watched lost to
     *   e127 and e128, opened later with their row at 0. And since "Continue watching" DOES filter
     *   by position (`observeContinueWatching`), the two surfaces answered differently: the home
     *   row offered e126 and the detail said "you're on e128".
     *
     * Still no second-count floor, on purpose: a chapter with two seconds played is "where you're
     * at" if it's the last thing you actually played. What's discarded isn't "little progress" but
     * "none".
     */
    val inProgressEpisode: Episode?
        get() = whereYouAreAt?.takeIf { !it.isNext }?.let { chosen -> episodes.find { it.id == chosen.episodeId } }

    /**
     * The rule shared with "Continue watching", resolved against the chapter list this detail
     * already has in memory. See [com.arkiv.player.data.ContinueWatchingRule]: no second-count
     * floor here, because in the detail "where you're at" is where you're at even if you watched
     * two seconds.
     */
    private val whereYouAreAt: ChapterToOffer?
        get() = ContinueWatchingRule.choose(
            episodes.mapNotNull { ep ->
                progress[ep.id]?.let {
                    ChapterProgress(ep.id, it.positionMs, it.watched, it.lastPlayedAt)
                }
            },
            nextIdOf = { id ->
                val i = episodes.indexOfFirst { it.id == id }
                if (i >= 0) episodes.getOrNull(i + 1)?.id else null
            },
        )

    /**
     * Episode for the "Reproducir" button: the one you're watching, or the one that follows the
     * last one you finished.
     *
     * The fallback is NOT "the first unwatched one" plainly: with the whole season saved at once
     * (see `addMagisSeason`), tapping and finishing E5 with no other chapter ever touched leaves
     * E1-E4 and E6-E20 just as "unwatched" as E6, so "the first unwatched one" by order always
     * landed on E1 instead of continuing where you were.
     *
     * "The most advanced watched one IN THE LIST" (by position) isn't enough either: watching E10
     * standalone out of curiosity and then starting in order and finishing E1-E3 would leave
     * "Reproducir" offering E11, skipping E4-E9. The rule is by RECENCY and [ContinueWatchingRule]
     * decides it, the SAME one that builds "Continue watching" on the home: it anchors on the last
     * thing you played (finished or not) and offers that chapter if it was left halfway, or the
     * one that follows it if you finished it. An accepted consequence (not a bug, don't "fix"
     * this): if you finished the whole series and then revisited E1, "Reproducir" starts offering
     * E2 -- that's what someone rewatching expects.
     *
     * The two fallbacks below belong to THIS surface and not the rule: the "Reproducir" button
     * can't be left with no chapter. If nothing was ever watched, it falls back to the first
     * unwatched one; if everything was watched (there's no "next" after the last one finished), it
     * starts over from the first one. The home, on the other hand, prefers not to show the card
     * over offering something you already watched.
     */
    val resumeEpisode: Episode?
        get() = whereYouAreAt?.let { chosen -> episodes.find { it.id == chosen.episodeId } }
            ?: episodes.firstOrNull { progress[it.id]?.watched != true }
            ?: episodes.firstOrNull()
}

/** Single point of access to the data: gateway (magis/TMDB) + persistence (Room). */
class ArkivRepository(
    private val db: ArkivDatabase,
    private val tmdbApi: TmdbApi? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Where each chapter's JPEG lives, to resolve `ContinueRow.framePath` from disk (see
     * `observeContinueWatching`). Nullable with a default so as not to break other call sites:
     * with no store, `framePath` is simply left null and the screens fall back to their usual
     * defaults.
     */
    private val frameStore: FrameStore? = null,
    /**
     * The process's only frame destroyer: `AppGraph` passes in here the same one it builds for the
     * rest of the app (`CloudSyncManager` and `LibraryWiper`, which also used it, were deleted
     * before this branch). The default is for call sites that build a standalone repository
     * (tests, tools) and it builds an equivalent one over the same two things — the store above
     * and this database's DAO.
     */
    private val frameDestroyer: FrameDestroyer =
        FrameDestroyer(frameStore, db.episodeFrameDao()),
) {
    private val itemDao = db.itemDao()
    private val playbackDao = db.playbackDao()
    private val downloadDao = db.downloadDao()
    private val skipMarkerDao = db.skipMarkerDao()
    private val artworkDao = db.artworkDao()
    private val episodeStillDao = db.episodeStillDao()
    private val episodeFrameDao = db.episodeFrameDao()
    private val liveFavoriteDao = db.liveFavoriteDao()
    private val liveRecentDao = db.liveRecentDao()

    /**
     * Called when a chapter JUST finished being watched. `AppGraph` wires this to "Para ti"; the
     * repository knows nothing about recommendations. Whoever receives it has its own 24h gate.
     */
    var onEpisodeFinished: (() -> Unit)? = null

    fun observeLibrary(): Flow<List<LibraryRow>> = itemDao.observeLibrary()

    /**
     * `itemId -> when something from that item was last played`, for the library's order. An item
     * that was never played isn't in the map.
     */
    fun observeLastPlayedAt(): Flow<Map<String, Long>> =
        playbackDao.observeLastPlayed().map { rows ->
            rows.associate { it.itemId to it.ultimaMs }
        }

    /**
     * The library ordered by what you last watched (see
     * [com.arkiv.player.data.library.LibraryOrder]). Consumed by the phone's grid.
     *
     * A separate flow and NOT [observeLibrary]'s order on purpose: that raw query is used by
     * `ensureArtwork`, the downloads screen and the TV home's hero, none of which the reorder
     * benefits. If the order lived in the SQL, the query would end up depending on `playback` and
     * Room would re-emit the whole library every time progress is saved — every few seconds while
     * you're playing —, firing an art pass per row on every emission.
     */
    fun observeLibraryOrdered(): Flow<List<LibraryRow>> =
        combine(observeLibrary(), observeLastPlayedAt()) { rows, lastPlayed ->
            com.arkiv.player.data.library.LibraryOrder.sortedRows(rows, lastPlayed)
        }

    /**
     * The library grouped WITHOUT ordering by what was last watched: just [LibraryGrouping]'s
     * grouping, in `observeLibrary()`'s order of entry (`addedAt DESC`).
     *
     * Private on purpose: the only thing that consumes it is [observeGroupMembers], for which the
     * group list's order is of no use (it resolves the members of ONE key). If it hung off the
     * order by last watched, it would depend on `observeLastPlayedAt()` and would recompute the
     * grouping every time progress is saved on ANY library item, even if the result were identical.
     */
    private fun observeLibraryGroupsUnordered(): Flow<List<LibraryGroup>> =
        LibraryGrouping.groupsFlow(observeLibrary(), observeArtwork())

    /**
     * The library already grouped: one entry per series, not per acquisition. See [LibraryGrouping].
     * `observeLibrary()` still exists for whoever needs the raw rows (the phone's library screen,
     * sync).
     *
     * The final order is by what was last watched ([com.arkiv.player.data.library.LibraryOrder]),
     * not by date added: [LibraryGrouping.group]'s `sortedByDescending` is left as the tiebreaker,
     * because Kotlin's ordering is stable.
     */
    fun observeLibraryGroups(): Flow<List<LibraryGroup>> =
        combine(
            observeLibraryGroupsUnordered(),
            observeLastPlayedAt(),
        ) { groups, lastPlayed ->
            com.arkiv.player.data.library.LibraryOrder.sortedGroups(groups, lastPlayed)
        }

    /**
     * The items behind a group key, from most complete to least.
     *
     * ALSO accepts a raw identifier: "Continue watching", the long-press menu and the phone's
     * detail navigate with the item's identifier, not with a group key. And it accepts an
     * `item:<identifier>` key that stopped being a live group because its item got added to
     * another group WHILE the detail was open (`ensureArtwork` resolving it a tv tmdbId in the
     * background): there it follows the item to its new group instead of returning empty. See
     * [LibraryGrouping.resolveMembers]. Only returns empty if there's truly nothing with that
     * identifier (e.g. if the only source got deleted while the detail was open).
     *
     * A single subscription to the library: the rows are derived from [groups]'s members (which
     * already comes from `observeLibrary()` via [observeLibraryGroupsUnordered]) instead of
     * combining `observeLibrary()` again separately here.
     *
     * Hangs off [observeLibraryGroupsUnordered], NOT [observeLibraryGroups], on purpose: this
     * resolves the members of a SINGLE group key, so the full group list's order doesn't affect it
     * at all. Hanging it off the order by last watched would make the detail recompute the
     * grouping every time progress is saved on any library item, even if the result for this key
     * didn't change. Don't "unify" this with [observeLibraryGroups] without reading this comment
     * again.
     */
    fun observeGroupMembers(groupKey: String): Flow<List<LibraryRow>> =
        observeLibraryGroupsUnordered().map { groups ->
            LibraryGrouping.resolveMembers(groupKey, groups, groups.flatMap { it.members })
        }

    /**
     * "Continue watching", with each chapter's captured frame if it's already on disk.
     *
     * The `combine` with `episodeFrameDao.observeAll()` contributes no data —the second value is
     * discarded— but INVALIDATION: the `playback` query doesn't touch `episode_frame`, so without
     * this Room wouldn't re-emit anything when [com.arkiv.player.thumbnails.FrameCapturer]
     * published a JPEG and the card stayed on the TMDB still.
     */
    fun observeContinueWatching(): Flow<List<ContinueRow>> =
        combine(
            playbackDao.observeProgressWithNext(),
            episodeFrameDao.observeAll(),
        ) { rows, _ -> rows }.map { rows ->
            // Which chapter goes for each series is decided by ContinueWatchingRule, the pure and
            // tested part (one card per item, ordered by what you last played). This just
            // translates the database row into what that rule understands.
            ContinueWatchingRule.byItem(
                rows.map {
                    ItemProgress(
                        itemId = it.itemId,
                        progress = ChapterProgress(
                            episodeId = it.episodeId,
                            positionMs = it.positionMs,
                            watched = it.watched,
                            lastPlayedAt = it.lastPlayedAt,
                        ),
                        nextEpisodeId = it.siguienteEpisodeId,
                    )
                },
                minPositionMs = CONTINUE_WATCHING_MIN_MS,
            )
        }.map { choices ->
            if (choices.isEmpty()) return@map emptyList()
            // The IN doesn't preserve order and the choice can point at a chapter with no playback
            // row, so it gets reordered here and lastPlayedAt is overwritten with the anchor's: the
            // offered chapter may never have been played, but the series was, and it's the series
            // that has to be at the top of the row.
            val byId = playbackDao.continueWatchingRows(choices.map { it.episodeId })
                .associateBy { it.episodeId }
            choices.mapNotNull { choice ->
                byId[choice.episodeId]?.copy(lastPlayedAt = choice.lastPlayedAt)
            }
        }.map { rows ->
            // framePath does NOT come from the query (see the field's doc in ContinueRow): it's
            // resolved here, from disk, after the dedup/take(20) above so as not to spend extra
            // File.exists() calls on rows that won't even be shown. It's ~6 rows per emission:
            // negligible.
            rows.map { it.copy(framePath = frameStore?.pathIfExists(it.episodeId)) }
        }

    /**
     * What's already watched, per item. The cross against the library's groups is done by
     * [com.arkiv.player.data.library.LibraryWatched], the pure and tested part.
     */
    fun observeWatchedItems(): Flow<List<com.arkiv.player.data.library.ItemWatched>> =
        playbackDao.observeWatched().map { rows ->
            rows.map { com.arkiv.player.data.library.ItemWatched(it.itemId, it.episodios, it.ultimoVistoMs) }
        }

    // --- TMDB art (local, not synced) -----------------------------------------------

    /** Map itemId -> resolved TMDB art, to paint backdrops on the home. */
    fun observeArtwork(): Flow<Map<String, ArtworkEntity>> =
        artworkDao.observeAll().map { list -> list.associateBy { it.itemId } }

    /**
     * Resolves TMDB backdrops for the items that don't have art yet. Sequential and best-effort:
     * items with no match are left with an empty row so as not to re-search them every time. Does
     * nothing if there's no [tmdbApi] (the parameter is nullable, defaulting to `null`).
     */
    suspend fun ensureArtwork(rows: List<LibraryRow>) {
        val tmdb = tmdbApi ?: return
        for (row in rows) {
            // Art that's ALREADY resolved (tmdbId) or that ALREADY has backdrops even with no
            // tmdbId (the portal's backdrop that addMagisSource saves) never gets requested again:
            // those are the two cases where there's already something good to lose. An empty one
            // of either kind DOES get retried, but only if the row is old: that way a title TMDB
            // doesn't know isn't queried on every startup, and at the same time items that failed
            // because of a dirty title (see cleanTitleForSearch) recover on their own after an
            // update. See LibraryGrouping.shouldRefetchArtwork for the rule's detail.
            val existing = artworkDao.get(row.identifier)
            if (!LibraryGrouping.shouldRefetchArtwork(existing, clock())) continue
            val type = if (row.isMovie) "movie" else "tv"
            val match = searchTmdbMatch(tmdb, type, row.title)
            val backdrops = if (match != null) {
                runCatching { tmdb.images(type, match.item.id) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            artworkDao.upsert(
                ArtworkEntity(
                    itemId = row.identifier,
                    // The id is saved regardless of whether the match was by fallback: it's good
                    // enough for the art.
                    tmdbId = match?.item?.id,
                    // The TYPE only if the match was exact, because that's what [LibraryGrouping]
                    // requires to merge two rows into one card. A "close enough" id gives an
                    // acceptable backdrop; a "close enough" identity merges different works.
                    tmdbType = match?.takeIf { it.exact }?.let { type },
                    backdropsJson = JSONArray(backdrops).toString(),
                    fetchedAt = clock(),
                ),
            )
        }
    }

    /**
     * Searches TMDB for an item's title: first with the cleaned title and, if that gives nothing,
     * with the raw one. The choice between results is [pickTmdbMatch]'s, NOT the first one to arrive.
     *
     * null = no match, and that includes "the network went down": whoever calls it decides whether
     * that means "save empty" (ensureArtwork, which retries after 7 days) or "touch nothing"
     * ([repairArtworkMatches], which has good art to lose).
     */
    private suspend fun searchTmdbMatch(tmdb: TmdbApi, type: String, title: String): TmdbMatch? {
        val cleaned = cleanTitleForSearch(title)
        return runCatching { pickTmdbMatch(cleaned, tmdb.search(type, cleaned)) }.getOrNull()
            ?: runCatching { pickTmdbMatch(title, tmdb.search(type, title)) }.getOrNull()
    }

    /** That the art repair runs ONCE per process: there's a HomeViewModel per screen. */
    private val artworkRepairRan = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Repairs, in a single pass, the art that got resolved BEFORE [pickTmdbMatch] existed.
     *
     * Needed because fixing the match's choice doesn't repair what's already saved: `ensureArtwork`
     * skips any row that already has a `tmdbId` (see [LibraryGrouping.shouldRefetchArtwork]), so
     * titles left pointing at the more popular sibling —the three Dragon Ball entries with Dragon
     * Ball Z's `tmdbId`— would stay that way forever.
     *
     * Two precautions, both against destroying good art:
     *  - Only rows WITH `tmdbId`, which are the ones `ensureArtwork` resolved by searching by
     *    title. A row with backdrops but no `tmdbId` is art the portal put there
     *    (`addMagisSource`) and never gets touched.
     *  - If the search returns nothing, the row is left **as-is**. Without this, a pass with the
     *    network down would wipe the whole library's art at once.
     *
     * Returns true only if the pass completed entirely; false if something failed and it's worth
     * retrying on the next startup.
     */
    suspend fun repairArtworkMatches(rows: List<LibraryRow>): Boolean {
        if (!artworkRepairRan.compareAndSet(false, true)) return false
        val tmdb = tmdbApi ?: return false
        var complete = true
        for (row in rows) {
            val existing = artworkDao.get(row.identifier) ?: continue
            val stored = existing.tmdbId ?: continue
            val type = if (row.isMovie) "movie" else "tv"
            val match = searchTmdbMatch(tmdb, type, row.title)
            if (match == null) {
                complete = false
                continue
            }
            val typeIfExact = type.takeIf { match.exact }
            if (match.item.id == stored && existing.tmdbType == typeIfExact) continue
            val backdrops = runCatching { tmdb.images(type, match.item.id) }.getOrNull()
            if (backdrops == null) {
                complete = false
                continue
            }
            artworkDao.upsert(
                ArtworkEntity(
                    itemId = row.identifier,
                    tmdbId = match.item.id,
                    // Same rule as in ensureArtwork: the repair can't PROMOTE a fallback match to
                    // an identity one, which is exactly what this change fixes.
                    tmdbType = typeIfExact,
                    backdropsJson = JSONArray(backdrops).toString(),
                    fetchedAt = clock(),
                ),
            )
        }
        return complete
    }

    // --- Chapter stills (TMDB, local and not synced) --------------------------------

    /** Map episodeId -> the still's URL, to paint each chapter's real thumbnail. */
    fun observeEpisodeStills(itemId: String): Flow<Map<String, String>> =
        episodeStillDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> r.stillUrl?.let { r.episodeId to it } }.toMap()
        }

    /**
     * Map episodeId -> on-disk path of the captured frame, so a series' detail paints the real
     * scene instead of TMDB's still. Same mechanism as [observeContinueWatching]
     * (`ContinueRow.framePath`), but here the trigger is a Room query instead of a
     * `List<ContinueRow>` already in memory.
     *
     * Deliberate SUBTLETY: the `episode_frame` row is used only as a TRIGGER (Room notifies the
     * Flow when a row changes; disk notifies nothing), and the path itself ALWAYS comes from
     * `frameStore.pathIfExists`, same as on the home — it's the only source of truth for where the
     * JPEG is. Accepted consequence: if the JPEG was ever saved but the row's write failed (or
     * vice versa), the detail wouldn't show it even though the home would. It's a rare case (the
     * row and file writes are part of the same capture) and it self-corrects with the chapter's
     * next capture.
     */
    fun observeEpisodeFrames(itemId: String): Flow<Map<String, String>> =
        episodeFrameDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> frameStore?.pathIfExists(r.episodeId)?.let { r.episodeId to it } }.toMap()
        }

    /**
     * Resolves a series' chapter stills from TMDB and caches them.
     *
     * Idempotent: if every chapter already has a row (even with a null `stillUrl` because TMDB had
     * no image) nothing gets requested again. Works the same for series and anime — on TMDB both
     * are `tv`, which is what [ensureArtwork] already resolves.
     *
     * **Doesn't own the table**: Magis writes the same rows when saving the season, with what the
     * gateway already crossed against TMDB. That's why this function never overwrites a whole row
     * — it merges field by field ([StillMerge]) and doesn't mark as "already asked" what
     * couldn't be asked. Without that, opening the detail with the network down would wipe out
     * what Magis had saved correctly and it would never be retried again.
     */
    suspend fun ensureEpisodeStills(itemId: String) {
        val tmdb = tmdbApi ?: return
        // The item's own tmdbId wins over `artwork`'s: that one gets resolved by searching TMDB by
        // title (a guess that can land on another series), while the item's was set by whoever
        // added it from search, who knew exactly which one it was.
        val ownTmdbId = itemDao.getItem(itemId)?.tmdbId
        val tvId = ownTmdbId ?: artworkDao.get(itemId)
            ?.let { art -> art.tmdbId?.takeIf { art.tmdbType == "tv" } }
            ?: return

        val episodes = itemDao.getEpisodesOf(itemId)
        if (episodes.isEmpty()) return
        // The rows that already exist, COMPLETE and not just their ids: used twice — for the early
        // cutoff below and so as not to overwrite with null what another source had already filled
        // in (see [StillMerge]).
        val previous = episodeStillDao.forItem(itemId).associateBy { it.episodeId }
        if (previous.keys.containsAll(episodes.map { it.id })) return

        // Chapter -> (season, episode). Three ways, from most to least reliable.
        val coords: Map<String, Pair<Int, Int>> = if (episodes.all { it.season != null && it.episode != null }) {
            // The file name declared the numbering (sNNeNN / NxNN): it's exact, and doesn't get
            // misaligned even if the local copy brings OVAs, recaps, or is missing chapters.
            episodes.associate { it.id to (it.season!! to it.episode!!) }
        } else if (episodes.any { EncodedNumbering.coordinates(it.itemId, it.section, it.orderIndex) != null }) {
            // Torrent and web: orderIndex carries the encoded numbering. Which source brings it and
            // which doesn't is decided by the row's source, not by the number going over 1000 —
            // looking at the number left season 0 out (the specials, which give less than 1000) and
            // sent those series down the count-based distribution branch, which gave them another
            // chapter's still.
            episodes.mapNotNull { ep ->
                EncodedNumbering.coordinates(ep.itemId, ep.section, ep.orderIndex)?.let { ep.id to it }
            }.toMap()
        } else {
            // Archive: flat 1..N list with no seasons. TMDB's are flattened in order and
            // distributed by count (with 25+24, chapter 26 lands on S2E1). If the local copy has
            // OVAs or recaps interspersed, this distribution gets misaligned.
            val seasons = tmdb.detail("tv", tvId)?.seasons
                ?.filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                ?.sortedBy { it.seasonNumber }
                .orEmpty()
            if (seasons.isEmpty()) return
            val flat = seasons.flatMap { s -> (1..s.episodeCount).map { s.seasonNumber to it } }
            episodes.sortedBy { it.orderIndex }
                .mapIndexedNotNull { i, ep -> flat.getOrNull(i)?.let { ep.id to it } }
                .toMap()
        }
        if (coords.isEmpty()) return

        // One call per season, not per chapter.
        val stillBySeasonEp = mutableMapOf<Pair<Int, Int>, String>()
        val titleBySeasonEp = mutableMapOf<Pair<Int, Int>, String>()
        val overviewBySeasonEp = mutableMapOf<Pair<Int, Int>, String>()
        // Seasons whose TMDB query went down (network, rate-limit, 5xx). Different from "TMDB
        // answered and had nothing": the latter DOES get written, so as not to re-ask forever.
        val failed = mutableSetOf<Int>()
        for (season in coords.values.map { it.first }.distinct().sorted()) {
            // Double null: `seasonEpisodes`'s (the query couldn't be made) and `runCatching`'s
            // (unexpected exception). Both mean "couldn't be asked".
            val eps = runCatching { tmdb.seasonEpisodes(tvId, season) }.getOrNull()
            if (eps == null) {
                // Without this cutoff, a failure got written as an empty row —indistinguishable
                // from "TMDB answered and had nothing"—, the early cutoff above returned true
                // forever, and a single timeout left that series with no images or names until the
                // app was reinstalled.
                failed += season
                continue
            }
            eps.forEach { e ->
                if (e.stillUrl.isNotBlank()) stillBySeasonEp[e.season to e.episode] = e.stillUrl
                if (e.name.isNotBlank()) titleBySeasonEp[e.season to e.episode] = e.name
                // Same table Magis fills (`MagisEntities.seasonStills`): the per-chapter
                // synopsis isn't a single source's privilege, both write `episode_still` and the
                // UI reads a single place.
                if (e.overview.isNotBlank()) overviewBySeasonEp[e.season to e.episode] = e.overview
            }
        }

        // ALL chapters get written, including the ones with no still: the row marks "already
        // asked" and avoids repeating the query on every time the series opens. With two
        // exceptions, both for the same reason —a row written here is read as a definitive
        // answer—:
        //  1. Those of a season that couldn't even be queried, so it gets retried.
        //  2. The fields this query didn't bring, which keep whatever was already saved (typically:
        //     what Magis left on saving the season). See [StillMerge].
        val now = clock()
        episodeStillDao.upsertAll(
            episodes
                .filter { ep -> coords[ep.id]?.first?.let { it !in failed } ?: true }
                .map { ep ->
                    StillMerge.merge(
                        previous = previous[ep.id],
                        updated = EpisodeStillEntity(
                            episodeId = ep.id,
                            stillUrl = coords[ep.id]?.let { stillBySeasonEp[it] },
                            fetchedAt = now,
                            title = coords[ep.id]?.let { titleBySeasonEp[it] },
                            overview = coords[ep.id]?.let { overviewBySeasonEp[it] },
                        ),
                    )
                },
        )
    }

    /**
     * Map episodeId -> the chapter's title per TMDB, to show it instead of the file name
     * ("s01e03"). Only brings the ones TMDB knew; the rest don't show up and the UI falls back to
     * the file name.
     */
    fun observeEpisodeTitles(itemId: String): Flow<Map<String, String>> =
        episodeStillDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> r.title?.let { r.episodeId to it } }.toMap()
        }

    /**
     * Map episodeId -> the chapter's synopsis per TMDB. Filled in both by Magis
     * (`addMagisSeason`/`addMagisSource`) and by [ensureEpisodeStills] for everything else (Ditu
     * today, and legacy torrent/web/archive rows): any series with a `tmdbId` has it, it isn't a
     * single source's privilege.
     */
    fun observeEpisodeOverviews(itemId: String): Flow<Map<String, String>> =
        episodeStillDao.observeForItem(itemId).map { rows ->
            rows.mapNotNull { r -> r.overview?.let { r.episodeId to it } }.toMap()
        }

    fun observeDownloadRows() = downloadDao.observeDownloadRows()

    // `completedDownloadUri` was removed: it looked ONLY at the `localUri` column (the one the
    // system's DownloadManager filled) and ignored `filePath`, which is where new downloads write,
    // so it returned null for everything downloaded with the worker. It also didn't check that the
    // file still existed. Now there's ONE resolver of "where's the local file?" for all three
    // sources: `LocalLibrary.fileFor`, which covers both columns, checks exists() and cleans up the
    // row if the file is gone.

    /** Sets the type by hand: true = movie, false = series, null = automatic detection. */
    suspend fun setCategory(itemId: String, isMovie: Boolean?) {
        val value = when (isMovie) {
            true -> "movie"
            false -> "series"
            null -> null
        }
        itemDao.updateCategoryOverride(itemId, value)
    }

    /** Renames a library item (the title is display-only; doesn't change its identifier). */
    suspend fun renameItem(itemId: String, title: String) {
        val clean = title.trim()
        if (clean.isBlank()) return
        itemDao.updateTitle(itemId, clean, clock())
    }

    /**
     * Records that the user has already seen this item's chapter list, which is what turns off
     * the "new chapters" badge.
     *
     * Called when the detail screen opens, as soon as the identifier to show is resolved. See
     * `NewEpisodeCounter`.
     */
    suspend fun markChaptersSeen(identifier: String) {
        val count = itemDao.getEpisodesOf(identifier).count { !it.deleted }
        itemDao.markEpisodesSeen(identifier, count)
    }

    /**
     * Saves a Magis result so it can be played and resumed.
     *
     * The id is derived from the portal's `contentId`, NOT from the ref: the ref gets re-emitted
     * on every search and an id derived from it would lose the "I'm here" mark every time. The ref
     * is saved separately (the same field where web saves its `pageUrl`) and gets refreshed on
     * finding it again.
     *
     * [episode] > 0 = a series chapter: goes as an episode INSIDE the season's item and marks it
     * as a series from the first one on (see [MagisEntities] for why). 0 = movie, the usual path,
     * which replaces the whole item.
     *
     * [seriesRef] is the SEASON's ref (the one used to ask the portal for the chapter list),
     * different from the [ref] of the chapter that's about to play.
     *
     * [season], [tmdbId], [still], [tmdbTitle] and [overview] are what `GatewaySeries`/
     * `GatewayEpisode` bring when the caller has them on hand (today, `NewChapterFinder.checkMagis`
     * when adding a new chapter in the background): a chapter that comes out this way ends up
     * enriched the same as if it had been opened by hand, with nobody having to open the season.
     * All optional for the other callers, which don't know them.
     */
    suspend fun addMagisSource(
        ref: String,
        contentId: String,
        title: String,
        episode: Int = 0,
        posterUrl: String = "",
        backdropUrl: String = "",
        episodeTitle: String = "",
        seriesRef: String = "",
        season: Int? = null,
        tmdbId: Int? = null,
        still: String? = null,
        tmdbTitle: String? = null,
        overview: String? = null,
        tituloCanonico: String? = null,
    ): String? {
        if (ref.isBlank() || contentId.isBlank()) return null
        val id = MagisEntities.itemIdFor(contentId)
        val existing = itemDao.getItem(id)
        val (item, ep) = MagisEntities.build(
            contentId = contentId, ref = ref, title = title, episode = episode,
            episodeTitle = episodeTitle, posterUrl = posterUrl, now = clock(),
            seriesRef = seriesRef, existing = existing, season = season, tmdbId = tmdbId,
            tituloCanonico = tituloCanonico,
        )
        if (episode > 0) {
            // upsert and NOT replaceItem: chapters already saved for this season can't be deleted
            // to fit the new one in.
            itemDao.upsertItem(item)
            itemDao.upsertEpisodes(listOf(ep))
            sweepLegacyChapterItem(contentId, episode)
            // Reuses `seasonStills` (the same "brings something" filter and the same episodeId
            // calculation `addMagisSeason` uses for the whole season) instead of duplicating that
            // logic here for a single chapter.
            saveMagisStills(
                id,
                MagisEntities.seasonStills(
                    id, listOf(SeasonChapter(episode, episodeTitle, ref, still, tmdbTitle, overview)), clock(),
                ),
            )
        } else {
            itemDao.replaceItem(item, listOf(ep))
        }
        saveMagisBackdrop(id, backdropUrl)
        return ep.id
    }

    /**
     * Saves a Caracol title to the library and returns the episodeId to play, or null if it isn't
     * something that can be saved (see [DituEntities.itemContentId]: a ref that isn't Caracol's,
     * or a series with no chapter chosen).
     *
     * A copy of [addMagisSource]: [episode] > 0 = a chapter, which goes as an episode INSIDE its
     * series' item (upsert: chapters already there don't get deleted); 0 = movie, which replaces
     * the whole item. The difference is that here what's saved doesn't expire: Caracol's ref
     * encodes stable ids (see `DituRef`), and it's kept in the episode's `torrentData`, which is
     * where [magisRefForEpisode] reads it from when `PlayerViewModel.loadDitu` is about to play it.
     *
     * [seriesRef] is the SERIES' ref (the one that lists its chapters), different from the [ref]
     * of the chapter that's about to play.
     */
    suspend fun addDituSource(
        ref: String,
        title: String,
        episode: Int = 0,
        posterUrl: String = "",
        backdropUrl: String = "",
        episodeTitle: String = "",
        seriesRef: String = "",
        season: Int? = null,
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): String? {
        val contentId = DituEntities.itemContentId(ref, seriesRef, episode) ?: return null
        val id = DituEntities.itemIdFor(contentId)
        val existing = itemDao.getItem(id)
        val (item, ep) = DituEntities.build(
            contentId = contentId, ref = ref, title = title, episode = episode,
            episodeTitle = episodeTitle, posterUrl = posterUrl, now = clock(),
            seriesRef = seriesRef, existing = existing, season = season, tmdbId = tmdbId,
            tituloCanonico = tituloCanonico,
        )
        if (episode > 0) {
            itemDao.upsertItem(item)
            itemDao.upsertEpisodes(listOf(ep))
        } else {
            itemDao.replaceItem(item, listOf(ep))
        }
        // The name says Magis, but all it does is write the landscape image into `artwork` if one
        // arrived: it has nothing Magis-specific.
        saveMagisBackdrop(id, backdropUrl)
        return ep.id
    }

    /**
     * Saves Caracol's WHOLE series and returns the [chosen] chapter's episodeId, to play it. This
     * is what runs when a chapter is tapped to watch it, with the list the chapter window already
     * had loaded (no network). A copy of [addMagisSeason].
     *
     * `upsert` and NOT `replaceItem`: a chapter you already had saved has to survive even if
     * Caracol doesn't list it this time. It's idempotent (ids derived from the content, and the
     * same ones [addDituSource] builds for a standalone chapter: see [DituEntities.buildSeries]),
     * so it can be called on every playback with nothing duplicated.
     *
     * A SINGLE write on the item, for the same reason as in [addMagisSeason]:
     * `episodiosVistosEnLista` gets re-sealed HERE, before writing, against the UNION of the
     * chapters already saved with the ones arriving, and the item comes out sealed from the single
     * `upsertItem`.
     *
     * Only [DituEntities.saveableChapters]'s chapters get in (the guard from
     * [DituEntities.itemContentId]): a ref that isn't Caracol's never ends up with a `ditu:` id.
     *
     * Doesn't carry [addMagisSeason]'s legacy bits (the per-chapter item sweep, the movie-shaped
     * ghost episode, the stills): Caracol has no old rows in this branch, and `DituSource` builds
     * its chapters with no still.
     *
     * Null if nothing got saved, or if [chosen] didn't end up saved with its ref (see
     * [DituEntities.chosenAmong]): the caller falls back to saving the chapter alone.
     */
    suspend fun addDituSeason(
        seriesRef: String,
        title: String,
        chapters: List<CaracolChapter>,
        chosen: CaracolChapter,
        posterUrl: String = "",
        backdropUrl: String = "",
        // `DituSource` leaves tmdbId at 0 when TMDB didn't find it: the caller passes null so it
        // doesn't overwrite one already saved.
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): String? {
        val saveable = DituEntities.saveableChapters(seriesRef, chapters)
        val contentId = saveable.firstNotNullOfOrNull {
            DituEntities.itemContentId(it.ref, seriesRef, it.number)
        } ?: return null
        val id = DituEntities.itemIdFor(contentId)
        val existing = itemDao.getItem(id)
        // The badge is for chapters that came out on Caracol, not for the ones you just saved
        // yourself: it gets re-sealed to the total that's going to be left after the upsert (what
        // was already there, plus what's arriving).
        val live = itemDao.getEpisodesOf(id).map { it.id }.toSet()
        val newIds = saveable.map { DituEntities.chapterId(id, it.season, it.number) }.toSet()
        val episodiosVistosEnLista = com.arkiv.player.data.newcontent.NewEpisodeCounter.reseal(
            existing?.episodiosVistosEnLista,
            (live + newIds).size,
        )
        val series = DituEntities.buildSeries(
            contentId = contentId, seriesRef = seriesRef, title = title, chapters = saveable,
            chosen = chosen, posterUrl = posterUrl, now = clock(), existing = existing,
            episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId,
            tituloCanonico = tituloCanonico,
        )
        itemDao.upsertItem(series.item)
        itemDao.upsertEpisodes(series.episodes)
        saveMagisBackdrop(id, backdropUrl)
        return series.chosenId
    }

    /**
     * Saves a plugin movie and returns its episodeId, or null if [ref] isn't a plugin movie ref.
     * Modeled on [addDituSource]; rows come from [PluginEntities].
     */
    suspend fun addPluginMovie(ref: String, title: String, posterUrl: String = "", backdropUrl: String = ""): String? {
        val itemId = PluginEntities.movieItemId(ref) ?: return null
        val existing = itemDao.getItem(itemId)
        val (item, ep) = PluginEntities.buildMovie(ref, title, posterUrl, clock(), existing) ?: return null
        itemDao.replaceItem(item, listOf(ep))
        saveMagisBackdrop(itemId, backdropUrl)
        return ep.id
    }

    /**
     * Saves a plugin series with every chapter the list already loaded and returns the chosen
     * chapter's episodeId (null if it couldn't be saved). Modeled on [addDituSeason].
     */
    suspend fun addPluginSeason(
        seriesRef: String,
        title: String,
        chapters: List<PluginChapter>,
        chosen: PluginChapter,
        posterUrl: String = "",
        backdropUrl: String = "",
        tmdbId: Int? = null,
        tituloCanonico: String? = null,
    ): String? {
        val itemId = PluginEntities.seriesItemId(seriesRef) ?: return null
        val existing = itemDao.getItem(itemId)
        val saveable = PluginEntities.saveableChapters(seriesRef, chapters)
        val live = itemDao.getEpisodesOf(itemId).map { it.id }.toSet()
        val newIds = saveable.map { PluginEntities.chapterId(itemId, it.season.coerceAtLeast(1), it.number) }.toSet()
        val seen = com.arkiv.player.data.newcontent.NewEpisodeCounter.reseal(existing?.episodiosVistosEnLista, (live + newIds).size)
        val series = PluginEntities.buildSeries(
            seriesRef, title, chapters, chosen, posterUrl, clock(), existing, seen, tmdbId, tituloCanonico,
        ) ?: return null
        itemDao.upsertItem(series.item)
        itemDao.upsertEpisodes(series.episodes)
        saveMagisBackdrop(itemId, backdropUrl)
        return series.chosenId
    }

    /**
     * The ref to ask the gateway for the identity of a Magis item saved with none, or null if
     * there's nothing to repair. The rule lives in [MagisEntities.refToRepair]; this just reads
     * the row.
     */
    suspend fun magisRefToRepair(itemId: String): String? {
        val row = itemDao.getItem(itemId) ?: return null
        return MagisEntities.refToRepair(row.identifier, row.tmdbId, row.torrentData)
    }

    /**
     * Gives a Magis item the identity the gateway can now resolve: the series' `tmdbId` and
     * whatever TMDB knows about each chapter.
     *
     * The stills go through [saveMagisStills], the same (and only) write point
     * `addMagisSeason`/`addMagisSource` use, so the merge that doesn't overwrite what's already
     * saved is respected. Doesn't touch the episodes or the rest of the item: this repairs
     * metadata, it doesn't rewrite the library.
     */
    suspend fun applyMagisIdentity(
        itemId: String,
        tmdbId: Int?,
        chapters: List<SeasonChapter>,
        tituloCanonico: String? = null,
    ) {
        val row = itemDao.getItem(itemId) ?: return
        // A single write for both things: they're the same gateway response, and two `upsertItem`
        // calls on the same row in the same second is exactly what makes the sync trigger recurse
        // (see `SyncTriggers`, and the same care in `MagisEntities.buildSeason`).
        val name = tituloCanonico?.trim()?.takeIf { it.isNotEmpty() }
        val hasNewIdentity = tmdbId != null && tmdbId > 0 && row.tmdbId != tmdbId
        val hasNewName = name != null && name != row.tituloCanonico
        if (hasNewIdentity || hasNewName) {
            itemDao.upsertItem(
                row.copy(
                    tmdbId = if (hasNewIdentity) tmdbId else row.tmdbId,
                    tituloCanonico = name ?: row.tituloCanonico,
                    updatedAt = clock(),
                ),
            )
        }
        if (chapters.isNotEmpty()) {
            saveMagisStills(itemId, MagisEntities.seasonStills(itemId, chapters, clock()))
        }
    }

    /**
     * Writes to `episode_still` what Magis brought, **without overwriting what was already there**
     * ([StillMerge]).
     *
     * The only write point of that table from Magis ([addMagisSource] and [addMagisSeason]), and
     * that's why there aren't two versions of this rule. `EpisodeStillDao.upsertAll` is a REPLACE,
     * so sending straight what `MagisEntities.seasonStills` returns would rewrite the WHOLE
     * row: that list leaves null every field the gateway didn't resolve, and it's enough for one
     * of the three to bring something to include the row. Concretely: the season gets saved, the
     * detail opens and [ensureEpisodeStills] fills in the name and synopsis of a chapter the
     * gateway hadn't crossed; then that chapter gets marked and "Guardar" is tapped, the gateway
     * returns only the still and —without the merge— name and synopsis were silently lost. Worse
     * still: since the row kept existing, [ensureEpisodeStills]'s early cutoff prevented ever
     * filling them in again.
     *
     * Reading the previous rows is done ONLY if there's something to write: the most common case
     * is the unenriched season, and there this doesn't touch the database.
     */
    private suspend fun saveMagisStills(itemId: String, new: List<EpisodeStillEntity>) {
        if (new.isEmpty()) return
        val previous = episodeStillDao.forItem(itemId).associateBy { it.episodeId }
        episodeStillDao.upsertAll(StillMerge.mergeAll(previous, new))
    }

    /**
     * The portal's landscape image goes to the same place the Home's hero looks for TMDB's. Only
     * written if Magis brought it: a row with backdrops —even with a null tmdbId, like here—
     * ensureArtwork no longer touches (see LibraryGrouping.shouldRefetchArtwork), so this portal
     * backdrop doesn't get overwritten with a "[]" every time the retry window passes. With no row
     * (empty backdropUrl), TMDB fills it in as always.
     */
    private suspend fun saveMagisBackdrop(itemId: String, backdropUrl: String) {
        if (backdropUrl.isBlank()) return
        artworkDao.upsert(
            com.arkiv.player.data.db.ArtworkEntity(
                itemId = itemId,
                tmdbId = null,
                tmdbType = null,
                backdropsJson = JSONArray(listOf(backdropUrl)).toString(),
                fetchedAt = clock(),
            ),
        )
    }

    /**
     * Saves Magis's ENTIRE season: this is what runs when a chapter is tapped to play it, with
     * the list the screen already had loaded (no network). The torrent and web sources had the
     * same pattern (saving the whole season at once), but they were removed in this branch's
     * pruning — Magis was the only source that saved one chapter at a time.
     *
     * `upsert` and NOT `replaceItem`: a chapter you already had saved has to survive even if the
     * portal doesn't list it this time. It's idempotent (ids derived from the content), so it can
     * be called on every playback.
     *
     * A SINGLE write on the item (`upsertItem`), not two. It used to be `upsertItem` and then a
     * point UPDATE (`markEpisodesSeen`) to fix the badge — two writes to the same row in the same
     * call, and if they landed in the same second the sync trigger (`SyncTriggers`) recursed until
     * "too many levels of trigger recursion": the app crashed after saving the season but before
     * navigating to the player. The trigger no longer recurses (see `SyncTriggers`), but the second
     * write kept dirtying sync for no reason, so it's also removed: the post-save total is
     * computed HERE (before writing anything) and passed to [MagisEntities.buildSeason] already
     * resolved, so the item comes out sealed from the single `upsertItem`.
     *
     * The total is NOT `chapters.size`: it's the UNION of the chapters already saved with the ones
     * the portal brings (see the why of the `upsert` above), so it's computed against what's
     * already in the database before writing the new season.
     *
     * Returns `chapter number → episodeId` so the caller knows which one to play without
     * re-deriving ids by hand.
     */
    suspend fun addMagisSeason(
        contentId: String,
        title: String,
        chapters: List<SeasonChapter>,
        seriesRef: String,
        posterUrl: String = "",
        backdropUrl: String = "",
        // Null when TMDB didn't resolve this series (or the gateway hasn't sent it yet):
        // `buildSeason` doesn't overwrite it against what was already saved, see its KDoc.
        tmdbId: Int? = null,
        // `GatewaySeries`'s `season_number`: `buildSeason` needs it so episodes save the real
        // season, without which `ensureEpisodeStills` flattens wrong (see its KDoc).
        seasonNumber: Int? = null,
        // The name TMDB knows the series by (`GatewaySeries.title`), so the card stops showing the
        // portal's. See `MagisEntities.buildSeason`.
        tituloCanonico: String? = null,
    ): Map<Int, String> {
        if (contentId.isBlank() || chapters.isEmpty()) return emptyMap()
        val id = MagisEntities.itemIdFor(contentId)
        val existing = itemDao.getItem(id)
        // The badge is for chapters that came out on the portal, not for the ones you just saved
        // yourself: it gets re-sealed to the total that's going to be left after the upsert, which
        // is the union of what was already there (undeleted) with what `chapters` brings (upsert
        // never leaves them deleted).
        val live = itemDao.getEpisodesOf(id).map { it.id }.toSet()
        // The MOVIE-shaped episode a standalone save of this same series might have left
        // (`addMagisSource` with no `episode` -- that's how a "Para ti" recommendation used to
        // come in before it knew how to ask the gateway for the chapters). Its id isn't any
        // chapter's, so the upsert below doesn't overwrite it: it would be left as a ghost
        // chapter, with the series' title and the whole season's ref. Soft-deleted so it travels
        // through sync, same as [sweepLegacyChapterItem]; and since `getEpisodesOf` already
        // filters tombstones, what got swept once isn't touched again (no re-dirtying the row for
        // sync).
        val ghost = MagisEntities.movieEpisodeId(id).takeIf { it in live }
        if (ghost != null) itemDao.softDeleteEpisode(ghost)
        val existingIds = live - setOfNotNull(ghost)
        val newIds = chapters.map { MagisEntities.episodeIdFor(id, it.number) }.toSet()
        val totalAfterSaving = (existingIds + newIds).size
        val episodiosVistosEnLista = com.arkiv.player.data.newcontent.NewEpisodeCounter.reseal(
            existing?.episodiosVistosEnLista,
            totalAfterSaving,
        )
        val (item, episodes) = MagisEntities.buildSeason(
            contentId = contentId, title = title, chapters = chapters, posterUrl = posterUrl,
            now = clock(), seriesRef = seriesRef, existing = existing,
            episodiosVistosEnLista = episodiosVistosEnLista, tmdbId = tmdbId, seasonNumber = seasonNumber,
            tituloCanonico = tituloCanonico,
        )
        itemDao.upsertItem(item)
        itemDao.upsertEpisodes(episodes)
        // The movie-cards the old schema left behind (one item per chapter), now that their
        // content lives inside the season's item.
        chapters.forEach { sweepLegacyChapterItem(contentId, it.number) }
        saveMagisBackdrop(id, backdropUrl)
        saveMagisStills(id, MagisEntities.seasonStills(id, chapters, clock()))
        return episodes.mapNotNull { ep -> ep.episode?.let { it to ep.id } }.toMap()
    }

    /**
     * Deletes the card a chapter saved with the old schema (one item per chapter) left behind.
     *
     * Those rows were left in the library as an episode's **movies** —the bug that motivated
     * [MagisEntities]— and there's no Room migration that reaches them. They clean themselves up
     * on saving that same chapter again, which is exactly when its content already lives in the
     * season's item and the old one contributes nothing. Soft-delete, same as [removeItem], so the
     * deletion travels through sync and doesn't reappear from the other device.
     *
     * The guard cuts off both if the row doesn't exist and if it already has the tombstone set:
     * `itemDao.getItem` does NOT filter `deleted` (it brings the row anyway, soft-delete is an
     * UPDATE, not a DELETE), so without the second check this gets called on every playback
     * —`addMagisSeason` runs it for every chapter of the season, always— and the deletion already
     * done would keep re-executing forever: every redundant UPDATE on an already-deleted row
     * overwrites the tombstone's `updatedAt` and marks it "dirty" for sync again, needlessly.
     */
    private suspend fun sweepLegacyChapterItem(contentId: String, episode: Int) {
        val old = MagisEntities.legacyChapterId(contentId, episode)
        if (itemDao.getItem(old)?.deleted != false) return
        itemDao.softDeleteEpisodesOf(old)
        itemDao.softDeleteItem(old)
    }

    /** A Magis episode's saved opaque ref (for loadMagis to resolve it). */
    suspend fun magisRefForEpisode(episodeId: String): String? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        return ep.torrentData ?: itemDao.getItem(ep.itemId)?.torrentData
    }

    /**
     * Rebuild the cross-device play descriptor for an [episodeId] this device can already play, so
     * the phone can hand the title to the TV (companion "play on the TV"). Null for ids with no
     * source the TV could re-resolve. The pure mapping lives in [buildCompanionPlayItem]; this only
     * fetches the library row (live needs none).
     */
    suspend fun companionPlayItem(episodeId: String): com.arkiv.player.companion.CompanionPlayItem? {
        if (episodeId.startsWith(com.arkiv.player.playback.PlayerSource.LIVE_PREFIX)) {
            return buildCompanionPlayItem(episodeId, null, "", "", null, null, "")
        }
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val item = itemDao.getItem(ep.itemId) ?: return null
        return buildCompanionPlayItem(
            episodeId = episodeId,
            ref = ep.torrentData ?: item.torrentData,
            itemIdentifier = item.identifier,
            title = item.tituloCanonico?.takeIf { it.isNotBlank() } ?: item.title,
            season = ep.season,
            episode = ep.episode,
            poster = item.thumbnailUrl,
        )
    }

    /**
     * Player header: item title + season/chapter label (only if it's a series). The label gets
     * PARSED, it isn't the raw displayName: in the real database those names carry anything from
     * "s01e01" to the whole synopsis with the date stuck on. See EpisodeNumbering.displayLabel.
     */
    data class PlayerHeaderInfo(val itemTitle: String, val episodeLabel: String?)

    suspend fun headerInfo(episodeId: String): PlayerHeaderInfo? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val item = itemDao.getItem(ep.itemId) ?: return null
        val count = itemDao.getEpisodesOf(ep.itemId).size
        val isMovie = when (item.categoryOverride) {
            "movie" -> true
            "series" -> false
            else -> count <= 1
        }
        val label = if (isMovie) null else EpisodeNumbering.displayLabel(ep.section, ep.displayName)
        return PlayerHeaderInfo(item.title, label)
    }

    suspend fun removeItem(identifier: String) {
        // Chapters are read BEFORE the soft-delete: `getEpisodesOf` filters `deleted = 0`, so
        // after the tombstone there would be nowhere left to get the ids from.
        val episodes = itemDao.getEpisodesOf(identifier)
        // Soft-delete (tombstone) so the deletion propagates through cloud sync.
        // The triggers bump updatedAt; the library already filters deleted=0.
        itemDao.softDeleteEpisodesOf(identifier)
        itemDao.softDeleteItem(identifier)
        // The JPEG gets really deleted (nobody else claims it), but the frame's row is left as a
        // tombstone and DOES travel through sync (see `FrameDestroyer.destroy`): if it didn't
        // propagate, removing the series from the library on one device would let the frame
        // "resurrect" on the others the next time they synced.
        episodes.forEach { frameDestroyer.destroy(it.id) }
    }

    fun observeItemDetail(identifier: String): Flow<ItemDetail?> = combine(
        itemDao.observeItem(identifier),
        itemDao.observeEpisodes(identifier),
        playbackDao.observePlaybackForItem(identifier),
    ) { item, episodes, playback ->
        if (item == null) return@combine null
        ItemDetail(
            identifier = item.identifier,
            title = item.title,
            description = item.description,
            thumbnailUrl = item.thumbnailUrl,
            episodes = episodes.map { it.toEpisode() },
            progress = playback.associateBy { it.episodeId },
        )
    }

    suspend fun getEpisode(episodeId: String): Episode? =
        itemDao.getEpisode(episodeId)?.toEpisode()

    /** All of an item's episodes, ordered. */
    suspend fun episodesOf(itemId: String): List<Episode> =
        itemDao.getEpisodesOf(itemId).map { it.toEpisode() }

    /** An item's first episode (to play a movie directly, with no list). */
    suspend fun firstEpisodeId(itemId: String): String? =
        itemDao.getEpisodesOf(itemId).firstOrNull()?.id

    /** Returns the next episode in the same section (for autoplay). */
    suspend fun nextEpisode(episodeId: String): Episode? = neighbourEpisode(episodeId) { all, id ->
        EpisodeNavigation.nextId(all, id)
    }

    /** Returns the previous episode in the same section (for the player header's "previous"/"next", see [com.arkiv.player.ui.player.PlayerCabecera]). */
    suspend fun previousEpisode(episodeId: String): Episode? = neighbourEpisode(episodeId) { all, id ->
        EpisodeNavigation.prevId(all, id)
    }

    private suspend fun neighbourEpisode(
        episodeId: String,
        pick: (List<NavEpisode>, String) -> String?,
    ): Episode? {
        val current = itemDao.getEpisode(episodeId) ?: return null
        val all = itemDao.getEpisodesOf(current.itemId)
        val targetId = pick(all.map { NavEpisode(it.id, it.section) }, episodeId) ?: return null
        return all.firstOrNull { it.id == targetId }?.toEpisode()
    }

    suspend fun getPlayback(episodeId: String): PlaybackEntity? = playbackDao.get(episodeId)

    /** All of an item's episodes' playback progress (for the chapter carousel). */
    suspend fun playbackForItem(itemId: String): Map<String, PlaybackEntity> =
        playbackDao.observePlaybackForItem(itemId).first().associateBy { it.episodeId }

    // --- Opening/ending markers (per series/item) ---

    /**
     * The raw `skip_markers` DAO, without going through the views already built below
     * (`getSkipMarker`, which only sees the WHOLE series' marker: empty `episodeId`).
     *
     * [com.arkiv.player.data.markers.MarkerEditor] needs it: it does point `getById`/`upsert`
     * calls PER CHAPTER. `PlayerViewModel` doesn't receive `AppGraph` through its constructor
     * (there are ~15 loose dependencies, see its own KDoc), so it builds its own `MarkerEditor` --
     * and this is what it's missing to be able to do that with no new parameter that would force
     * touching the call site in `PlayerScreen.kt`.
     */
    fun skipMarkerDao(): com.arkiv.player.data.db.SkipMarkerDao = skipMarkerDao

    fun observeSkipMarker(itemId: String) = skipMarkerDao.observe(itemId)

    /**
     * The marker of an EXACT scope: chapter [episodeId]'s, or the whole series' (`""`, the
     * default). Doesn't fall from one to the other on purpose -- whoever wants the full precedence
     * uses `ChapterMarker.choose` over `observeForChapter`.
     */
    suspend fun getSkipMarker(itemId: String, episodeId: String = "") =
        skipMarkerDao.getById(com.arkiv.player.data.ChapterMarker.idFor(itemId, episodeId))

    suspend fun saveSkipMarker(
        itemId: String,
        openingStartMs: Long?,
        openingEndMs: Long?,
        endingStartMs: Long?,
    ) {
        if (openingStartMs == null && openingEndMs == null && endingStartMs == null) {
            skipMarkerDao.delete(itemId)
        } else {
            skipMarkerDao.upsert(
                com.arkiv.player.data.db.SkipMarkerEntity(
                    // The markers dialog edits the SERIES' (empty episodeId): `origen` is left at
                    // its MANUAL default, which is exactly what this is.
                    id = com.arkiv.player.data.ChapterMarker.idFor(itemId, ""),
                    itemId = itemId,
                    episodeId = "",
                    openingStartMs = openingStartMs,
                    openingEndMs = openingEndMs,
                    endingStartMs = endingStartMs,
                    updatedAt = clock(),
                ),
            )
        }
    }

    /**
     * Seals "you're watching this" as soon as playback starts, without waiting to know the duration.
     *
     * [savePlayback] only writes once the player already knows `durationMs`, and on Magis that can
     * take a while (TS stream, up to a 20s probe): until then the chapter you're watching didn't
     * exist for the detail screen. Preserves position, duration and `watched` from whatever was
     * already there: this marks where you're at, it doesn't reset progress or unmark an
     * already-watched chapter.
     *
     * Skips ALREADY watched chapters entirely: `load()` is also reachable to re-watch a scene of a
     * finished chapter (from `DetailScreen`/`EpisodeRow` or `TvDetailScreen`'s carousel), and that
     * re-play can't overwrite `lastPlayedAt`. That column feeds three consumers that don't tell
     * "just watched" apart from "reopened something old":
     * [PlaybackDao.observeWatched] (via `LibraryWatched.cross`, orders the library's "Ya visto"),
     * [ItemDao.seriesWithProgress] (via `SeriesToCheck.choose`, decides which series to sweep
     * against the network looking for a new chapter) and [PlaybackDao.observeLastPlayed] (via
     * `LibraryOrder`, decides which card rises to the top of "Mi biblioteca"). Without this cutoff,
     * reopening an old chapter for three seconds would bump that series to the top of "Ya visto"
     * and put it back in the network sweep for up to 30 days, with nothing new actually watched.
     *
     * This cutoff only protects `load()`'s initial instant: as soon as the player knows the
     * duration, `savePlayback` overwrites `lastPlayedAt` with no exception (no seconds floor, see
     * the library-order spec), even if the position reached doesn't hit 60% and `watched` stays
     * `false`. So yes, reopening a finished chapter and closing it a few seconds later still bumps
     * that item to the top of "Mi biblioteca" as soon as the duration is known — that's intentional,
     * not a bug in this cutoff.
     */
    suspend fun markInProgress(episodeId: String) {
        val existing = playbackDao.get(episodeId)
        if (existing?.watched == true) return
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = existing?.positionMs ?: 0L,
                durationMs = existing?.durationMs ?: 0L,
                watched = false,
                lastPlayedAt = clock(),
            ),
        )
    }

    /** Persists playback position. Marks watched per [WatchedThreshold]. */
    suspend fun savePlayback(episodeId: String, positionMs: Long, durationMs: Long) {
        val watched = WatchedThreshold.isWatched(positionMs, durationMs)
        // The extra `playbackDao.get` is only read when this save ALREADY says "watched": it's the
        // only case where it matters whether it already was, so as not to fire `onEpisodeFinished` extra.
        val wasAlreadyWatched = watched && playbackDao.get(episodeId)?.watched == true
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                watched = watched,
                lastPlayedAt = clock(),
            )
        )
        // This is the MOST COMMON path by which a chapter ends up watched (the player calls here
        // every ~5s): if the frame isn't destroyed here too, watching a chapter to the end —without
        // ever touching setWatched's manual toggle— would leave it alive forever.
        if (watched) {
            deleteFrameFor(episodeId)
            if (!wasAlreadyWatched) onEpisodeFinished?.invoke()
        }
    }

    suspend fun setWatched(episodeId: String, watched: Boolean) {
        val existing = playbackDao.get(episodeId)
        playbackDao.upsert(
            PlaybackEntity(
                episodeId = episodeId,
                positionMs = if (watched) (existing?.durationMs ?: 0L) else 0L,
                durationMs = existing?.durationMs ?: 0L,
                watched = watched,
                // Marking watched IS an interaction with the chapter: it overwrites lastPlayedAt.
                // Unmarking is NOT (it's correcting a mistake, not "watching"), so whatever was
                // already there is preserved; otherwise the card would jump to the top of the
                // library with nothing actually watched. See observeLastPlayed, which no longer
                // filters by watched.
                lastPlayedAt = if (watched) clock() else (existing?.lastPlayedAt ?: clock()),
            )
        )
        // Unmarking (watched = false) does NOT delete anything: the chapter goes back to in
        // progress and whatever frame there is stays valid.
        if (watched) {
            deleteFrameFor(episodeId)
            if (existing?.watched != true) onEpisodeFinished?.invoke()
        }
    }

    /**
     * Destroys the frame of a chapter that just became watched. Delegates to
     * [com.arkiv.player.thumbnails.FrameDestroyer], the only place that knows how to delete a
     * frame (file + row), so the deletion logic lives in a single place.
     *
     * There's more than one path inside this repository that flips a chapter to `watched = true`:
     * the manual toggle ([setWatched], from the detail screen) and the automatic one from progress
     * ([savePlayback], past 60% of the duration -- by far the most common one). Both call this.
     * (These are the only two callers today; if a third local path that marks something watched
     * shows up, it just needs to call this helper too.)
     *
     * Called unconditionally every time `watched` comes out `true`, without checking beforehand
     * whether the frame exists (see [com.arkiv.player.thumbnails.FrameDestroyer.destroy]'s doc).
     * In particular, `savePlayback` runs every ~5s while the player is open, so past 60% this
     * repeats several times per chapter: the cost is negligible and it's not worth complicating
     * this with logic to avoid the repetition.
     */
    private suspend fun deleteFrameFor(episodeId: String) {
        frameDestroyer.destroy(episodeId)
    }

    /**
     * The identity of the work to request trivia facts for, or null if there's no way to name it
     * well (see [com.arkiv.player.data.trivia.TriviaSubject.of]). No network: the sheet is looked
     * up separately in [workSheetFor], and only if there's no cache.
     *
     * Season and chapter come first from the fields Magis and Caracol write on save
     * (`EpisodeEntity.season` / `.episode`), and if not, from the name and the section, as before.
     */
    internal suspend fun triviaSubjectFor(episodeId: String): com.arkiv.player.data.trivia.TriviaSubject? {
        val ep = itemDao.getEpisode(episodeId) ?: return null
        val item = itemDao.getItem(ep.itemId) ?: return null
        val episode = ep.episode?.takeIf { it > 0 }
            ?: com.arkiv.player.data.model.EpisodeNumbering.episodeOf(ep.displayName)
        val season = ep.season?.takeIf { it > 0 }
            ?: com.arkiv.player.data.model.EpisodeNumbering.seasonOf(ep.section)
        return com.arkiv.player.data.trivia.TriviaSubject.of(
            kind = com.arkiv.player.data.model.WorkKind.of(item.tipo, item.categoryOverride, episode),
            tmdbId = item.tmdbId,
            canonicalTitle = item.tituloCanonico,
            season = season,
            episode = episode,
        )
    }

    /**
     * TMDB's sheet of verified facts to anchor the trivia fact to THIS exact work (spec addendum
     * from 2026-09-10): never the synopsis, because it carries plot.
     *
     * **With no `tmdbId`**: a minimal sheet with only the canonical title (no facts at all) — still
     * better than asking blind. `null` if there's no canonical title either.
     *
     * **With `tmdbId`**: the movie's or series' sheet, plus the chapter's if there's a season and
     * episode and TMDB finds it. If TMDB answers with NOTHING (neither movie nor series), `null` —
     * not the minimal sheet: saving it under that key would seal for a month the "no anchor" mode
     * the addendum measured as made-up, when what actually happened was a passing outage. With no
     * cache in between, the next open just retries on its own.
     *
     * If the series DID come back but the chapter's call failed (requested with season and
     * episode), the sheet is left [com.arkiv.player.data.trivia.WorkSheet.degraded]: it's still
     * asked with the series' facts, but [com.arkiv.player.data.trivia.TriviaFacts] doesn't save
     * that answer under the chapter's key.
     *
     * Cancellation is rethrown; everything else is swallowed, as before in `nombreDeObra`.
     *
     * **This only runs if there's no cache** (same as before with the name): it can cost up to 2
     * calls to TMDB (movie, or series + chapter).
     */
    internal suspend fun workSheetFor(subject: com.arkiv.player.data.trivia.TriviaSubject): com.arkiv.player.data.trivia.WorkSheet? {
        val tmdb = tmdbApi
        val id = subject.tmdbId
        if (tmdb == null || id == null) {
            return subject.canonicalTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
                com.arkiv.player.data.trivia.WorkSheet(kind = subject.kind, name = it)
            }
        }
        if (subject.kind == "movie") {
            return try {
                tmdb.raw("movie/$id", append = "credits")?.let { com.arkiv.player.data.trivia.movieSheet(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val series = try {
            tmdb.raw("tv/$id", append = "aggregate_credits")?.let { com.arkiv.player.data.trivia.seriesSheet(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        if (subject.season == null || subject.episode == null) return series
        val chapter = try {
            tmdb.raw("tv/$id/season/${subject.season}/episode/${subject.episode}", append = "credits")
                ?.let { com.arkiv.player.data.trivia.chapterSheet(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (chapter != null) series.copy(chapter = chapter) else series.copy(degraded = true)
    }
}

/** A TMDB search result matched against a query title, plus whether that match was exact. */
internal data class TmdbMatch(
    val item: TmdbItem,
    /**
     * Whether the title matched FOR REAL, or it's the first result picked by default.
     *
     * The distinction exists because the match's two uses have different tolerances: to grab
     * "Dragon Ball Kai" a backdrop, TMDB's "Dragon Ball Z Kai" is more than good enough; to say two
     * rows are THE SAME WORK —which is what [LibraryGrouping] does, grouping by `tv:<tmdbId>`— it
     * doesn't come close. Without this flag, a title TMDB doesn't know ("Construido por los
     * hombres", which is an Evangelion chapter) took whichever first result came back and merged
     * two unrelated works into a single card.
     */
    val exact: Boolean,
)

/** lowercase, no accents, no punctuation, no "(2024)", collapsed spaces. Used to live in the
 *  web layer's `WebTmdbMatcher` (deleted); [pickTmdbMatch] still needs it to match titles from
 *  ANY source against TMDB, not just web. */
internal fun normalizeTitle(title: String): String {
    var s = title.lowercase().replace(Regex("\\(\\d{4}\\)"), " ")
    s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    s = s.replace(Regex("[^a-z0-9 ]"), " ")
    return s.replace(Regex("\\s+"), " ").trim()
}

/**
 * Which of TMDB's results is this title's art.
 *
 * NOT `results.first()`: TMDB orders by its relevance score, which beats an exact match when one
 * title is a prefix of a more popular one. Verified against the API (2026-08-11):
 * `search/tv?query=Dragon Ball` returns "Dragon Ball Z" first and the 1986 "Dragon Ball" in
 * position 7 of 9. That's why the library's three Dragon Balls ended up with Z's `tmdbId`: with
 * Z's poster and, worse, merged into ONE single card, because [LibraryGrouping] groups series by
 * `tv:<tmdbId>`.
 *
 * First an EXACT normalized-title match is looked for, against the Spanish title AND the
 * original: TMDB returns the localized one (es-MX) but releases usually come with the English
 * original ("The Simpsons" against "Los Simpson"). If neither fits it falls back to the first one,
 * which is the old behavior and is still the best bet when the title isn't exact ("Dragon Ball
 * Kai" against TMDB's "Dragon Ball Z Kai").
 *
 * A title that normalizes to empty (Japanese, Cyrillic) deliberately matches nothing: otherwise
 * it would "exact match" against any original that also normalizes to empty, which is almost all anime.
 */
internal fun pickTmdbMatch(query: String, results: List<TmdbItem>): TmdbMatch? {
    val q = normalizeTitle(query)
    if (q.isBlank()) return results.firstOrNull()?.let { TmdbMatch(it, exact = false) }
    results.firstOrNull {
        normalizeTitle(it.title) == q || normalizeTitle(it.originalTitle) == q
    }?.let { return TmdbMatch(it, exact = true) }
    return results.firstOrNull()?.let { TmdbMatch(it, exact = false) }
}

/**
 * "Bare" title to search TMDB with.
 *
 * The " — Pack" suffix was added by the app when saving a torrent that brought the whole series
 * (source removed in this branch's pruning); it isn't part of the name, and without stripping it
 * TMDB returns nothing (verified: the library's two "Naruto — Pack" entries ended up without a
 * tmdbId and so weren't grouped with the rest of the Naruto entries).
 */
internal fun cleanTitleForSearch(raw: String): String {
    // Only the SUFFIX: a long dash in the middle of the title is a legitimate separator.
    var s = raw.replace(Regex("""\s*[—–-]\s*Pack\s*$""", RegexOption.IGNORE_CASE), "")
    s = s.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace('—', ' ').replace('–', ' ')
    Regex("""\b(19|20)\d{2}\b""").find(s)?.let { s = s.substring(0, it.range.first) }
    val noise = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|x264|x265|h264|h265|hevc|bluray|blu ray|brrip|bdrip|webrip|web dl|web|hdrip|dvdrip|hdtv|latino|castellano|espanol|español|dual|multi|subs?|ac3|aac|dts|yify|rarbg|proper|remux)\b""",
    )
    s = s.replace(noise, " ")
    return s.replace(Regex("""\s+"""), " ").trim().ifBlank { raw.trim() }
}
