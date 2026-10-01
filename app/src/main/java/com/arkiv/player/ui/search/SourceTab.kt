package com.arkiv.player.ui.search

import androidx.compose.ui.graphics.Color
import com.arkiv.player.data.ditu.CaracolVisibility
import com.arkiv.player.ui.catalog.ArkivCaracolVerde
import com.arkiv.player.ui.catalog.PlaySource
import com.arkiv.player.ui.catalog.accent

/**
 * Filter by result origin. With several sections open at once the screen turns into a wall: this
 * lets a single origin show once you already know which one you want.
 *
 * "Todo" and Caracol are fixed ([FIXED]); after them, one tab per installed plugin that brought
 * results, in arrival order ([tabsFor]) — Xuper included, which searches through its plugin. A tab
 * IS its [key] — the source name the search events use (`"ditu"`, `"plugin:<id>"`) — so the tab built
 * from a result and the one built from an error event for the same source are equal.
 */
class SourceTab private constructor(val key: String, val label: String, val accent: Color) {
    override fun equals(other: Any?): Boolean = other is SourceTab && other.key == key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "SourceTab($key)"

    companion object {
        val ALL = SourceTab("all", "Todo", Color.White)
        val CARACOL = SourceTab("ditu", "Caracol", ArkivCaracolVerde)

        /** The tabs every search shows, in order, even at zero. Caracol only while it isn't
         *  hidden ([CaracolVisibility]); the parameter lets tests pin either state. */
        fun fixed(caracolVisible: Boolean = CaracolVisibility.visible): List<SourceTab> =
            if (caracolVisible) listOf(ALL, CARACOL) else listOf(ALL)

        /** [fixed] as the app runs it, with the [CaracolVisibility] switch. */
        val FIXED: List<SourceTab> = fixed()

        fun plugin(source: String, label: String, accent: Color): SourceTab = SourceTab(source, label, accent)
    }
}

/** The tab a source belongs to. */
fun tabOf(source: PlaySource): SourceTab = when (source) {
    is PlaySource.Ditu -> SourceTab.CARACOL
    is PlaySource.Plugin -> SourceTab.plugin(source.result.source, source.pluginName, source.accent)
}

/**
 * "Todo", Caracol, then one tab per plugin present in [sources], in order of first appearance.
 * While Caracol is hidden ([CaracolVisibility]) its tab isn't there at all.
 */
fun tabsFor(sources: List<PlaySource>, caracolVisible: Boolean = CaracolVisibility.visible): List<SourceTab> =
    SourceTab.fixed(caracolVisible) +
        sources.filterIsInstance<PlaySource.Plugin>().map(::tabOf).distinct()

/** How many sources per tab (ALL included), to paint on the chip. Fixed tabs always have a key,
 *  even at zero, so the chips don't jump around as results arrive. */
fun countsByTab(sources: List<PlaySource>, caracolVisible: Boolean = CaracolVisibility.visible): Map<SourceTab, Int> {
    val counts = sources.groupingBy { tabOf(it) }.eachCount()
    return tabsFor(sources, caracolVisible).associateWith { tab -> if (tab == SourceTab.ALL) sources.size else counts[tab] ?: 0 }
}

fun filterByTab(sources: List<PlaySource>, tab: SourceTab): List<PlaySource> =
    if (tab == SourceTab.ALL) sources else sources.filter { tabOf(it) == tab }

/**
 * The sources to draw as rows in the TV results: in [tabsFor]'s order, without the empty ones and
 * respecting the chosen filter.
 *
 * Lives here and not in the screen because it's the only part of "how it looks" that can be
 * tested without Compose, and it's exactly the part that decides whether a source gets lost from
 * view -- which was the problem: with 536 torrents and 20 from magis in a single vertical list,
 * magis didn't exist.
 */
fun visibleRows(
    sources: List<PlaySource>,
    tab: SourceTab,
    caracolVisible: Boolean = CaracolVisibility.visible,
): List<Pair<SourceTab, List<PlaySource>>> {
    val bySource = sources.groupBy { tabOf(it) }
    return tabsFor(sources, caracolVisible)
        .filter { it != SourceTab.ALL && (tab == SourceTab.ALL || it == tab) }
        .mapNotNull { t -> bySource[t]?.takeIf { it.isNotEmpty() }?.let { t to it } }
}
