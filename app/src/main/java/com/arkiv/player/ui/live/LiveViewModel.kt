package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveCatalogGateway
import com.arkiv.player.data.gateway.LiveCategory
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

/** How long to wait before retrying the EPG the gateway returned in `missing`. See [LiveViewModel.requestEpg]. */
private const val EPG_RETRY_MS = 10_000L

/** Category id the portal uses for "all channels" (not our own convention). */
const val CATEGORY_ALL = 76182

/** Synthetic category (doesn't exist in the portal): filters by [LiveFavoriteDao] instead of the gateway. */
const val CATEGORY_FAVORITES = -1

private fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** By name (no accents or case) or by exact channel number. */
fun filterChannels(channels: List<LiveChannel>, text: String): List<LiveChannel> {
    val q = text.trim()
    if (q.isEmpty()) return channels
    val normalized = q.normalized()
    return channels.filter { it.name.normalized().contains(normalized) || it.number.toString() == q }
}

/**
 * Fraction [0,1] of the current program's progress, for the grid's thin "Now on screen" bar.
 * Guarded against inconsistent EPG data (end <= start): without this a bar with a 0 or negative
 * denominator paints a negative/NaN width instead of simply not advancing.
 */
fun programProgress(p: LiveProgram, nowSeconds: Long = System.currentTimeMillis() / 1000): Float {
    val total = (p.end - p.start).toFloat()
    if (total <= 0f) return 0f
    return ((nowSeconds - p.start).toFloat() / total).coerceIn(0f, 1f)
}

data class LiveUiState(
    val categories: List<LiveCategory> = emptyList(),
    val activeCategory: Int = CATEGORY_ALL,
    val channels: List<LiveChannel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** The current program per channel, for the grid. */
    val current: Map<String, LiveProgram?> = emptyMap(),
    /** The whole day per channel, for the guide (Task 12). */
    val programming: Map<String, List<LiveProgram>> = emptyMap(),
    val search: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val visible: List<LiveChannel> get() = filterChannels(channels, search)
}

/**
 * State and logic for the "Live" tab: categories, channels, search, and favorites.
 *
 * Paints what's in the cache (Room) first and refreshes against [api] after -- see [load] --
 * so the section opens instantly and keeps showing the grid if the gateway is slow or down.
 * [com.arkiv.player.ui.live.LiveController] (per-channel session resolution) and
 * [com.arkiv.player.data.db.LiveRecentDao] (recents) are used directly by `LiveScreen`, not this
 * ViewModel: only what the guide (Task 12) also needs to reuse lives here.
 *
 * [api] is [LiveCatalogGateway] and not [com.arkiv.player.data.gateway.LiveApi] on purpose: it's
 * the narrow interface with the three operations this ViewModel actually consumes (see its KDoc
 * for the full reasoning). The production call site (`AppGraph`/`LiveScreen`) doesn't change a
 * line -- `LiveApi` implements the interface, so an instance of it fits here as-is.
 */
