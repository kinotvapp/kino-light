package com.arkiv.player.ui.player

import com.arkiv.player.data.gateway.GatewayBlockedException
import com.arkiv.player.data.plugin.PluginSetupRequiredException
import com.arkiv.player.data.plugin.PluginStreamExpiry
import com.arkiv.player.playback.SourceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlayerViewModel.loadPlugin`/`onMagisExoError`'s plugin-only branches (review round 1's three
 * findings), pinned down as pure functions/mechanisms because `PlayerViewModel` itself can't be
 * instantiated here: `repo: ArkivRepository` needs a real `ArkivDatabase`, and this module has no
 * Room or Robolectric infrastructure in its JVM unit tests -- same reason `DituState`,
 * `liveErrorMessage` and `shouldMarkInProgress` already live outside the ViewModel as testable
 * pure units (see `RecommendationQueryTest`'s KDoc for the same constraint, stated independently
 * of this task). `PlayerViewModel`'s own wiring of these pieces is covered by code review, noted
 * in the fix report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginPlaybackDecisionsTest {

    private fun samplePlayerData() = PlayerData(
        episodeId = "plugin:demo:v7::0",
        itemId = "plugin:demo:v7",
        title = "Video 7",
        subtitle = "",
        mediaUrl = "http://10.0.2.2:8096/stream/v7.mp4",
        castUrl = null,
        artworkUrl = "",
        openingStartMs = null, openingEndMs = null, endingStartMs = null,
        kind = SourceKind.PLUGIN,
    )

    // --- PluginLoadFailure.from: what loadPlugin's failure `when` used to do inline (finding 3) ---

    @Test fun `geo_blocked becomes the same dialog as a portal-side region block`() {
        assertEquals(
            PluginLoadFailure.Blocked("bloqueado en tu región"),
            PluginLoadFailure.from(GatewayBlockedException("bloqueado en tu región"), "Demo"),
        )
    }

    @Test fun `auth_required becomes a setup prompt carrying the failing plugin's own id`() {
        assertEquals(
            PluginLoadFailure.SetupRequired("demo", "Configura Demo en Ajustes ▸ Plugins"),
            PluginLoadFailure.from(PluginSetupRequiredException("demo", "Configura Demo en Ajustes ▸ Plugins"), "Otro"),
        )
    }

    @Test fun `everything else passes the plugin's own wording through unchanged`() {
        assertEquals(
            PluginLoadFailure.Generic("Demo no respondió a tiempo"),
            PluginLoadFailure.from(RuntimeException("Demo no respondió a tiempo"), "Demo"),
        )
    }

    @Test fun `a blank or missing failure message falls back to a generic sentence naming the plugin`() {
        assertEquals(PluginLoadFailure.Generic("No se pudo abrir esto con Demo"), PluginLoadFailure.from(RuntimeException(""), "Demo"))
        assertEquals(PluginLoadFailure.Generic("No se pudo abrir esto con Demo"), PluginLoadFailure.from(null, "Demo"))
    }

    // --- shouldRetryPluginStream: onMagisExoError's retry gate (findings 1 & 2) ---

    @Test fun `never retries a non-plugin item, even with a tracked expiry past its age gate`() {
        val expiry = PluginStreamExpiry(resolvedAtMs = 0, expiresInSeconds = 60)
        assertFalse(shouldRetryPluginStream(SourceKind.MAGIS, expiry, nowMs = 60_000))
        assertFalse(shouldRetryPluginStream(null, expiry, nowMs = 60_000))
    }

    @Test fun `never retries a plugin item with no tracked expiry`() {
        assertFalse(shouldRetryPluginStream(SourceKind.PLUGIN, null, nowMs = 60_000))
    }

    @Test fun `retries a plugin item only once its own expiry's age gate is met`() {
        val expiry = PluginStreamExpiry(resolvedAtMs = 0, expiresInSeconds = 60)
        assertFalse(shouldRetryPluginStream(SourceKind.PLUGIN, expiry, nowMs = 59_999))
        assertTrue(shouldRetryPluginStream(SourceKind.PLUGIN, expiry, nowMs = 60_000))
    }

    @Test fun `never retries the same stream twice`() {
        val usedUp = PluginStreamExpiry(resolvedAtMs = 0, expiresInSeconds = 60, retried = true)
        assertFalse(shouldRetryPluginStream(SourceKind.PLUGIN, usedUp, nowMs = 1_000_000))
    }

    // --- The dead-player bug's actual mechanism (finding 1): StateFlow drops an equal PlayerData ---

    @Test fun `an equal PlayerData republish is silently dropped by StateFlow -- the dead-player bug's root cause`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<PlayerData?>(null)
        val seen = mutableListOf<PlayerData?>()
        val job = launch { flow.collect { seen += it } }

        flow.value = samplePlayerData()
        // A re-resolve that came back with the exact same fields (routine on a fixed-path server,
        // e.g. the reference-server plugin's `/stream/<id>.mp4`): a DIFFERENT PlayerData instance,
        // but `equals()` to the one already published.
        flow.value = samplePlayerData()

        assertEquals(
            "StateFlow only notifies collectors when the new value differs from the current one; " +
                "an equal republish is silently conflated away -- this is why StreamExoPlayer never " +
                "saw the retry and stayed stuck showing its old error",
            listOf(null, samplePlayerData()),
            seen,
        )
        job.cancel()
    }

    @Test fun `going through null first forces the republish through even with identical fields -- the fix`() = runTest(UnconfinedTestDispatcher()) {
        val flow = MutableStateFlow<PlayerData?>(null)
        val seen = mutableListOf<PlayerData?>()
        val job = launch { flow.collect { seen += it } }

        flow.value = samplePlayerData()
        flow.value = null // PlayerViewModel.onMagisExoError's retry branch, before re-resolving
        flow.value = samplePlayerData() // the re-resolve returned the exact same fields

        assertEquals(listOf(null, samplePlayerData(), null, samplePlayerData()), seen)
        job.cancel()
    }
}
