package com.arkiv.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.arkiv.player.AppGraph
import com.arkiv.player.playback.LangPromotion
import com.arkiv.player.playback.LangTokens
import com.arkiv.player.playback.SubtitleDecision
import com.arkiv.player.playback.TrackLang
import com.arkiv.player.playback.TrackSelector
import com.arkiv.player.ui.settings.label
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary
import kotlinx.coroutines.delay

/**
 * Player audio and subtitles. The tracks come from ExoPlayer: the in-screen player that is
 * bound ([setExoPlayer]) or, by default, the local player `PlaybackService` hosts for downloaded
 * files, reached through the screen's `MediaController` ([local]).
 *
 * Split out of [PlayerContent] because it's several variables that nobody else on that screen
 * reads: the only crossover with the rest is the controls' CC icon, which asks whether any
 * subtitle is on. While at it, the two rules that did carry logic —how a track with no language
 * gets labeled and when picking by hand changes your preference— end up as pure functions down
 * below, outside the composable and finally reachable from a test.
 */
@Stable
internal class TracksState(
    private val graph: AppGraph,
    /**
     * The local (service-hosted) player, through the screen's `MediaController`: what tracks are
     * read from and chosen on when no in-screen ExoPlayer is bound. The controller proxies
     * `currentTracks` and `trackSelectionParameters`, overrides included (media3 maps the track
     * groups back to the player's own).
     */
    private val local: Player?,
    /**
     * The episode this screen opened. It tells the local player's tracks apart from the PREVIOUS
     * download's, which the service player still holds while this screen resolves its own.
     */
    private val episodeId: String,
) {
    /** The audio/subtitles menu is open. */
    var pickerOpen by mutableStateOf(false)
        private set

    /** Subtitle tracks embedded in the file, as (id, name). */
    var spuTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList())
        private set

    /** Embedded audio tracks (dual releases: Latin American / English). */
    var audioTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList())
        private set

    var curSpu by mutableIntStateOf(-1)
        private set

    var curAudio by mutableIntStateOf(-1)
        private set

    // The player tracks are read from and chosen on: an in-screen ExoPlayer while one is bound,
    // otherwise [local]. Updated from PlayerScreen.
    private var exoRef: Player? = local
    // TrackGroups ExoPlayer detected, to select with setOverrideForType.
    private var exoAudioGroups: List<TrackGroup> = emptyList()
    private var exoSubGroups: List<TrackGroup> = emptyList()

    /** The preferred language was already applied for this playback. See [autoPickLanguageExo]. */
    private var alreadyAutoPickedExo = false

    /** An embedded subtitle is on. Read by the controls' CC icon. */
    var subsOn by mutableStateOf(false)
        private set

    /** Whether any subtitle is on. The only thing the controls' CC icon looks at. */
    val hasSubtitle: Boolean get() = subsOn

    fun openPicker() {
        refresh()
        pickerOpen = true
    }

    fun closePicker() {
        pickerOpen = false
    }

    /** Binds the in-screen ExoPlayer that is playing. Null = back to the local (service) player. */
    fun setExoPlayer(player: Player?) {
        exoRef = player ?: local
        // Every playback decides the language again: what was hand-picked in the previous one
        // doesn't carry over to the next (see [autoPickLanguageExo]).
        alreadyAutoPickedExo = false
        if (player == null) {
            forgetTracks()
            // Back on the local player: its tracks replace the in-screen player's in the menu.
            local?.let { onLocalTracksChanged(it.currentTracks) }
        }
    }

    /**
     * A local item is (re)loading: forget the previous one's tracks and let the language be decided
     * again. Without this the one-shot auto-pick is spent on whatever the service player was holding.
     */
    fun onLocalItemLoad() {
        alreadyAutoPickedExo = false
        forgetTracks()
    }

    /** Empties the menu. The tracks on screen must never describe an item that isn't playing. */
    private fun forgetTracks() {
        exoAudioGroups = emptyList()
        exoSubGroups = emptyList()
        audioTracks = emptyList()
        spuTracks = emptyList()
        curAudio = -1
        curSpu = -1
        subsOn = false
    }

    /**
     * Tracks reported by the local player's `onTracksChanged`. Ignored while an in-screen ExoPlayer
     * is bound: the controller keeps reporting (an empty or stale item) and would overwrite its lists.
     */
    fun onLocalTracksChanged(tracks: Tracks) {
        if (local == null || exoRef !== local) return
        // While this screen resolves its own item, the service player is still playing the previous
        // download and reporting ITS tracks: they must neither fill this menu nor spend the auto-pick.
        if (tracksBelongToEpisode(local.currentMediaItem?.mediaId, episodeId)) {
            updateExoTracks(tracks)
        } else {
            forgetTracks()
        }
    }

    /**
     * Populates audio and subtitle from the tracks ExoPlayer reports via onTracksChanged.
     * Called from MagisExoPlayer's onTracksChanged callback.
     */
    fun updateExoTracks(tracks: Tracks) {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val subGroups   = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        exoAudioGroups = audioGroups.map { it.mediaTrackGroup }
        exoSubGroups   = subGroups.map { it.mediaTrackGroup }

        // Use the index as the id (for setOverrideForType).
        audioTracks = audioGroups.mapIndexed { i, group ->
            i to exoTrackLabel(group.getTrackFormat(0), "A${i + 1}")
        }
        spuTracks = subGroups.mapIndexed { i, group ->
            i to exoTrackLabel(group.getTrackFormat(0), "S${i + 1}")
        }
        curAudio = audioGroups.indexOfFirst { it.isSelected }.coerceAtLeast(-1)
        curSpu   = subGroups.indexOfFirst   { it.isSelected }.coerceAtLeast(-1)
        android.util.Log.i("ExoTracks", "tracks updated: audio=${audioTracks.size} subs=${spuTracks.size} curAudio=$curAudio curSpu=$curSpu")
        autoPickLanguageExo()
    }

    /**
     * Applies your preferred audio and subtitle language, once per playback.
     *
     * [TrackSelector] decides the audio and [SubtitleDecision] the subtitle (it turns them off when
     * the audio is already understood); without this ExoPlayer chose on its own and the preference
     * was never applied. It shows with magis, whose files carry eight audio tracks.
     *
     * Only the first time: `onTracksChanged` also fires when a track is switched, and deciding again
     * there would overwrite what you just picked by hand. The flag is cleared for each new playback:
     * [setExoPlayer] when an in-screen player takes over, [onLocalItemLoad] for each local item.
     */
    private fun autoPickLanguageExo() {
        if (alreadyAutoPickedExo) return
        if (audioTracks.isEmpty() && spuTracks.isEmpty()) return
        alreadyAutoPickedExo = true

        val prefs = graph.subtitlePrefs.prefs.value
        // elegirAudio/elegirSpu are NOT used: those promote the language picked into your
        // preferences, and only a pick FROM YOU can do that. Letting the automatic one
        // self-confirm would leave the preference pinned to whatever it chose the first time.
        //
        // The names already arrive translated ("Español (genérico)", "Japonés"), so they're
        // classified as free text and not as an ISO code.
        TrackSelector.select(audioTracks, prefs.audioLangs, requireChoice = true)
            ?.takeIf { it != curAudio }
            ?.let { picked ->
                android.util.Log.i("ExoTracks", "auto-audio: ${nameOf(audioTracks, picked)} (was ${nameOf(audioTracks, curAudio)})")
                applyAudioExo(picked)
            }

        val spu = SubtitleDecision.decide(
            audioTrackName = nameOf(audioTracks, curAudio),
            spuTracks = spuTracks,
            prefs = prefs,
            spuClassifier = LangTokens::classify,
        )
        if (spu != curSpu) {
            android.util.Log.i("ExoTracks", "auto-subtitle: ${if (spu < 0) "off" else nameOf(spuTracks, spu)}")
            applySpuExo(spu)
        }
    }

    /** Sets the track on ExoPlayer and updates the state, without touching your language preferences. */
    private fun applyAudioExo(id: Int) {
        val exo = exoRef ?: return
        exoAudioGroups.getOrNull(id)?.let { group ->
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, 0))
                .build()
            curAudio = id
        }
    }

    private fun applySpuExo(id: Int) {
        val exo = exoRef ?: return
        if (id < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            curSpu = -1
            return
        }
        exoSubGroups.getOrNull(id)?.let { group ->
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group, 0))
                .build()
            curSpu = id
        }
    }

    /**
     * What an ExoPlayer track is called in the menu.
     *
     * Tried in three steps because none alone is enough:
     *  1. The label the file itself carries, if it has one — nobody describes the track better.
     *  2. [LangTokens], the one that knows how to tell Latin American Spanish from Castilian.
     *     That distinction matters and `Locale` doesn't make it: to it, everything is "español".
     *  3. [java.util.Locale], for whatever falls off [TrackLang]'s map. That enum was written for
     *     Spanish-language torrents —it only covers Latin American, Castilian, English, and
     *     Japanese— and magis serves eight languages: without this step, Portuguese, German,
     *     French, and Italian all came out as "Desconocido" in the same list.
     *
     * Watch the code ExoPlayer sends: it's ISO 639-1, so Japanese comes as `ja` and [LangTokens]'s
     * table only has `jp` and `jpn`. Step 3 covers it.
     */
    private fun exoTrackLabel(fmt: Format, fallback: String): String {
        fmt.label?.takeIf { it.isNotBlank() }?.let { return it }
        val code = fmt.language?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
        LangTokens.classifyCode(code)
            .takeIf { it != TrackLang.UNKNOWN }
            ?.label()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // `forLanguageTag` swallows anything and returns empty if it doesn't understand it, so the
        // fallback is still needed.
        val name = java.util.Locale.forLanguageTag(code)
            .getDisplayLanguage(java.util.Locale("es"))
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.uppercase() }
            ?: code.uppercase()
    }

    /**
     * Re-reads the local player's tracks before opening the menu, in case an `onTracksChanged` was
     * missed (re-entering an item that was already playing). In-screen players push theirs through
     * [updateExoTracks] and are not polled.
     */
    fun refresh() {
        val exo = exoRef ?: return
        if (exo === local) onLocalTracksChanged(exo.currentTracks)
    }

    /**
     * Syncs [subsOn] with what's on. Called by the screen's playback polling loop, which is the
     * one that knows how often it's worth checking.
     */
    fun syncSubsOn() {
        subsOn = curSpu >= 0
    }

    fun chooseAudio(id: Int) {
        val exo = exoRef ?: return
        val group = exoAudioGroups.getOrNull(id)
        if (group != null) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, 0))
                .build()
        }
        curAudio = id
        promoteLanguage(nameOf(audioTracks, id) ?: return, audioTracks.realNames(), isAudio = true)
    }

    fun chooseSpu(id: Int) {
        val exo = exoRef ?: return
        if (id < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            curSpu = -1
            return
        }
        val group = exoSubGroups.getOrNull(id)
        if (group != null) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group, 0))
                .build()
        }
        curSpu = id
        promoteLanguage(nameOf(spuTracks, id) ?: return, spuTracks.realNames(), isAudio = false)
    }

    /**
     * Picking a track by hand promotes that language to the top of the preference — but only if
     * the file had more than one language (see LangPromotion: with no alternative, picking
     * expresses no preference).
     */
    private fun promoteLanguage(pickedName: String, allNames: List<String>, isAudio: Boolean) {
        val prefs = graph.subtitlePrefs.prefs.value
        val updatedOrder = LangPromotion.promote(
            order = if (isAudio) prefs.audioLangs else prefs.subtitleLangs,
            pickedName = pickedName,
            allNames = allNames,
            classifier = if (isAudio) LangTokens::classify else LangTokens::classifyFileName,
        ) ?: return
        val updated = if (isAudio) prefs.copy(audioLangs = updatedOrder) else prefs.copy(subtitleLangs = updatedOrder)
        graph.subtitlePrefs.update(updated)
    }

    private fun nameOf(tracks: List<Pair<Int, String>>, id: Int): String? =
        tracks.firstOrNull { it.first == id }?.second
}