class LiveViewModel(
    private val api: LiveCatalogGateway,
    private val favoriteDao: LiveFavoriteDao,
    private val cacheDao: LiveChannelCacheDao,
    /**
     * Whether THIS device has the 18+ section unlocked. Read on every load, not once at
     * construction: unlocking it from Settings has to show up the next time the guide is
     * entered, without restarting the app. Defaults to `false` -- the safe default, and what the
     * tests use.
     */
    private val adultsUnlocked: () -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state

    /**
     * The current category load's Job. Cancelling the previous one before launching a new one
     * avoids wasted network work when the user switches chips quickly -- but cancellation isn't
     * instant and isn't enough on its own to guard the state: `runCatching` catches even
     * `CancellationException`, so a coroutine cancelled while waiting for an HTTP response can
     * still end up running its `onFailure` anyway (never its `onSuccess`: for `runCatching` to
     * catch that exception, the cancellation had to intercept the call BEFORE it returned data,
     * so that path never gets to write `channels`).
     *
     * Through this specific path -a cancelled coroutine that still runs its `onFailure`- the only
     * thing that could get corrupted without the `activeCategory` check inside [load] is
     * `error`/`loading`: the OLD category writing "couldn't load" over the one the user is
     * already looking at. `channels` getting overwritten by a stale response is a DIFFERENT race
     * -two SUCCESSFUL requests coming back out of order, with no cancellation involved- and it's
     * covered by the same check but on the `onSuccess` side (see [load]'s KDoc for that case,
     * which is the one actually measured in review). Cancellation here is a "spend less"
     * optimization; the `activeCategory` check on each branch is the correctness guarantee for
     * what that branch can end up writing.
     */
    private var loadJob: Job? = null

    /**
     * Codes with an EPG request in flight right now -- see [requestEpg]'s KDoc. Lives outside the
     * StateFlow on purpose: it's internal bookkeeping of "who's already requesting what", not
     * something the UI paints. No explicit synchronization because all of this runs confined to
     * `Dispatchers.Main` (the dispatcher behind `viewModelScope`): calls never overlap across
     * threads, they only interleave on the same thread.
     */
    private val epgInFlight = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            favoriteDao.flowAll().collect { favs ->
                _state.update { it.copy(favorites = favs.map { f -> f.code }.toSet()) }
            }
        }
        load(CATEGORY_ALL)
    }

    fun chooseCategory(id: Int) = load(id)

    fun search(text: String) = _state.update { it.copy(search = text) }

    /**
     * Paints what's in the local cache first and refreshes against the gateway after. That way
     * the section opens instantly and keeps showing the grid if the gateway is slow or down --
     * in that case it only fails to play, with a concrete message.
     *
     * Tapping chips quickly is this screen's NORMAL interaction, not an edge case: if an old
     * category's (A) response arrives after the one for the category the user is already looking
     * at (B), that late response must not overwrite what's on screen -- that's why every point
     * that writes `channels`/`error` first checks that `category` is still `activeCategory`.
     * Without that check, the selected chip ended up being B with A's channels (measured in
     * review). The cache IS always written even if the response arrives late: it's useful for the
     * next time that category is requested, not just for this screen.
     */
    private fun load(category: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, activeCategory = category) }

            if (category == CATEGORY_FAVORITES) {
                val favs = favoriteDao.flowAll().first()
                if (_state.value.activeCategory != category) return@launch
                val channels = favs.map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
                _state.update { it.copy(channels = channels, loading = false) }
                requestEpg(channels.take(40).map { it.code })
                return@launch
            }

            val cached = cacheDao.byCategory(category)
                .map { LiveChannel(it.code, it.nombre, it.numero, it.logo) }
            if (cached.isNotEmpty() && _state.value.activeCategory == category) {
                _state.update { it.copy(channels = cached, loading = false) }
                requestEpg(cached.take(40).map { it.code })
            }

            runCatching {
                if (_state.value.categories.isEmpty()) {
                    val cats = api.categories(includeAdults = adultsUnlocked())
                    _state.update { it.copy(categories = cats) }
                }
                api.channels(category)
            }.onSuccess { fresh ->
                val nowMs = System.currentTimeMillis()
                cacheDao.replace(category, fresh.map {
                    LiveChannelCacheEntity(it.code, category, it.name, it.number, it.logo, nowMs)
                })
                if (_state.value.activeCategory == category) {
                    _state.update { it.copy(channels = fresh, loading = false, error = null) }
                    requestEpg(fresh.take(40).map { it.code })
                }
            }.onFailure {
                // With the cache already painted, a down gateway doesn't empty the screen.
                if (_state.value.activeCategory == category) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (it.channels.isEmpty()) "No se pudo cargar los canales" else null,
                        )
                    }
                }
            }
        }
    }

    /**
     * Programming for those channels: stores the whole day (the guide uses it) and derives the
     * current program (the grid uses it). Whatever the gateway doesn't have yet arrives on a
     * later round; nothing is awaited here.
     *
     * [load] calls this TWICE per normal load (once painting the cache, again with the fresh
     * response), almost always with the same list of codes. The original filter only looked at
     * `programming` (what had ALREADY come back), and the first call hadn't returned yet by the
     * time the second one checked that map -- it found it empty and requested the EPG again.
     * Measured: the gateway received TWO identical EPG requests per normal load, against an
     * endpoint limited to 1 request every 1.5s, globally. [epgInFlight] marks a code as
     * "requested" BEFORE launching the coroutine (not after it returns), so the second call sees
     * it and discards it.
     *
     * There's no background sweep on the server (a decision made separately): the gateway's
     * worker only fills its per-channel cache at 1.5s each, so the FIRST query for any channel
     * almost always comes back with that channel in `missing` -empty EPG, not an error-. Left as
     * is, the guide would stay on "Cargando programación…" until the user scrolled that row off
     * screen and back (finding F3 from the final review). [retry] does a single bounded retry -at
     * [EPG_RETRY_MS]- of whatever came back in `missing`: `false` in the recursive call below cuts
     * the chain there, so a channel the gateway never ends up having doesn't trigger retries
     * forever. The retry reuses this same function -and therefore [epgInFlight]- so it doesn't
     * compete with the duplicate-request guard: if by the time it runs the code already arrived
     * another way (another load, another scroll), the `missing` filter below discards it on its
     * own.
     */
    fun requestEpg(codes: List<String>, retry: Boolean = true) {
        val missing = codes.filter { it !in _state.value.programming && it !in epgInFlight }
        if (missing.isEmpty()) return
        epgInFlight.addAll(missing)
        viewModelScope.launch {
            var stillMissing: List<String> = emptyList()
            try {
                runCatching { api.epg(missing) }.onSuccess { (programsByChannel, notFound) ->
                    stillMissing = notFound
                    val now = System.currentTimeMillis() / 1000
                    val current = programsByChannel.mapValues { (_, progs) ->
                        progs.firstOrNull { p -> now >= p.start && now < p.end }
                    }
                    _state.update {
                        it.copy(programming = it.programming + programsByChannel, current = it.current + current)
                    }
                }
            } finally {
                // Whatever happens (success or failure): frees the codes so a future request
                // -another load, another scroll- can retry them. A failure must not block them
                // forever.
                epgInFlight.removeAll(missing)
            }
            if (retry && stillMissing.isNotEmpty()) {
                delay(EPG_RETRY_MS)
                requestEpg(stillMissing, retry = false)
            }
        }
    }

    fun toggleFavorite(c: LiveChannel) {
        viewModelScope.launch {
            if (c.code in _state.value.favorites) favoriteDao.delete(c.code)
            else favoriteDao.save(LiveFavoriteEntity(c.code, c.name, c.number, c.logo))
        }
    }
}
