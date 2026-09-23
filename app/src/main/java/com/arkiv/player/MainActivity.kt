package com.arkiv.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID
import com.arkiv.player.playback.NowPlaying
import com.arkiv.player.security.RootDetection
import com.arkiv.player.security.LockedScreen
import com.arkiv.player.security.RootSignalCollector
import com.arkiv.player.ui.ArkivRoot
import com.arkiv.player.ui.ArkivSplash
import com.arkiv.player.ui.theme.ArkivTheme
import com.arkiv.player.ui.tv.ArkivTvRoot
import kotlinx.coroutines.delay

/**
 * Head start given to the intro before starting to compose the app: without this the main thread
 * gets saturated and the animation doesn't get to draw (see the comment on setContent).
 *
 * Comes from [com.arkiv.player.ui.INTRO_DURATION_MS] and is NOT a loose number, on purpose.
 * Written by hand it drifted: it stayed at 600 ms --tuned for the old, 750 ms intro-- while the
 * intro grew to 880, so the root composed on top of its heaviest stretch. Tied to the real
 * duration, composition always lands AFTER the animation finished drawing, and the exit fade
 * covers it.
 */
private val INTRO_HEAD_START_MS = com.arkiv.player.ui.INTRO_DURATION_MS.toLong()

/** Margin after starting the root's composition before uncovering the app with the fade. */
private const val CONTENT_SETTLE_MS = 400L

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
        setContent {
            ArkivTheme {
                val graph = (application as ArkivApp).graph

                // The intro is drawn ON TOP of the app to cover the cold start.
                //
                // The content is NOT composed from the get-go: building the root (Room, the
                // home's rows) saturates the main thread and the animation's clock jumps straight
                // to the end without ever getting drawn. By giving it ~1s of free thread time, the
                // intro actually plays, and only then does the app start composing, behind the fade.
                var splashDone by remember { mutableStateOf(false) }
                var loadContent by remember { mutableStateOf(false) }
                var contentSettled by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(INTRO_HEAD_START_MS)
                    loadContent = true
                    // The root's composition blocks the main thread; this wait only resumes once
                    // it's done, so the fade uncovers something already drawn.
                    delay(CONTENT_SETTLE_MS)
                    contentSettled = true
                }
                Box(Modifier.fillMaxSize()) {
                    if (loadContent) {
                        var credentials by remember { mutableStateOf(graph.credentialsStore.read()) }
                        if (credentials == null) {
                            // Blocks all other navigation until activation succeeds -- see
                            // docs/superpowers/specs/2026-09-15-split-credential-activation-design.md.
                            if (isTv) {
                                com.arkiv.player.ui.tv.TvActivationScreen(
                                    activator = graph.credentialsActivator,
                                    store = graph.credentialsStore,
                                    onActivated = { credentials = graph.credentialsStore.read() },
                                )
                            } else {
                                com.arkiv.player.ui.ActivationScreen(
                                    activator = graph.credentialsActivator,
                                    store = graph.credentialsStore,
                                    onActivated = { credentials = graph.credentialsStore.read() },
                                )
                            }
                        } else if (isTv) {
                            ArkivTvRoot(
                                deepLinkEpisodeId = pendingEpisode,
                                onDeepLinkConsumed = { pendingEpisode = null },
                            )
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
                    }

                    // OTA: shows up on its own when AppGraph detects a new version (the check at
                    // launch or the periodic UpdateWorker). "dismissed" only hides this instance
                    // of the global dialog; the manual check from Settings uses its own instance.
                    // Re-evaluate on (re)open: a pending update whose staggered time passed while the
                    // process stayed alive (no fresh startup check) surfaces now.
                    androidx.compose.runtime.LaunchedEffect(Unit) { graph.promoteDueUpdate() }
                    val updateAvailable by graph.updateInfo.collectAsState()
                    var dismissed by remember { mutableStateOf(false) }
                    updateAvailable?.let { info ->
                        if (!dismissed) {
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
