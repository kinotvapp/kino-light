package com.arkiv.player.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.data.magis.MagisAccountState
import com.arkiv.player.ui.readingWidth
import com.arkiv.player.ui.rememberGraph

/**
 * The Settings drawers. "Reproducción" was dropped from this row (its controls -- quality/source
 * pickers -- were all pruned from this branch; see the deleted `ReproduccionTab.kt`), so today
 * "Subtítulos" opens the screen.
 */
private enum class SettingsTab(val label: String) {
    SUBTITLES("Subtítulos"),
    ACCOUNT("Cuenta"),
    APP("App"),
    PLUGINS("Plugins"),
    CONNECT("Conectar"),
}

/**
 * The phone's Ajustes, spread across tabs.
 *
 * Used to be a single column with eight blocks chained together: to reach "Mis aparatos" you had
 * to pass through the three language editors and the subtitle color palette. Each tab builds its
 * own state --only the visible one subscribes to the preferences it shows-- and the tab row lives
 * outside the scroll, so it's always within reach.
 */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, onOpenDownloads: () -> Unit = {}) {
    val graph = rememberGraph()
    val magisAccount = graph.magisAccount
    // Reactive: this can flip WHILE the person is sitting on this screen (the next catalog call
    // can observe the geo-block), so it's collected instead of read once.
    val regionGeoBlocked by graph.regionGeoBlocked.collectAsStateWithLifecycle()

    // Linking Magis opens the SAME screen as the player's on-demand link prompt (`MagisLinkOffer`),
    // not a form unfolded inside the tab -- same reasoning as `TvSettingsScreen`'s `linkingMagis`:
    // two different UIs for the same thing means fixing everything twice. This is the ONLY place
    // linking happens unprompted by the system: the app never offers it on its own (no upfront
    // account offer on entry), only here (voluntary) and from the player when live actually needs
    // an account.
    var linkingMagis by remember { mutableStateOf(false) }
    if (linkingMagis) {
        val magisState by magisAccount.state.collectAsStateWithLifecycle()
        // Closes itself on linking; MagisLinkOffer doesn't signal success on its own (see
        // TvSettingsScreen's own comment on the same pattern).
        LaunchedEffect(magisState) {
            if (magisState is MagisAccountState.Linked) linkingMagis = false
        }
        MagisLinkOffer(
            account = magisAccount,
            regionGeoBlocked = regionGeoBlocked,
            // Closing goes back to Settings, simply. The person came in to link ON PURPOSE here.
            onNotNow = { linkingMagis = false },
        )
        return
    }

    var tab by rememberSaveable { mutableStateOf(SettingsTab.SUBTITLES) }
    // One scroll per tab: with a single shared one, entering "Cuenta" from the bottom of
    // "Subtítulos" left the screen starting halfway down.
    val scroll = rememberSaveable(tab, saver = ScrollState.Saver) { ScrollState(0) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .readingWidth()
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            Text(
                "Ajustes",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTab.entries.forEach { t ->
                    Chip(t.label, t == tab) { tab = t }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
            ) {
                when (tab) {
                    SettingsTab.SUBTITLES -> SubtitlesTab()
                    SettingsTab.ACCOUNT -> AccountSection(
                        magisAccount,
                        onLink = { linkingMagis = true },
                        accountUnavailable = regionGeoBlocked,
                    )
                    SettingsTab.APP -> AppTab(onOpenDownloads = onOpenDownloads)
                    SettingsTab.PLUGINS -> com.arkiv.player.ui.plugin.PluginsTab()
                    SettingsTab.CONNECT -> CompanionSettings()
                }
                // The bottom shell adds the air below: the tabs don't need to know there's a
                // navigation bar under them.
                Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 32.dp))
            }
        }
    }
}
