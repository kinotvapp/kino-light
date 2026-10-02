package com.arkiv.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.security.RootDetection
import com.arkiv.player.security.LockedScreen
import com.arkiv.player.security.RootSignalCollector
import com.arkiv.player.ui.ArkivRoot
import com.arkiv.player.ui.ArkivSplash
import com.arkiv.player.ui.LocalReducedEffects
import com.arkiv.player.ui.plugin.FocusWhenReady
import com.arkiv.player.ui.plugin.focusRing
import com.arkiv.player.ui.rememberReducedEffects
import com.arkiv.player.ui.theme.ArkivSurface
import com.arkiv.player.ui.theme.ArkivTheme
import com.arkiv.player.ui.tv.ArkivTvRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Head start given to the intro before starting to compose the app: without this the main thread
 * gets saturated and the animation doesn't get to draw (see the comment on setContent).
 *
 * Comes from [com.arkiv.player.ui.INTRO_DURATION_MS] and is NOT a loose number, on purpose.
 * Written by hand it drifted: it stayed at 600 ms --tuned for the old, 750 ms intro-- while the
 * intro grew to 880, so the root composed on top of its heaviest stretch. Tied to the real
 * duration, composition always lands AFTER the animation finished drawing, and the exit fade
 * covers it.
 *
 * Phone only: on TV the splash is the static logo (see `ArkivSplash`), there's no animation to give
 * room to, so the root starts composing right away.
 */
private val INTRO_HEAD_START_MS = com.arkiv.player.ui.INTRO_DURATION_MS.toLong()

/** Margin after starting the root's composition before uncovering the app with the fade. */
private const val CONTENT_SETTLE_MS = 400L

/** After this long on the splash, a "Preparando Kino…" note shows on it: the root waits for the
 *  warm-up with no timeout (see `startupContent`), and a weak TV box can still take seconds. */
private const val PREPARING_HINT_MS = 6000L

/**
 * Whether a rooted device gets blocked. **Off on purpose**: today we want a device with root to
 * still be able to use the app.
 *
 * It's turned off with a switch instead of deleting [com.arkiv.player.security.RootDetection]
 * because the detection itself is already built and tested (tests included); turning it back on
 * is flipping this `false` to `true`, not rewriting it.
 *
 * Note what this switch does NOT change: signature enforcement no longer lives in a Kotlin gate
 * here -- it's in the native credential path instead. A re-signed build derives the wrong
 * cert-bound credential and fails silently, so this switch only ever concerns the root block.
 */
private const val BLOCK_ON_ROOT = false

class MainActivity : AppCompatActivity() {

    private var pendingEpisode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: switches from the launch theme (system logo) to the real theme.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        // Integrity checks BEFORE building anything: no services, no Room. If the device fails
        // them, the only thing composed is the warning. See `RootDetection` for what it detects
        // and, above all, for what it CANNOT detect.
        val blockingReasons = reasonsNotToStart()
        if (blockingReasons.isNotEmpty()) {
            setContent { ArkivTheme { LockedScreen(blockingReasons) } }
            return
        }

