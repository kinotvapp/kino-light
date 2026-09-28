package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.LiveChannelCacheDao
import com.arkiv.player.data.db.LiveChannelCacheEntity
import com.arkiv.player.data.db.LiveFavoriteDao
import com.arkiv.player.data.db.LiveFavoriteEntity
import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveChannelKeys
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.gateway.liveCode
import com.arkiv.player.data.live.LiveChannelProvider
import com.arkiv.player.data.live.LiveModule
import com.arkiv.player.data.live.LiveProviderTab
import com.arkiv.player.data.live.ProviderCategory
import com.arkiv.player.data.live.XuperLiveProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer

/** How long to wait before retrying the Xuper EPG the portal returned in `missing`. See [LiveViewModel.requestEpg]. */
private const val EPG_RETRY_MS = 10_000L

/** Synthetic category (no provider has it): the favourites of every available provider, from [LiveFavoriteDao]. */
const val CATEGORY_FAVORITES = "__favoritos__"

/** How long the cross-provider search waits for typing to settle. */
private const val SEARCH_DEBOUNCE_MS = 150L

private val COMBINING_MARKS = Regex("\\p{Mn}+")

internal fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

/** By name (no accents or case) or by exact channel number (only a numbered channel: 0 means "no number"). */
fun filterChannels(channels: List<LiveChannel>, text: String): List<LiveChannel> {
    val q = text.trim()
    if (q.isEmpty()) return channels
    val normalized = q.normalized()
    return channels.filter { it.name.normalized().contains(normalized) || (it.number > 0 && it.number.toString() == q) }
}

/**
 * The En vivo search across every provider (Amendment A1) for [query]: [results] as
 * [searchAcrossProviders] orders them, and [notLoaded] the names of providers with categories
 * never listed, whose channels may be missing (see [notLoadedYetNote]).
 */
data class CrossSearch(val query: String, val results: List<LiveChannel>, val notLoaded: List<String>)

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
    /** The module's providers, in order; the screen shows a chip row for them only when [showProviders]. */
    val providers: List<LiveProviderTab> = emptyList(),
    val activeProvider: String? = null,
    /** The ACTIVE provider's categories. */
    val categories: List<ProviderCategory> = emptyList(),
    val activeCategory: String? = null,
    val channels: List<LiveChannel> = emptyList(),
    /** Live codes (see [liveCode]): the same code under two providers is two favourites. */
    val favorites: Set<String> = emptySet(),
    /** The current programme per live code, for the grid. */
    val current: Map<String, LiveProgram?> = emptyMap(),
    /** The whole day per live code, for the guide. */
    val programming: Map<String, List<LiveProgram>> = emptyMap(),
    val search: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** The active provider can have programme data (spec: the UI hides the guide when neither a plugin guide nor a playlist EPG exists). */
    val hasGuide: Boolean = true,
    /** The active provider's note over its channels (a plugin's "Lista recortada: …"); null = none. */
    val notice: String? = null,
    /** The module has no provider left (the last plugin switched off, Xuper's gate closed): nothing to load or retry. */
    val moduleEmpty: Boolean = false,
) {
    val visible: List<LiveChannel> get() = filterChannels(channels, search)
    /** More than one provider: provider chips, and a badge on each channel. */
    val showProviders: Boolean get() = providers.size > 1
    fun tabOf(channel: LiveChannel): LiveProviderTab? = providerBadge(channel, providers)
}

/**
 * State and logic for the En vivo screens (phone tab, TV guide, TV channel drawer): providers,
 * their categories and channels, search, favourites and the guide, read from a [LiveModule].
 *
 * Paints what's in the cache (Room, per provider and category) first and refreshes against the
 * provider after -- see [load] -- so a section opens instantly and keeps showing the grid if the
 * provider is slow or down. Playback ([com.arkiv.player.data.live.LiveChannelProvider.open]) and
 * [com.arkiv.player.data.db.LiveRecentDao] (recents) are not this ViewModel's.
 *
 * Providers come and go while a screen is open ([LiveModule.providers]): a plugin switched off,
 * reconfigured or uninstalled. A replaced plugin provider is closed by the module, and a call
 * still running into it ends with a [CancellationException] while this ViewModel's coroutine is
 * still active. That is never an error to show: the section is simply re-read from the
 * replacement when the new list arrives ([onProviders]). A provider that vanished hands the
 * screen to the next one; with none left the state says [LiveUiState.moduleEmpty].
 */
