package com.arkiv.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkiv.player.ui.theme.ArkivRed
import kotlinx.coroutines.delay

/**
 * Which fun fact to show, based on how many times the next one was requested.
 *
 * The facts are requested ALL AT ONCE on startup (see [com.arkiv.player.data.trivia.TriviaFacts],
 * which talks to Kilo from the device) and here they're only rotated between. That's why this is
 * pure arithmetic and not a request: switching facts can't cost the ~20 seconds the model takes,
 * nor fail in the middle of a movie.
 *
 * Lives apart from the Composable on purpose: this project has no UI tests, so a rule written
 * inside `PlayerScreen` couldn't be tested in any way (same criterion as `DrawerDpad`).
 */
object PlayerTrivia {

    /**
     * The fact that follows [current], or -1 if there isn't one.
     *
     * Rotates in a circle: after the last one, the first comes back, so pressing up always shows
     * something. The index used to come from the video's POSITION (a new one every ten minutes),
     * but since it now advances by press the two things can't govern the same number — time would
     * move it underneath while the user moves it by hand.
     *
     * A [current] of -1 means "none shown yet", and falls to the first one.
     */
    fun nextIndex(current: Int, count: Int): Int {
        if (count <= 0) return -1
        return (current + 1).mod(count)
    }

    /** With no facts the button isn't drawn: it's the good failure, nobody sees an error or a wait. */
    fun hasButton(texts: List<String>): Boolean = texts.isNotEmpty()

    /**
     * Whether this episode carries a fun fact: movies and chapters from Magis or Caracol. A live
     * channel isn't a work (what's on changes every half hour), and Magis's ephemeral content is
     * the adults one, which has no library row to name it by. Downloaded files don't reach here:
     * [PlayerViewModel.load] routes them to `loadLocal` earlier.
     */
    fun wantsFacts(episodeId: String, kind: com.arkiv.player.playback.SourceKind): Boolean = when (kind) {
        com.arkiv.player.playback.SourceKind.MAGIS -> !com.arkiv.player.playback.MagisEphemeral.isEphemeral(episodeId)
        com.arkiv.player.playback.SourceKind.DITU -> !com.arkiv.player.playback.DituLive.isLive(episodeId)
        else -> false
    }
}

/** How long the red badge stays after the facts arrive. */
private const val BADGE_VISIBLE_MS = 5000L

/** How long the panel stays open untouched. */
private const val PANEL_VISIBLE_MS = 10_000L

/**
 * The fun fact's state: which one is showing, whether the panel is expanded, and whether the
 * announcement badge is still on screen.
 *
 * Goes with [PlayerTrivia] —which decides WHICH fact comes next— holding the little that needs to
 * be remembered between frames.
 */
@Stable
internal class TriviaState {
    /** The panel with the text is expanded. */
    var panelOpen by mutableStateOf(false)
        private set

    /**
     * Red "Dato curioso" badge stuck at the top. It's its OWN overlay: the controls start hidden
     * and auto-hide, so a notice hanging off the bar would be seen by nobody.
     *
     * Shows ONCE, when the facts arrive, and goes away on its own. Doesn't come back: facts no
     * longer rotate with time, so there's nothing new to announce afterward.
     */
    var badgeVisible by mutableStateOf(false)
        private set

    /** The fact being shown, or -1 if none has been opened yet. */
    var visibleIndex by mutableIntStateOf(-1)
        private set

    /** Bumps with each open or advance, to reset the auto-close countdown. */
    var panelTick by mutableIntStateOf(0)
        private set

    private var alreadyAnnounced = false

    /** The facts arrived: announced only once per load. [count] 0 announces nothing. */
    fun announceArrival(count: Int) {
        if (count <= 0 || alreadyAnnounced) return
        alreadyAnnounced = true
        badgeVisible = true
    }

    /** Another episode: everything's back to unseen. */
    fun reset() {
        alreadyAnnounced = false
        badgeVisible = false
        panelOpen = false
        visibleIndex = -1
        panelTick = 0
    }

    /**
     * Opens the panel, or advances to the next fact if it was already open. What the up arrow,
     * the "i" button, and tapping the badge all do: always "show me the next one".
     */
    fun showNext(count: Int) {
        val next = PlayerTrivia.nextIndex(visibleIndex, count)
        if (next < 0) return
        visibleIndex = next
        panelOpen = true
        badgeVisible = false
        panelTick++
    }

    fun closePanel() {
        panelOpen = false
    }

    internal fun hideBadge() {
        badgeVisible = false
    }
}

@Composable
internal fun rememberTriviaState(): TriviaState = remember { TriviaState() }

/**
 * The two timers: the badge leaves 5 s after appearing, and the panel at 10 s since the last
 * press (each advance resets the countdown, so reading several in a row doesn't close it).
 *
 * [count] triggers the announcement: it changes from 0 to N when
 * [com.arkiv.player.data.trivia.TriviaFacts] responds (Kilo, no gateway).
 */
@Composable
internal fun TriviaEffects(state: TriviaState, count: Int, episodeId: String) {
    LaunchedEffect(episodeId) { state.reset() }
    LaunchedEffect(count) { state.announceArrival(count) }
    LaunchedEffect(state.badgeVisible) {
        if (!state.badgeVisible) return@LaunchedEffect
        delay(BADGE_VISIBLE_MS)
        state.hideBadge()
    }
    LaunchedEffect(state.panelTick, state.panelOpen) {
        if (!state.panelOpen) return@LaunchedEffect
        delay(PANEL_VISIBLE_MS)
        state.closePanel()
    }
}

/**
 * "Dato curioso" badge: centered and stuck to the top edge, red background and white text.
 * Announces that there are facts to read and goes away on its own.
 *
 * On the phone it's tappable —it's the quick access while it's on screen—; on TV it isn't, there
 * it opens with the up arrow. [onTap] is null when it shouldn't respond to a tap.
 */
@Composable
internal fun BoxScope.TriviaBadge(state: TriviaState, onTap: (() -> Unit)? = null) {
    AnimatedVisibility(
        visible = state.badgeVisible && !state.panelOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(ArkivRed)
                .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Dato curioso",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Fact panel, deployed from the top edge. Shows [texts] at the position given by
 * [TriviaState.visibleIndex], with the counter for how many are left.
 *
 * Comes down from the top instead of being a centered dialog so it doesn't cover the video: the
 * fact gets read while the movie keeps playing.
 */
@Composable
internal fun BoxScope.TriviaPanel(state: TriviaState, texts: List<String>) {
    val text = texts.getOrNull(state.visibleIndex)
    AnimatedVisibility(
        visible = state.panelOpen && text != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.92f))
                .systemBarsPadding()
                .padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ArkivRed)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Dato curioso", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                if (texts.size > 1) {
                    Text(
                        "${state.visibleIndex + 1} de ${texts.size}",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                text.orEmpty(),
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 26.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