        val isTv = isTelevision() || intent.getBooleanExtra("force_tv", false)
        com.arkiv.player.data.plugin.PluginsPlace.onTv = isTv
        // Telemetry only: how fast this device is at startup, reported once per version.
        StartupProfiler.start(this, (application as ArkivApp).graph.settings, isTv)
        setContent {
            ArkivTheme {
                val graph = (application as ArkivApp).graph

                // Reactive host approval: a plugin's kino.fetch hit a host its manifest never
                // declared, or the Stream its resolve returned is on one (StreamHostApproval), and the
                // coroutine behind it (see HostApprovalCenter) is suspended waiting for a verdict;
                // `req.question` says which. Dismissing (back button, tap outside) counts as a reject
                // -- REJECT -- so the wait can never hang open forever unanswered. A VOD stream's
                // dialog adds a third choice, the broad video permission (req.offersAnyVideoHost).
                val pendingHostApproval by graph.hostApprovalCenter.pending.collectAsState()
                pendingHostApproval?.let { req ->
                    // key(req): a queued request can replace the answered one in the SAME frame (the
                    // StateFlow goes A -> B without ever showing null), and Compose would otherwise
                    // just recompose this Dialog in place -- same window, same buttons under the
                    // person's finger. Keyed, each request is a fresh Dialog with fresh state.
                    key(req) {
                        // TV: a Compose Dialog here doesn't reliably pick up initial D-pad focus on its
                        // own (same reason PluginConsentDialog/PluginUninstallDialog/UpdateDialog all do
                        // this) -- without it, the remote could only reach "back" (a reject), leaving
                        // neither button reachable. Focus starts on "Rechazar" (the safe option), same as
                        // PluginConsentDialog/PluginUninstallDialog: granting a plugin network access to a
                        // new host is the same category of consent decision, so a reflexive D-pad
                        // "select" should not accidentally approve it.
                        val rejectFocus = remember { FocusRequester() }
                        FocusWhenReady(rejectFocus)
                        // Every answer -- either button, back, a tap outside -- is ignored for a moment
                        // after a request appears: a double-tap that answered the previous prompt must
                        // not also answer this one, a different plugin's or host's, unseen. Longer for
                        // a prompt that follows another closely (HostApprovalCenter decides).
                        var armed by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            delay(req.armDelayMs)
                            armed = true
                        }
                        val answer: (com.arkiv.player.data.plugin.HostApprovalAnswer) -> Unit = { a -> if (armed) req.answer(a) }
                        val reject = { answer(com.arkiv.player.data.plugin.HostApprovalAnswer.REJECT) }
                        val allowHost = { answer(com.arkiv.player.data.plugin.HostApprovalAnswer.ALLOW_HOST) }
                        Dialog(onDismissRequest = reject) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ArkivSurface,
                                modifier = Modifier.widthIn(max = 480.dp),
                            ) {
                                // Scrolls: with the broad video note and three buttons, a large font on a
                                // small phone or a TV could push "Rechazar" off screen.
                                Column(
                                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(req.pluginName, style = MaterialTheme.typography.titleLarge, color = Color.White)
                                    Text(
                                        req.question,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                    )
                                    if (req.offersAnyVideoHost) {
                                        Text(
                                            req.anyVideoHostNote,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.7f),
                                        )
                                        // Three choices, stacked full width: side by side they don't fit a
                                        // phone, and a column is one straight D-pad path. "Rechazar" first,
                                        // where the focus starts (see above).
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = reject,
                                                modifier = Modifier.fillMaxWidth().focusRequester(rejectFocus).focusRing(),
                                            ) { Text("Rechazar") }
                                            Button(onClick = allowHost, modifier = Modifier.fillMaxWidth().focusRing()) { Text(req.allowHostLabel) }
                                            Button(
                                                onClick = { answer(com.arkiv.player.data.plugin.HostApprovalAnswer.ALLOW_ANY_VIDEO_HOST) },
                                                modifier = Modifier.fillMaxWidth().focusRing(),
                                            ) { Text(com.arkiv.player.data.plugin.HostApprovalRequest.ANY_VIDEO_HOST_LABEL) }
                                        }
                                    } else {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                                        ) {
                                            TextButton(
                                                onClick = reject,
                                                modifier = Modifier.focusRequester(rejectFocus).focusRing(),
                                            ) { Text("Rechazar") }
                                            Button(onClick = allowHost, modifier = Modifier.focusRing()) { Text(req.allowHostLabel) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // The intro is drawn ON TOP of the app to cover the cold start.
                //
                // The content is NOT composed from the get-go: building the root (Room, the
                // home's rows) saturates the main thread and the animation's clock jumps straight
                // to the end without ever getting drawn. By giving it ~1s of free thread time, the
                // intro actually plays, and only then does the app start composing, behind the fade.
                //
                // And the root waits for the warm-up, with NO timeout (see `startupContent`): it reads
                // lazies (Magis portal/session: native 3DES + Keystore) the warm-up builds off the main
                // thread, and composing it before that finished is what froze the UI thread
                // (ERRORES-7I7/78K/9OA/1T/13/7I4). The splash keeps the main thread free meanwhile.
                var splashDone by remember { mutableStateOf(false) }
                var introDone by remember { mutableStateOf(false) }
                var contentSettled by remember { mutableStateOf(false) }
                var showPreparing by remember { mutableStateOf(false) }
                // Read OFF the main thread: the store is Keystore-backed.
                var credentialsRead by remember { mutableStateOf(false) }
                var hasCredentials by remember { mutableStateOf(false) }
                val warmedUp by graph.warmedUp.collectAsState()
                val startupScope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    if (!isTv) delay(INTRO_HEAD_START_MS)
                    introDone = true
                }
                LaunchedEffect(Unit) {
                    graph.warmedUp.first { it }
                    hasCredentials = withContext(Dispatchers.IO) { graph.credentialsStore.read() != null }
                    credentialsRead = true
                }
                LaunchedEffect(Unit) {
                    delay(PREPARING_HINT_MS)
                    showPreparing = true
                }
                val content = startupContent(introDone, warmedUp, credentialsRead, hasCredentials)
                LaunchedEffect(content) {
                    if (content != StartupContent.ACTIVATION && content != StartupContent.APP) return@LaunchedEffect
                    // The root's composition blocks the main thread; this wait only resumes once
                    // it's done, so the fade uncovers something already drawn.
                    delay(CONTENT_SETTLE_MS)
                    contentSettled = true
                }
                // After a first activation the Magis chain was never warmed (a fresh install has
                // nothing to warm): build it off the main thread now, then let the root compose.
                val onActivated: () -> Unit = {
                    credentialsRead = false
                    startupScope.launch {
                        withContext(Dispatchers.IO) { graph.warmUpCredentials() }
                        hasCredentials = withContext(Dispatchers.IO) { graph.credentialsStore.read() != null }
                        credentialsRead = true
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    when (content) {
                        StartupContent.NONE -> Unit
                        // Behind the splash at cold start; on its own after an activation.
                        StartupContent.PREPARING -> if (splashDone) PreparingScreen()
                        // Blocks all other navigation until activation succeeds -- see
                        // docs/superpowers/specs/2026-09-15-split-credential-activation-design.md.
                        StartupContent.ACTIVATION -> if (isTv) {
                            com.arkiv.player.ui.tv.TvActivationScreen(
                                activator = graph.credentialsActivator,
                                store = graph.credentialsStore,
                                onActivated = onActivated,
                            )
                        } else {
                            com.arkiv.player.ui.ActivationScreen(
                                activator = graph.credentialsActivator,
                                store = graph.credentialsStore,
                                onActivated = onActivated,
                            )
                        }
                        StartupContent.APP -> if (isTv) {
                            // Decorative motion switch (see EffectsPolicy) for everything under the
                            // TV root, read by the cards' focus zoom without each one touching settings.
                            CompositionLocalProvider(LocalReducedEffects provides rememberReducedEffects()) {
                                ArkivTvRoot(
                                    deepLinkEpisodeId = pendingEpisode,
                                    onDeepLinkConsumed = { pendingEpisode = null },
                                )
                            }
                        } else {
                            ArkivRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
                        }
                    }
                    if (!splashDone) {
                        ArkivSplash(
                            isTv = isTv,
                            canExit = contentSettled,
                            onFinished = { splashDone = true },
                        )
                        // A slow warm-up keeps the splash up: say so.
                        if (showPreparing && content == StartupContent.PREPARING) PreparingNote()
                    }

                    // OTA: shows up on its own when AppGraph detects a new version (the check at
                    // launch or the periodic UpdateWorker). "dismissed" only hides this instance
                    // of the global dialog; the manual check from Settings uses its own instance.
                    // Re-evaluate on (re)open: a pending update whose staggered time passed while the
                    // process stayed alive (no fresh startup check) surfaces now.
                    androidx.compose.runtime.LaunchedEffect(Unit) { graph.promoteDueUpdate() }
                    val updateAvailable by graph.updateInfo.collectAsState()
                    var dismissed by remember { mutableStateOf(false) }
                    // Never over a cast or its "Se cortó en el Chromecast" dialog: offered after.
                    val waitForCast = com.arkiv.player.ui.player.rememberUpdatePromptWaitsForCast(graph)
                    updateAvailable?.let { info ->
                        if (!dismissed && !waitForCast) {
                            com.arkiv.player.ui.update.UpdateDialog(
                                info = info,
                                graph = graph,
                                // "Later": persist the dismissal so the auto-prompt stops for this
                                // version (Settings' manual check still offers it).
                                onDismiss = { dismissed = true; graph.dismissPendingUpdate() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER && intent.getBooleanExtra(com.arkiv.player.cast.TvReadyNotice.EXTRA_SEND_TO_TV, false)) {
            // "Listo para la TV": the player is reopened (even if it shows this title from the
            // network) so it loads the file, and asks which TV to send it to.
            val id = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
            com.arkiv.player.cast.SendToTv.offer(id)
            com.arkiv.player.cast.PlayerReopen.request(id)
            return
        }
        if (intent?.action == ACTION_OPEN_PLAYER) {
            // The extra takes priority when present (a "download complete" notice, which points to
            // a specific episode); without it, the one currently playing opens, which is what the
            // player's notification asks for.
            pendingEpisode = intent.getStringExtra(EXTRA_EPISODE_ID) ?: NowPlaying.episodeId
        }
    }

    /**
     * Why this device cannot run the app. Empty = it can.
     *
     * 1. **Root.** See [RootDetection], which also explains its limits. Today it does NOT block:
     *    it's behind [BLOCK_ON_ROOT], turned off.
     *
     * APK-signature enforcement no longer lives here: a re-signed release build now gets garbage
     * credentials from the native credential path (silent fail), so decompile/strip/re-sign is
     * already worthless without a Kotlin-side gate to strip.
     */
    private fun reasonsNotToStart(): List<String> {
        if (!BLOCK_ON_ROOT) return emptyList()
        return RootDetection.reasons(RootSignalCollector.collect(this))
    }

    private fun isTelevision(): Boolean = DeviceType.isTelevision(this)
}

/** The note on the splash while a slow warm-up holds it up. */
@androidx.compose.runtime.Composable
private fun PreparingNote() {
    Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White.copy(alpha = 0.7f),
                strokeWidth = 2.dp,
            )
            Text("Preparando Kino…", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** Full screen while the warm-up runs after a first activation (the splash is gone by then). */
@androidx.compose.runtime.Composable
private fun PreparingScreen() {
    Box(Modifier.fillMaxSize().background(com.arkiv.player.ui.theme.ArkivBlack), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            Text("Preparando Kino…", style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}
