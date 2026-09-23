package com.arkiv.player.ui.tv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.ui.rememberGraph

/**
 * The same drawers as on the phone ([com.arkiv.player.ui.settings.SettingsScreen]).
 *
 * There's no "Playback" drawer here: its only two controls (streaming/download quality and web
 * source quality) were for archive.org and torrent/web, removed in this branch's pruning — Magis
 * doesn't use either one (its CDN decides the bitrate on its own). The phone still keeps a
 * "Playback" drawer because that's also where the live channel's remote signature lives, a
 * control that never made it to this screen.
 */
private enum class TvSettingsTab(val label: String) {
    SUBTITLES("Subtítulos"),
    ACCOUNT("Cuenta"),
    APP("App"),
    CONNECT("Conectar"),
}

/**
 * TV Settings, split across tabs.
 *
 * It used to be a 700-line column scrolled through entirely with the D-pad: reaching "Check for
 * updates" meant going down through the four quality settings, the three language editors, and
 * the device list. Tabs are the gesture the catalog's roots already use
 * ([TvCatalogSections]) and they share the same piece ([TvTab]).
 *
 * The tab row lives OUTSIDE the scrolling column. Inside it, the Fire TV's `bringIntoView` pivot
 * would drag it upward as soon as focus went down into the content, and getting back to it would
 * be a fumble.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen() {
    val graph = rememberGraph()
    val magisAccount = graph.magisAccount
    // Reactive: this can flip WHILE the person is sitting on this screen (the next catalog call
    // can observe the geo-block), so it's collected instead of read once.
    val regionGeoBlocked by graph.regionGeoBlocked.collectAsStateWithLifecycle()

    // Linking Magis opens the SAME screen as the player's on-demand link prompt ([TvMagisLinkOffer]),
    // not a form unfolded inside the Settings list. There used to be two different UIs for the
    // same thing: here, loose fields with the system keyboard -awkward with the remote-, and in
    // the offer, the on-screen keyboard with "Log in" and "Create account". Keeping both meant
    // fixing everything twice, and in fact the sign-up flow's improvements (password in the
    // first step, "Create account" enabled only once the fields are complete) had only landed in
    // one of them. This is the ONLY place linking happens unprompted by the system: the TV never
    // offers it on its own (no upfront account offer on entry), only here (voluntary) and from the
    // player when live actually needs an account.
    var linkingMagis by remember { mutableStateOf(false) }
    if (linkingMagis) {
        val magisState by magisAccount.state.collectAsStateWithLifecycle()
        // And it closes itself on linking. `TvMagisLinkOffer` doesn't signal success on its own:
        // whoever composes it (here, or PlayerScreen's on-demand prompt) has to watch
        // `account.state` itself and stop painting it once linked. Without this, linking succeeds
        // -the portal accepts it- and the person is left looking at the same form, with no signal
        // that anything happened. Measured on the Fire TV on 2026-08-14: "I hit link and it
        // didn't say anything."
        LaunchedEffect(magisState) {
            if (magisState is MagisAccountState.Linked) linkingMagis = false
        }
        TvMagisLinkOffer(
            account = magisAccount,
            regionGeoBlocked = regionGeoBlocked,
            // Closing goes back to Settings, simply. The person came in to link ON PURPOSE here.
            onNotNow = { linkingMagis = false },
        )
        return
    }

    var tab by rememberSaveable { mutableStateOf(TvSettingsTab.SUBTITLES) }
    // One scroll per tab: with a single shared one, entering "Account" from the bottom of
    // "Subtitles" left the screen starting halfway down.
    val scroll = rememberSaveable(tab, saver = ScrollState.Saver) { ScrollState(0) }

    // Focus enters through the first tab. Without this it starts on the content's first row and
    // the row above gets discovered by accident.
    val firstTabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { firstTabFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            // Room for the zoom and the focus border: without this the focused tab gets clipped
            // against its own row's bounds.
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(TvSettingsTab.entries.size) { i ->
                val t = TvSettingsTab.entries[i]
                TvTab(
                    label = t.label,
                    selected = t == tab,
                    onClick = { tab = t },
                    modifier = if (i == 0) Modifier.focusRequester(firstTabFocus) else Modifier,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                TvSettingsTab.SUBTITLES -> TvSettingsSubtitles()
                TvSettingsTab.ACCOUNT -> TvSettingsAccount(
                    magisAccount,
                    onLinkMagis = { linkingMagis = true },
                    accountUnavailable = regionGeoBlocked,
                )
                TvSettingsTab.APP -> TvSettingsApp()
                TvSettingsTab.CONNECT -> TvCompanionSettings()
            }
        }
    }
}