@Composable
internal fun rememberTracksState(local: Player?, graph: AppGraph, episodeId: String): TracksState {
    return remember(local, graph, episodeId) { TracksState(graph, local, episodeId) }
}

/**
 * Whether the local player's tracks describe the episode this screen opened.
 *
 * The service player keeps playing the previous download while a new screen resolves its own item
 * (that is the point of playing in the background), so its tracks arrive at the new screen first.
 * Taking them would fill the menu with the previous episode's tracks and, worse, spend the one-shot
 * language auto-pick on them, leaving the real item with whatever ExoPlayer chose by itself.
 */
internal fun tracksBelongToEpisode(mediaIdOnThePlayer: String?, episodeId: String): Boolean =
    mediaIdOnThePlayer != null && mediaIdOnThePlayer == episodeId

/** The container's real tracks: negative ids are the menu's synthetic entries. */
internal fun List<Pair<Int, String>>.realTracks(): List<Pair<Int, String>> = filter { it.first >= 0 }

internal fun List<Pair<Int, String>>.realNames(): List<String> = realTracks().map { it.second }

/**
 * Display name of a subtitle track. The Magis MPEG-TS carries them without a language, so their
 * names say nothing. When the source declared the languages (same order as the tracks) the language
 * is prefixed; otherwise the raw name stays.
 *
 * It covers the first N tracks by id, which are the container's; past that it doesn't guess.
 * Outside Magis ([isMagis] false) the language list doesn't describe these tracks, and labeling
 * them with it would be lying in the menu.
 */
