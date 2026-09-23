package com.arkiv.player.ui.search

/**
 * Which sources are still searching in the current source search.
 *
 * Used to be a single boolean (`loadingMagis`) that turned on at the start and off when the
 * ENTIRE search finished. Since `CompositeSource` emits a single `Done` when all sources have
 * finished, "Buscando en Magis…" kept spinning until Caracol answered, even if Magis had already
 * brought back everything. Now each source turns its own off as soon as it sends its `SourceDone`
 * or its `SourceError` ([sourceFinished]), and "Todo" spins while any one is still missing.
 *
 * The end of the entire search ([allFinished]) turns everything off regardless: a source that
 * never got to send either one can't leave its tab spinning forever.
 *
 * Sources go by the name they travel under in the events (`"magis"`, `"ditu"`), same as in
 * [SourcesState].
 */
data class SearchingSources(
    /** The sources that already responded or went down. */
    val finished: Set<String> = emptySet(),
    /** Whether the entire search already finished. Defaults to `true`: with no search in progress, nothing spins. */
    val done: Boolean = true,
) {
    fun sourceFinished(source: String) = copy(finished = finished + source)

    fun allFinished() = copy(done = true)

    /** Whether [tab] has to show that it's still searching. */
    fun isSearching(tab: SourceTab): Boolean = when (tab) {
        SourceTab.ALL -> SourceTab.entries.any { it != SourceTab.ALL && isSearching(it) }
        else -> !done && finished.none { tabForSource(it) == tab }
    }

    /** Whether any source is still searching: the same as "Todo". */
    val any: Boolean get() = isSearching(SourceTab.ALL)

    companion object {
        /** A search that's starting: everything searching. */
        fun starting() = SearchingSources(done = false)
    }
}
