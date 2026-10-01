package com.arkiv.player.ui.search

/**
 * Which sources are still searching in the current source search.
 *
 * Used to be a single boolean (`loadingMagis`) that turned on at the start and off when the
 * ENTIRE search finished. Since `CompositeSource` emits a single `Done` when all sources have
 * finished, "Buscando en Magis…" kept spinning until Caracol answered, even if Magis had already
 * brought back everything. Now each source turns its own off as soon as it sends its `SourceDone`
 * or its `SourceError` ([sourceFinished]), and "Todo" spins while Caracol, or any plugin that
 * announced itself ([sourceStarted]) -- Xuper included, which searches as a plugin -- hasn't
 * finished. Plugins announce themselves before touching the network (`PluginContentSource.search`
 * emits `SourceStart` first), so none of them can be missed by a Caracol that fails fast.
 *
 * The end of the entire search ([allFinished]) turns everything off regardless: a source that
 * never got to send either one can't leave its tab spinning forever.
 *
 * Sources go by the name they travel under in the events (`"ditu"`, `"plugin:<id>"`), same as in
 * [SourcesState].
 */
data class SearchingSources(
    /** The sources that already responded or went down. */
    val finished: Set<String> = emptySet(),
    /** Whether the entire search already finished. Defaults to `true`: with no search in progress, nothing spins. */
    val done: Boolean = true,
    /** Sources that announced themselves (`SourceStart`); Caracol is always expected (unless hidden). */
    val started: Set<String> = emptySet(),
    /** Sources "Todo" waits on even before they announce themselves: [ALWAYS_EXPECTED] by default. */
    val expected: Set<String> = ALWAYS_EXPECTED,
) {
    fun sourceStarted(source: String) = copy(started = started + source)

    fun sourceFinished(source: String) = copy(finished = finished + source)

    fun allFinished() = copy(done = true)

    /** Whether [tab] has to show that it's still searching. */
    fun isSearching(tab: SourceTab): Boolean = when (tab) {
        SourceTab.ALL -> !done && ((expected + started) - finished).isNotEmpty()
        else -> !done && tab.key !in finished
    }

    /** Whether any source is still searching: the same as "Todo". */
    val any: Boolean get() = isSearching(SourceTab.ALL)

    companion object {
        /**
         * Caracol only. The native Magis source (`"magis"`) used to be here too; it was deleted in
         * Task 13c and nothing emits under that name any more, so waiting on it only kept "Todo"
         * spinning until the whole search's end signal.
         */
        internal val ALWAYS_EXPECTED: Set<String>
            // Hidden Caracol is never searched (CaracolVisibility): waiting on it would keep "Todo"
            // spinning until the whole search ended.
            get() = expectedFor(com.arkiv.player.data.ditu.CaracolVisibility.visible)

        /** A search that's starting: everything searching. */
        fun starting() = SearchingSources(done = false)

        /** [starting] with Caracol pinned visible or hidden, whatever the switch says (tests). */
        internal fun starting(caracolVisible: Boolean) =
            SearchingSources(done = false, expected = expectedFor(caracolVisible))

        internal fun expectedFor(caracolVisible: Boolean): Set<String> =
            if (caracolVisible) setOf(SourceTab.CARACOL.key) else emptySet()
    }
}
