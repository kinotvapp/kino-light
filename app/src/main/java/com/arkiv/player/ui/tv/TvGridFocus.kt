package com.arkiv.player.ui.tv

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One section of a TV card grid, for the D-pad routing of [TvGridFocus]: a run of [Cards] laid out in the
 * grid's columns (with the key of the full-width header item right above them, if any), or a single
 * focusable [Action] that takes a line of its own ("Actualizar" of "De la comunidad"). The keys are the
 * lazy grid's item keys: the one of each card, and for an action the key of the item that holds it.
 */
internal sealed interface GridBlock {
    data class Cards(val keys: List<String>, val headerKey: String? = null) : GridBlock
    data class Action(val key: String) : GridBlock
}

/** The focusable lines of the grid, top to bottom: each card block cut [columns] at a time, each action alone. */
internal fun gridFocusLines(blocks: List<GridBlock>, columns: Int): List<List<String>> =
    blocks.flatMap { block ->
        when (block) {
            is GridBlock.Cards -> block.keys.chunked(columns.coerceAtLeast(1))
            is GridBlock.Action -> listOf(listOf(block.key))
        }
    }

/**
 * Where Down ([down]) or Up leads from the stop [from]: the next (or previous) line, in the same column,
 * clamped to that line's last stop (an action's line has just the one). Null when [from] is on the last
 * (or first) line, so the key leaves the grid, and when [from] is not a stop of [lines].
 */
internal fun gridFocusNeighbour(lines: List<List<String>>, from: String, down: Boolean): String? {
    val line = lines.indexOfFirst { from in it }
    if (line < 0) return null
    val column = lines[line].indexOf(from)
    val target = lines.getOrNull(if (down) line + 1 else line - 1) ?: return null
    return target[minOf(column, target.lastIndex)]
}

/**
 * The header item to keep in view when [key] takes focus: the [GridBlock.Cards.headerKey] of its block
 * when [key] is on that block's FIRST line, null for any other line, an action or an unknown key.
 */
internal fun gridStopHeader(blocks: List<GridBlock>, columns: Int, key: String): String? {
    val block = blocks.firstOrNull { it is GridBlock.Cards && key in it.keys } as GridBlock.Cards? ?: return null
    return block.headerKey.takeIf { block.keys.indexOf(key) < columns.coerceAtLeast(1) }
}

/** The narrowest a compact card gets, and the room between two cards (the grid's own spacing). */
private const val PICKER_CARD_MIN_WIDTH_DP = 150f
internal const val PICKER_CARD_GAP_DP = 16f

/**
 * How many compact cards a TV plugin grid ("Elige tus fuentes", Ajustes ▸ Plugins ▸ Recomendados) lays in
 * a line of [widthDp]: as many as fit at [PICKER_CARD_MIN_WIDTH_DP] each (5 on a common 1280x720 px box,
 * ~961 dp wide at 213 dpi, whose grid gets ~833 dp; 6 on a 1280 dp TV), so Recomendados and the first
 * line of "De la comunidad" share the first screen; never fewer than 2 nor more than 6.
 */
internal fun tvPickerColumns(widthDp: Float): Int =
    ((widthDp + PICKER_CARD_GAP_DP) / (PICKER_CARD_MIN_WIDTH_DP + PICKER_CARD_GAP_DP)).toInt().coerceIn(2, 6)

/** Attempts to put focus on a stop that is being scrolled in, [WAIT_MS] apart. */
private const val FOCUS_ATTEMPTS = 20
private const val WAIT_MS = 32L

/** Most scroll steps taken to compose a stop (or a header) that lies outside the grid's viewport. */
private const val MAX_SCROLL_STEPS = 12

