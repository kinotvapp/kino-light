package com.arkiv.player.ui.search

import com.arkiv.player.ui.catalog.PlaySource

/**
 * Filter by result list origin. With several sections open at once the screen turns into a wall:
 * this lets a single origin show once you already know which one you want.
 *
 * The order here rules: it's the chips' and the "Todo" sections' order. Magis first and Caracol
 * after.
 */
enum class SourceTab(val label: String) {
    ALL("Todo"),
    MAGIS("Xuper"),
    CARACOL("Caracol"),
}

/** The tab a source belongs to. */
fun tabOf(source: PlaySource): SourceTab = when (source) {
    is PlaySource.Magis -> SourceTab.MAGIS
    is PlaySource.Ditu -> SourceTab.CARACOL
}

/** How many sources there are per tab (ALL included), to paint on the chip. Always returns a key
 *  for every tab, even at zero, so the chips don't jump around as results arrive from each origin. */
fun countsByTab(sources: List<PlaySource>): Map<SourceTab, Int> {
    val counts = sources.groupingBy { tabOf(it) }.eachCount()
    return SourceTab.entries.associateWith { tab ->
        if (tab == SourceTab.ALL) sources.size else counts[tab] ?: 0
    }
}

fun filterByTab(sources: List<PlaySource>, tab: SourceTab): List<PlaySource> =
    if (tab == SourceTab.ALL) sources else sources.filter { tabOf(it) == tab }

/**
 * The sources to draw as rows in the TV results: in the enum's order, without the empty ones and
 * respecting the chosen filter.
 *
 * Lives here and not in the screen because it's the only part of "how it looks" that can be
 * tested without Compose, and it's exactly the part that decides whether a source gets lost from
 * view -- which was the problem: with 536 torrents and 20 from magis in a single vertical list,
 * magis didn't exist.
 */
fun visibleRows(sources: List<PlaySource>, tab: SourceTab): List<Pair<SourceTab, List<PlaySource>>> {
    val bySource = sources.groupBy { tabOf(it) }
    return SourceTab.entries
        .filter { it != SourceTab.ALL && (tab == SourceTab.ALL || it == tab) }
        .mapNotNull { source -> bySource[source]?.takeIf { it.isNotEmpty() }?.let { source to it } }
}