internal fun spuLabel(
    id: Int,
    name: String,
    isMagis: Boolean,
    declaredLanguages: List<String>,
    spuTracks: List<Pair<Int, String>>,
): String {
    if (id < 0 || !isMagis) return name
    val real = spuTracks.realTracks().sortedBy { it.first }
    val i = real.indexOfFirst { it.first == id }
    if (i < 0 || i >= declaredLanguages.size) return name
    val lang = LangTokens.classifyCode(declaredLanguages[i])
    if (lang == TrackLang.UNKNOWN) return name
    return "${lang.label()} · $name"
}

/**
 * Audio and subtitles menu: the tracks the container (file or stream) carries.
 *
 * [isMagis] and [declaredLanguages] are the only things the dialog needs to know about the
 * source, and only to label tracks with no language (see [spuLabel]).
 */
@Composable
internal fun AudioAndSubtitlesDialog(
    state: TracksState,
    isMagis: Boolean,
    declaredLanguages: List<String>,
) {
    if (!state.pickerOpen) return
    // Only turns off the flag: focus is handled by the screen's LaunchedEffect(pickerOpen), the
    // same path picking a track follows.
    val close = { state.closePicker() }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("Audio y subtítulos") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                // AUDIO (dual releases: Latin American / English).
                if (state.audioTracks.realTracks().size > 1) {
                    SectionTitle("Audio", first = true)
                    state.audioTracks.realTracks().forEach { (id, name) ->
                        TextButton(onClick = { state.chooseAudio(id) }) {
                            Text(
                                (if (id == state.curAudio) "✓ " else "") + name,
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // FILE SUBTITLES (embedded).
                SectionTitle("Subtítulos del archivo")
                if (state.spuTracks.realTracks().isEmpty()) {
                    Text(
                        "Este archivo no trae subtítulos embebidos.",
                        color = ArkivTextSecondary, modifier = Modifier.padding(8.dp),
                    )
                    TextButton(onClick = { state.chooseSpu(-1) }) {
                        Text((if (state.curSpu < 0) "✓ " else "") + "Desactivar", color = Color.White)
                    }
                } else {
                    (listOf(-1 to "Desactivar") + state.spuTracks.realTracks()).forEach { (id, name) ->
                        TextButton(onClick = { state.chooseSpu(id) }) {
                            Text(
                                (if (id == state.curSpu) "✓ " else "") +
                                    spuLabel(id, name, isMagis, declaredLanguages, state.spuTracks),
                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { close() }) { Text("Cerrar") } },
    )
}

@Composable
private fun SectionTitle(text: String, first: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = ArkivRed,
        modifier = Modifier.padding(top = if (first) 8.dp else 12.dp, bottom = 2.dp),
    )
}