/**
 * Explicit Up/Down among the cards and actions of a TV lazy grid ([GridBlock], [gridFocusNeighbour]),
 * instead of Compose's geometric focus search. That search picks among every focusable node on screen by
 * distance, so what it reached from a card depended on the card's column and on which items the lazy
 * grid happened to have composed: on the KALLEY TV, Down from the left card of the last recommended line
 * went to "Listo" (outside the grid, below it) while Down from the right card went to "Actualizar".
 *
 * Every stop gets [stop]: Down/Up go to the neighbouring line; the target is scrolled in (and composed)
 * first when it lies outside the viewport, and focus then lands on it (its own bring-into-view shows it
 * whole). Going Up onto the first line of a card block also scrolls its header in ([gridStopHeader]).
 * At the grid's edges the key is left to [leaveDown] / [leaveUp] (null: its usual course).
 */
internal class TvGridFocus(private val state: LazyGridState, private val scope: CoroutineScope) {
    private val requesters = HashMap<String, FocusRequester>()
    private var blocks: List<GridBlock> = emptyList()
    private var columns: Int = 1
    private var lines: List<List<String>> = emptyList()

    /** The stop that last held focus, for a way back into the grid (Up from "Listo"). */
    var lastFocused: String? = null
        private set

    fun update(blocks: List<GridBlock>, columns: Int) {
        this.blocks = blocks
        this.columns = columns
        lines = gridFocusLines(blocks, columns)
    }

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The stop to go back to from below the grid: the last one focused if it is still there, else the first of the last line. */
    fun returnTarget(): String? = lastFocused?.takeIf { key -> lines.any { key in it } } ?: lines.lastOrNull()?.firstOrNull()

    fun stop(key: String, leaveDown: (() -> Unit)? = null, leaveUp: (() -> Unit)? = null): Modifier =
        Modifier
            .focusRequester(requester(key))
            .onFocusChanged { if (it.hasFocus) lastFocused = key }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val down = when (e.key) {
                    Key.DirectionDown -> true
                    Key.DirectionUp -> false
                    else -> return@onPreviewKeyEvent false
                }
                val target = gridFocusNeighbour(lines, key, down)
                val leave = if (down) leaveDown else leaveUp
                when {
                    target != null -> {
                        focus(target, down)
                        true
                    }
                    leave != null -> {
                        leave()
                        true
                    }
                    else -> false
                }
            }

    /** Scrolls [key] in if needed and focuses it; going Up, its block's header comes into view as well. */
    fun focus(key: String, down: Boolean) {
        scope.launch {
            reveal(key, down)
            repeat(FOCUS_ATTEMPTS) {
                if (runCatching { requester(key).requestFocus() }.isSuccess && lastFocused == key) {
                    if (!down) gridStopHeader(blocks, columns, key)?.let { revealHeader(it) }
                    return@launch
                }
                delay(WAIT_MS)
            }
        }
    }

    /** Scrolls a third of the viewport at a time towards [key] until the lazy grid has it composed. */
    private suspend fun reveal(key: String, down: Boolean) {
        repeat(MAX_SCROLL_STEPS) {
            if (state.layoutInfo.visibleItemsInfo.any { it.key == key }) return
            val step = state.layoutInfo.viewportSize.height / 3f
            if (step <= 0f || (down && !state.canScrollForward) || (!down && !state.canScrollBackward)) return
            state.scrollBy(if (down) step else -step)
        }
    }

    /** Scrolls back until the header [key] (just above the focused line) is whole at the top of the viewport. */
    private suspend fun revealHeader(key: String) {
        repeat(MAX_SCROLL_STEPS) {
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == key }
            val by = if (item == null) -info.viewportSize.height / 3f else (item.offset.y - info.viewportStartOffset).toFloat()
            if ((item != null && by >= 0f) || !state.canScrollBackward) return
            state.animateScrollBy(by)
        }
    }
}

@Composable
internal fun rememberTvGridFocus(state: LazyGridState): TvGridFocus {
    val scope = rememberCoroutineScope()
    return remember(state, scope) { TvGridFocus(state, scope) }
}