class LiveViewModel(
    private val module: LiveModule,
    private val favoriteDao: LiveFavoriteDao,
    private val cacheDao: LiveChannelCacheDao,
    /**
     * Whether THIS device has the 18+ section unlocked. Read on every load, not once at
     * construction: unlocking it from Settings has to show up the next time the guide is
     * entered, without restarting the app. Defaults to `false` -- the safe default, and what the
     * tests use.
     */
    private val adultsUnlocked: () -> Boolean = { false },
    /**
     * The TV channel drawer's lock (spec §4): only this provider's categories and favourites, so
     * zapping and the drawer never cross providers. Null everywhere else.
     */
    private val onlyProvider: String? = null,
    /** Where the cross-provider search indexes and matches; tests pass their own. */
    searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state

    /**
     * Per provider, in the module's order, every channel the search can see, names pre-normalised:
     * what the provider holds in memory, then its Room rows (Xuper's only from categories the 18+
     * lock allows), one entry per live code. Null = to be rebuilt. Rebuilt when a query starts
     * (blank to non-blank) and after a provider change; a successful section load is merged in.
     * Replaced whole, never mutated, so the search thread can read it while the main thread swaps it.
     */
    @Volatile private var searchIndex: List<Pair<String, List<SearchEntry>>>? = null
    /** Bumped when [searchIndex] changes under a query already shown: that query is searched again. */
    private val searchVersion = MutableStateFlow(0)
    /** Only touched inside the [crossSearch] pipeline (sequential). */
    private var lastQueryBlank = true

    /**
     * The En vivo search across every provider (Amendment A1); null while the query is blank (the
     * screens show their normal per-provider view). The TV drawer's model ([onlyProvider]) searches
     * its own provider only. Debounced, and computed off the main thread.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val crossSearch: StateFlow<CrossSearch?> =
        combine(_state.map { it.search.trim() }.distinctUntilChanged(), searchVersion) { q, _ -> q }
            .debounce(SEARCH_DEBOUNCE_MS)
            .mapLatest { q -> crossSearchFor(q) }
            .flowOn(searchDispatcher)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The current section load's Job. Cancelling the previous one before launching a new one
     * avoids wasted work when the user switches chips quickly -- but cancellation isn't instant
     * and isn't enough on its own to guard the state: a coroutine cancelled while waiting for a
     * response may still run code after it.
     *
     * Every point in [load] that writes `channels`/`error`/`loading` first checks that its
     * provider and category are still the active ones. `channels` getting overwritten by a stale
     * response is a DIFFERENT race -two SUCCESSFUL requests coming back out of order, with no
     * cancellation involved- and it's covered by the same check (see [load]'s KDoc for that case,
     * which is the one actually measured in review). Cancellation here is a "spend less"
     * optimization; the active check on each branch is the correctness guarantee.
     */
    private var loadJob: Job? = null

    /** The provider instance the active section was loaded from: a different one in [onProviders] means it was replaced. */
    private var activeInstance: LiveChannelProvider? = null
    private var noticeJob: Job? = null

    /**
     * Live codes with a guide request in flight right now -- see [requestEpg]'s KDoc. Lives outside
     * the StateFlow on purpose: it's internal bookkeeping of "who's already requesting what", not
     * something the UI paints. No explicit synchronization because all of this runs confined to
     * `Dispatchers.Main` (the dispatcher behind `viewModelScope`): calls never overlap across
     * threads, they only interleave on the same thread.
     */
    private val epgInFlight = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            favoriteDao.flowAll().collect { favs ->
                _state.update { it.copy(favorites = favs.map { f -> LiveChannelKeys.liveCode(f.provider, f.code) }.toSet()) }
            }
        }
        viewModelScope.launch { module.providers.collect(::onProviders) }
    }

    private fun visibleProviders(list: List<LiveChannelProvider>) = list.filter { onlyProvider == null || it.id == onlyProvider }

    /**
     * The module's list changed: new chips; the active provider kept (re-read from its replacement
     * if the module swapped the instance), else the next one takes over, else the module is empty.
     */
    private fun onProviders(list: List<LiveChannelProvider>) {
        invalidateSearch()
        val visible = visibleProviders(list)
        val tabs = visible.map { LiveProviderTab(it.id, it.name, it.color) }
        val before = _state.value.providers
        _state.update { it.copy(providers = tabs, moduleEmpty = tabs.isEmpty()) }
        val s = _state.value
        val active = s.activeProvider
        val kept = visible.firstOrNull { it.id == active }
        if (kept != null) {
            when {
                kept !== activeInstance -> reopen(kept, s.activeCategory)
                // The favourites list spans providers: one gone (or back) changes it.
                s.activeCategory == CATEGORY_FAVORITES && before != tabs -> load(active, CATEGORY_FAVORITES)
            }
            return
        }
        val next = visible.firstOrNull()
        if (next == null) {
            loadJob?.cancel()
            noticeJob?.cancel()
            activeInstance = null
            _state.update {
                it.copy(
                    activeProvider = null, categories = emptyList(), activeCategory = null, channels = emptyList(),
                    loading = false, error = null, hasGuide = true, notice = null,
                )
            }
        } else {
            // Favourites span providers: they stay on screen, only re-filtered.
            openProvider(next, keepFavorites = s.activeCategory == CATEGORY_FAVORITES)
        }
    }

    /** The active provider's instance was replaced (its plugin changed): the same section, read from [provider]. */
    private fun reopen(provider: LiveChannelProvider, category: String?) {
        if (category == null) { chooseProvider(provider.id); return }
        activeInstance = provider
        watchNotice(provider)
        _state.update { it.copy(categories = emptyList(), hasGuide = provider.hasGuide(), error = null) }
        load(provider.id, category)
    }

    private fun watchNotice(provider: LiveChannelProvider) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = provider.notice.value) }
        noticeJob = viewModelScope.launch {
            provider.notice.collect { n -> if (activeInstance === provider) _state.update { it.copy(notice = n) } }
        }
    }

    /** A provider chip: its categories, opening on its initial category (from cache first) or, lacking one, its first. */
    fun chooseProvider(id: String) {
        if (onlyProvider != null && id != onlyProvider) return
        openProvider(module.provider(id) ?: return, keepFavorites = false)
    }

    /**
     * After a [CancellationException] in a coroutine of ours: rethrows when it is ours, else says
     * whether [provider] was replaced in the module (the call is dropped quietly: the replacement
     * re-reads the section). Not replaced means an ordinary failure, so no spinner is left hanging.
     */
    private suspend fun replaced(id: String, provider: LiveChannelProvider): Boolean {
        currentCoroutineContext().ensureActive()
        return module.provider(id) !== provider
    }

    private fun openProvider(provider: LiveChannelProvider, keepFavorites: Boolean) {
        val id = provider.id
        loadJob?.cancel()
        activeInstance = provider
        _state.update {
            it.copy(
                activeProvider = id, categories = emptyList(), activeCategory = null, channels = emptyList(),
                error = null, hasGuide = provider.hasGuide(),
            )
        }
        watchNotice(provider)
        if (keepFavorites) { load(id, CATEGORY_FAVORITES); return }
        val initial = provider.initialCategory()
        if (initial != null) { load(id, initial); return }
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val cats: List<ProviderCategory>? = try {
                provider.categories(adultsUnlocked())
            } catch (e: CancellationException) {
                if (replaced(id, provider)) return@launch
                null
            } catch (e: Exception) {
                null
            }
            if (_state.value.activeProvider != id) return@launch
            if (cats == null) {
                _state.update { it.copy(loading = false, error = "No se pudo cargar los canales") }
                return@launch
            }
            _state.update { it.copy(categories = cats, hasGuide = provider.hasGuide()) }
            val first = cats.firstOrNull()?.id
            if (first == null) {
                // It answered, with nothing: an empty provider, not a failure.
                _state.update { it.copy(loading = false, error = null) }
            } else {
                load(id, first)
            }
        }
    }

    fun chooseCategory(id: String) {
        if (id == CATEGORY_FAVORITES) { load(_state.value.activeProvider, id); return }
        val provider = _state.value.activeProvider ?: return
        load(provider, id)
    }

    /** The "recargar" button: re-fetches the active category from its provider, bypassing its
     *  in-memory caches, and overwrites the Room cache with what comes back. */
    fun reload() {
        val s = _state.value
        val category = s.activeCategory ?: return
        load(s.activeProvider, category, force = true)
    }

    /**
     * The error screen's "Reintentar": the active category again, or, when the provider never got
     * as far as one (its categories failed), the provider opened again.
     */
    fun retry() {
        val s = _state.value
        val category = s.activeCategory
        if (category != null) { load(s.activeProvider, category); return }
        s.activeProvider?.let(::chooseProvider)
    }

    fun search(text: String) = _state.update { it.copy(search = text) }

    /**
     * Paints what's in the local cache first and refreshes against the provider after. That way
     * the section opens instantly and keeps showing the grid if the provider is slow or down --
     * in that case it only fails to play, with a concrete message.
     *
     * Tapping chips quickly is this screen's NORMAL interaction, not an edge case: if an old
     * category's (A) response arrives after the one for the category the user is already looking
     * at (B), that late response must not overwrite what's on screen -- that's why every point
     * that writes `channels`/`error` first checks that the provider and category are still the
     * active ones. Without that check, the selected chip ended up being B with A's channels
     * (measured in review). The cache IS always written even if the response arrives late: it's
     * useful for the next time that category is requested, not just for this screen.
     */
    private fun load(providerId: String?, category: String, force: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, activeCategory = category) }

            if (category == CATEGORY_FAVORITES) {
                val live = _state.value.providers.map { it.id }.toSet()
                // Favourites of a provider that is gone are hidden, never deleted: they come back with it.
                val favs = favoriteDao.flowAll().first().filter { it.provider in live }
                if (_state.value.activeCategory != category) return@launch
                val channels = favs.map { LiveChannel(it.code, it.nombre, it.numero, it.logo, provider = it.provider) }
                _state.update { it.copy(channels = channels, loading = false) }
                requestEpg(channels.take(40))
                // Opened straight on favourites (its provider took over): its chips still come.
                val owner = providerId?.let(module::provider)
                if (owner != null && _state.value.categories.isEmpty()) {
                    val cats = try {
                        owner.categories(adultsUnlocked())
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    } catch (e: Exception) {
                        null
                    }
                    if (cats != null && _state.value.activeProvider == providerId && _state.value.categories.isEmpty()) {
                        _state.update { it.copy(categories = cats, hasGuide = owner.hasGuide()) }
                        // A plugin knows it has a playlist guide only once it listed: its favourites ask again.
                        requestEpg(channels.take(40))
                    }
                }
                return@launch
            }

            val provider = providerId?.let(module::provider)
            if (provider == null) { _state.update { it.copy(loading = false) }; return@launch }
            fun stillActive() = _state.value.activeProvider == providerId && _state.value.activeCategory == category

            val cached = cacheDao.byCategory(providerId, category)
                .map { LiveChannel(it.code, it.nombre, it.numero, it.logo, provider = it.provider, ref = it.ref) }
            if (cached.isNotEmpty() && stillActive()) {
                _state.update { it.copy(channels = cached, loading = false) }
                requestEpg(cached.take(40))
            }

            fun fail() {
                // With the cache already painted, a down provider doesn't empty the screen.
                if (stillActive()) {
                    _state.update {
                        it.copy(loading = false, error = if (it.channels.isEmpty()) "No se pudo cargar los canales" else null)
                    }
                }
            }
            val fresh = try {
                if (_state.value.categories.isEmpty()) {
                    val cats = provider.categories(adultsUnlocked())
                    if (_state.value.activeProvider == providerId) _state.update { it.copy(categories = cats) }
                }
                provider.channels(category, force)
            } catch (e: CancellationException) {
                // Our own cancellation (a newer load) rethrows; a provider closed by the module is
                // re-read from its replacement (onProviders), never an error.
                if (!replaced(providerId, provider)) fail()
                return@launch
            } catch (e: Exception) {
                fail()
                return@launch
            }
            val nowMs = System.currentTimeMillis()
            cacheDao.replace(providerId, category, fresh.map {
                LiveChannelCacheEntity(it.code, category, it.name, it.number, it.logo, nowMs, provider = providerId, ref = it.ref)
            })
            mergeIntoSearch(providerId, fresh)
            if (stillActive()) {
                // A plugin learns whether it has a guide (a playlist EPG) only once it listed.
                _state.update { it.copy(channels = fresh, loading = false, error = null, hasGuide = provider.hasGuide()) }
                requestEpg(fresh.take(40))
            }
        }
    }

    private fun invalidateSearch() {
        searchIndex = null
        searchVersion.update { it + 1 }
    }

    /** A freshly listed section joins an index already built, so a query on screen finds it. */
    private fun mergeIntoSearch(providerId: String, fresh: List<LiveChannel>) {
        val idx = searchIndex ?: return
        if (fresh.isEmpty() || idx.none { it.first == providerId }) return
        searchIndex = idx.map { (id, entries) ->
            if (id != providerId) return@map id to entries
            val have = entries.mapTo(HashSet()) { it.channel.liveCode }
            id to (entries + searchIndexOf(fresh.filter { it.liveCode !in have }))
        }
        searchVersion.update { it + 1 }
    }

    private suspend fun crossSearchFor(q: String): CrossSearch? {
        if (q.isEmpty()) { lastQueryBlank = true; return null }
        val providers = visibleProviders(module.providers.value)
        val index = searchIndex?.takeIf { !lastQueryBlank } ?: buildSearchIndex(providers).also { searchIndex = it }
        lastQueryBlank = false
        return CrossSearch(
            query = q,
            results = searchAcrossProviders(q, index.map { it.second }),
            notLoaded = providers.filter { it.hasUnloadedCategories() }.map { it.name },
        )
    }

    private suspend fun buildSearchIndex(providers: List<LiveChannelProvider>): List<Pair<String, List<SearchEntry>>> =
        providers.map { p ->
            val memory = quietly { p.knownChannels() }.orEmpty()
            val allowed = if (p.id == LiveChannelKeys.XUPER) xuperSearchCategories(p) else null
            val rows = quietly { cacheDao.byProvider(p.id) }.orEmpty()
                .filter { allowed == null || it.categoria in allowed }
                .map { LiveChannel(it.code, it.nombre, it.numero, it.logo, provider = it.provider, ref = it.ref) }
            p.id to searchIndexOf((memory + rows).distinctBy { it.liveCode })
        }

    /**
     * The Xuper cache categories the search may read: the ones the portal offers under the current
     * 18+ lock, plus its "all channels" one. A channel cached from an adults category never shows
     * while adults are locked. The portal failing leaves only "all channels".
     */
    private suspend fun xuperSearchCategories(p: LiveChannelProvider): Set<String> =
        quietly { p.categories(adultsUnlocked()) }.orEmpty().mapTo(HashSet()) { it.id } + XuperLiveProvider.ALL_CATEGORY_ID

    /** [block]'s value, or null on any failure; our own cancellation still ends the caller. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (e: Exception) {
        null
    }

    /**
     * Programming for those channels, each asked of its OWN provider: stores the whole day by
     * live code (the guide uses it) and derives the current program (the grid uses it). Whatever
     * a provider doesn't have yet arrives on a later round; nothing is awaited here.
     *
     * [load] calls this TWICE per normal load (once painting the cache, again with the fresh
     * response), almost always with the same list of codes. The original filter only looked at
     * `programming` (what had ALREADY come back), and the first call hadn't returned yet by the
     * time the second one checked that map -- it found it empty and requested the EPG again.
     * Measured: the gateway received TWO identical EPG requests per normal load, against an
     * endpoint limited to 1 request every 1.5s, globally. [epgInFlight] marks a live code as
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
     *
     * The timed retry is Xuper's only. A plugin provider's "ask later" list is the channels past
     * its per-pass cap (200): they are simply asked on the next natural request (a scroll, a
     * reload), never on a timer that would hammer the plugin. A provider replaced mid-call ends
     * its call with a [CancellationException]: quietly, its channels freed for the next request.
     */
    fun requestEpg(channels: List<LiveChannel>, retry: Boolean = true) {
        val missing = channels.distinctBy { it.liveCode }
            .filter { it.liveCode !in _state.value.programming && it.liveCode !in epgInFlight }
        if (missing.isEmpty()) return
        epgInFlight.addAll(missing.map { it.liveCode })
        missing.groupBy { it.provider }.forEach { (providerId, group) ->
            val codes = group.map { it.liveCode }.toSet()
            val provider = module.provider(providerId)
            // A provider that can have no guide (a plugin without `guide` nor a playlist EPG) is never asked.
            if (provider == null || !provider.hasGuide()) { epgInFlight.removeAll(codes); return@forEach }
            viewModelScope.launch {
                var later: List<String> = emptyList()
                try {
                    val (programsByChannel, notFound) = provider.guide(group)
                    later = notFound
                    val now = System.currentTimeMillis() / 1000
                    val current = programsByChannel.mapValues { (_, progs) -> progs.firstOrNull { p -> now >= p.start && now < p.end } }
                    _state.update { it.copy(programming = it.programming + programsByChannel, current = it.current + current) }
                } catch (e: CancellationException) {
                    // Rethrown when it is ours; a closed provider's call just ends (see the KDoc).
                    currentCoroutineContext().ensureActive()
                } catch (e: Exception) {
                    // No guide this round: the grid keeps working without it.
                } finally {
                    // Whatever happens: frees the live codes so a future request -another load,
                    // another scroll- can retry them. A failure must not block them forever.
                    epgInFlight.removeAll(codes)
                }
                if (retry && providerId == LiveChannelKeys.XUPER && later.isNotEmpty()) {
                    delay(EPG_RETRY_MS)
                    requestEpg(group.filter { it.liveCode in later }, retry = false)
                }
            }
        }
    }

    fun toggleFavorite(c: LiveChannel) {
        viewModelScope.launch {
            if (c.liveCode in _state.value.favorites) favoriteDao.delete(c.provider, c.code)
            else favoriteDao.save(LiveFavoriteEntity(c.code, c.name, c.number, c.logo, provider = c.provider))
        }
    }
}
