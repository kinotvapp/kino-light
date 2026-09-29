package com.arkiv.player.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** What the search of the TV Recomendados tab shows in the header row. */
internal enum class TvSearchMode {
    /** Just the "Buscar plugins" button: no filter. */
    COLLAPSED,

    /** The text field, in the button's place. */
    EXPANDED,

    /** The button again, saying what the list is filtered by ("Buscar: xuper"), and "Quitar" beside it. */
    FILTERED,
}

internal fun tvSearchMode(expanded: Boolean, query: String): TvSearchMode = when {
    expanded -> TvSearchMode.EXPANDED
    query.isNotBlank() -> TvSearchMode.FILTERED
    else -> TvSearchMode.COLLAPSED
}

internal enum class TvSearchEvent {
    /** OK on the search button (in either collapsed mode): the field opens with the current query. */
    OPEN,

    /** Back while the field has focus: it closes, the query stays, focus goes to the button. */
    BACK,

    /** Focus left the field some other way (Down to the cards, Done, Up): it closes where focus went. */
    FIELD_LEFT,

    /** OK on "Quitar": the query is dropped and focus goes to the button. */
    CLEAR,
}

/** What an event leaves: whether the field is open, the query, and whether focus must be put on the button. */
internal data class TvSearchStep(val expanded: Boolean, val query: String, val focusButton: Boolean)

/**
 * The search bar's state machine. The field only exists while it is open, so every way out of it closes it;
 * Back and "Quitar" take away the very node that holds focus, hence [TvSearchStep.focusButton] (never
 * stranded focus); leaving the field by the D-pad already put focus somewhere, so it stays there.
 */
internal fun tvSearchStep(query: String, event: TvSearchEvent): TvSearchStep = when (event) {
    TvSearchEvent.OPEN -> TvSearchStep(expanded = true, query = query, focusButton = false)
    TvSearchEvent.BACK -> TvSearchStep(expanded = false, query = query, focusButton = true)
    TvSearchEvent.FIELD_LEFT -> TvSearchStep(expanded = false, query = query, focusButton = false)
    TvSearchEvent.CLEAR -> TvSearchStep(expanded = false, query = "", focusButton = true)
}

/** Longest query the collapsed button repeats before cutting it with an ellipsis. */
private const val MAX_BUTTON_QUERY = 16

/** The search button's words: "Buscar plugins", or while a query filters the list "Buscar: xuper". */
internal fun tvSearchButtonLabel(query: String): String {
    val q = query.trim()
    return when {
        q.isEmpty() -> "Buscar plugins"
        q.length > MAX_BUTTON_QUERY -> "Buscar: ${q.take(MAX_BUTTON_QUERY).trimEnd()}…"
        else -> "Buscar: $q"
    }
}

/**
 * The search of the TV Recomendados tab, in the Plugins header row: a "Buscar plugins" button that, on OK,
 * turns into the text field (focused, so the TV keyboard can open) in its place; the list filters as you
 * type ([onQueryChange], the view model's query). Back while the field has focus closes it back into the
 * button (Ajustes is not left: the handler is only on while the field is open); leaving it by the D-pad or
 * Done closes it too. While a query filters the list the button says so ([tvSearchButtonLabel]) and OK on it
 * opens the field again to edit it, and "Quitar" beside it drops the query. See [tvSearchStep].
 *
 * [upFocus] is where Up leads (the host's tab row) and [downTarget] where Down leads (the first card).
 */
@Composable
internal fun TvPluginSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    upFocus: FocusRequester?,
    downTarget: FocusRequester?,
) {
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var focusButton by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }

    fun on(event: TvSearchEvent) {
        val step = tvSearchStep(query, event)
        expanded = step.expanded
        if (step.query != query) onQueryChange(step.query)
        if (step.focusButton) focusButton = true
    }

    BackHandler(enabled = expanded) { on(TvSearchEvent.BACK) }
    LaunchedEffect(expanded) { if (expanded) requestFocusWhenReady(fieldFocus) }
    LaunchedEffect(focusButton) {
        if (focusButton) {
            requestFocusWhenReady(buttonFocus)
            focusButton = false
        }
    }

    when (tvSearchMode(expanded, query)) {
        TvSearchMode.EXPANDED -> {
            // Only a field that HAD focus closes when it loses it: the first focus event says "not focused".
            var hadFocus by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Buscar plugins") },
                singleLine = true,
                // `Done` just leaves the field for the results (the list filters as you type).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { leave(down = true, upFocus, downTarget, focusManager) }),
                modifier = Modifier
                    .width(260.dp)
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        if (it.hasFocus) hadFocus = true else if (hadFocus) on(TvSearchEvent.FIELD_LEFT)
                    }
                    // A single-line field has no use for Up/Down, and a closed keyboard would trap focus in it.
                    .onPreviewKeyEvent { e ->
                        val down = when {
                            e.type != KeyEventType.KeyDown -> null
                            e.key == Key.DirectionDown -> true
                            e.key == Key.DirectionUp -> false
                            else -> null
                        }
                        if (down != null) leave(down, upFocus, downTarget, focusManager)
                        down != null
                    },
            )
        }
        TvSearchMode.COLLAPSED, TvSearchMode.FILTERED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvCompactAction(
                label = tvSearchButtonLabel(query),
                icon = Icons.Filled.Search,
                modifier = Modifier
                    .focusRequester(buttonFocus)
                    .focusProperties { if (upFocus != null) up = upFocus }
                    .dpadDownTo(downTarget),
                onClick = { on(TvSearchEvent.OPEN) },
            )
            if (query.isNotBlank()) {
                TvCompactAction(
                    label = "Quitar",
                    icon = Icons.Filled.Close,
                    modifier = Modifier
                        .focusProperties { if (upFocus != null) up = upFocus }
                        .dpadDownTo(downTarget),
                    onClick = { on(TvSearchEvent.CLEAR) },
                )
            }
        }
    }
}

/** Moves focus out of the search field: Down to [downTarget] (the first card), Up to [upFocus], else by geometry. */
private fun leave(down: Boolean, upFocus: FocusRequester?, downTarget: FocusRequester?, focusManager: FocusManager) {
    val target = if (down) downTarget else upFocus
    if (target == null || runCatching { target.requestFocus() }.isFailure) {
        focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)
    }
}
