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
import com.arkiv.player.cast.AudioTrackRef
import com.arkiv.player.cast.CastAudioChoice
import com.arkiv.player.data.subtitles.TitleSubtitleMemory
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
    // How many of [exoAudioGroups] are the container's own; the rest are a stream's side audio
    // merged in after them (see [audioMenu]), which no cast can carry.
    private var embeddedAudioCount = 0

    /** The preferred language was already applied for this playback. See [autoPickLanguageExo]. */
    private var alreadyAutoPickedExo = false

    /**
     * This title's remembered subtitle ([TitleSubtitleMemory]) still waits for its tracks: the
     * first report had none to match it against. See [restoreTitleSubtitle].
     */
    private var titleSubtitlePending = false

    /** Notices an audio-track fallback's rebuild under this playback. See [AudioFallbackRepick]. */
    private val fallbackRepick = AudioFallbackRepick()

    /**
     * The audio on the phone, in the shape a cast remux can carry, or null when there is none it
     * could: no tracks yet, or the one on is a stream's side audio (a separate file merged in by
     * `StreamExoPlayer`), which is not inside the transport stream the remux reads.
     *
     * Reads [curAudio] and [audioTracks], both snapshot state, so a composable reading this
     * recomposes when the person picks another audio -- that is what re-remuxes a cast.
     */
    val castAudioChoice: CastAudioChoice?
        get() = castAudioChoiceOf(curAudio, audioTracks.size, embeddedAudioCount) { i ->
            exoAudioGroups.getOrNull(i)?.takeIf { it.length > 0 }?.getFormat(0)
                ?.let { AudioTrackRef(it.id, it.language, it.label) }
        }

    /**
     * The subtitle on the phone, for the cast to mirror on the TV (see `CastSubtitles`): unknown
     * until the player has reported its text tracks, so a fresh screen does not switch the TV's
     * subtitle off before it knows. Snapshot reads ([curSpu], [spuTracks]): a composable reading
     * this recomposes when the person picks another one.
     */
    val castTextSelection: com.arkiv.player.cast.CastTextSelection
        get() = castTextSelectionOf(curSpu, spuTracks.size) { i ->
            exoSubGroups.getOrNull(i)?.takeIf { it.length > 0 }?.getFormat(0)?.let { it.id to it.language }
        }

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
        titleSubtitlePending = false
        fallbackRepick.reset()
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
        titleSubtitlePending = false
        fallbackRepick.reset()
        forgetTracks()
    }

    /** Empties the menu. The tracks on screen must never describe an item that isn't playing. */
    private fun forgetTracks() {
        exoAudioGroups = emptyList()
        exoSubGroups = emptyList()
        embeddedAudioCount = 0
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
     * Called from StreamExoPlayer's onTracksChanged callback.
     *
     * [pluginAudioTracks] is the stream's own separate audio tracks (see [ResolvedAudioTrack]),
     * merged into the video source in the same order right after its embedded ones -- see
     * [StreamExoPlayer]. `MergingMediaSource` reports one flat list, so [audioTrackLabel] is what
     * tells them apart by position and, for the plugin's own, labels them from its `lang`/`label`
     * instead of whatever (usually nothing) a raw audio file's own `Format` carries.
     *
     * It must be the list merged RIGHT NOW (`StreamExoPlayer` reports it with every change): after
     * an audio-track fallback drops a failing one, the rebuilt source has fewer groups, and labelling
     * them against the original list would name the embedded track after a dub and a dub after a
     * dead one. That rebuild also invalidates the previous choice (its override names groups that no
     * longer exist), so [fallbackRepick] chooses the same language again -- see [reapplyAfterFallback].
     */
    fun updateExoTracks(tracks: Tracks, pluginAudioTracks: List<ResolvedAudioTrack> = emptyList()) {
        // Before the menu is relabelled: what the person has now, named against the previous list.
        fallbackRepick.onReport(
            merged = pluginAudioTracks,
            audioLabel = nameOf(audioTracks, curAudio),
            spuLabel = if (curSpu >= 0) nameOf(spuTracks, curSpu) else null,
        )
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val subGroups   = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        exoAudioGroups = audioGroups.map { it.mediaTrackGroup }
        exoSubGroups   = subGroups.map { it.mediaTrackGroup }
        embeddedAudioCount = (audioGroups.size - pluginAudioTracks.size).coerceAtLeast(0)

        // Use the index as the id (for setOverrideForType).
        audioTracks = audioMenu(
            audioGroups.mapIndexed { i, group -> exoTrackLabel(group.getTrackFormat(0), audioFallbackLabel(i)) },
            pluginAudioTracks,
        )
        spuTracks = subGroups.mapIndexed { i, group ->
            i to exoTrackLabel(group.getTrackFormat(0), "S${i + 1}")
        }
        curAudio = audioGroups.indexOfFirst { it.isSelected }.coerceAtLeast(-1)
        curSpu   = subGroups.indexOfFirst   { it.isSelected }.coerceAtLeast(-1)
        android.util.Log.i("ExoTracks", "tracks updated: audio=${audioTracks.size} subs=${spuTracks.size} curAudio=$curAudio curSpu=$curSpu")
        val pending = fallbackRepick.take(audioTracks)
        if (pending != null) {
            reapplyAfterFallback(pending)
        } else if (alreadyAutoPickedExo) {
            if (titleSubtitlePending) restoreTitleSubtitle()
        } else {
            autoPickLanguageExo()
        }
    }

    /**
     * Turns on the subtitle the person last left this title with ([TitleSubtitleMemory]: the same
     * label, or one in the same language, or off), instead of the language decision. False when
     * there is nothing remembered, or nothing to match it yet (then [titleSubtitlePending]).
     */
    private fun restoreTitleSubtitle(): Boolean {
        val remembered = runCatching { graph.subtitlePrefs.titleSubtitle(episodeId) }.getOrNull()
        titleSubtitlePending = false
        if (remembered == null) return false
        if (remembered != TitleSubtitleMemory.OFF && spuTracks.isEmpty()) {
            titleSubtitlePending = true
            return false
        }
        val spu = TitleSubtitleMemory.restore(remembered, spuTracks) ?: return false
        android.util.Log.i("ExoTracks", "title subtitle: ${if (spu < 0) "off" else nameOf(spuTracks, spu)} (remembered '$remembered')")
        if (spu != curSpu) applySpuExo(spu)
        return true
    }

    /**
     * The source was rebuilt without a failing side audio track: choose again, on the NEW groups,
     * the audio the person was hearing (by its name, which the menu now gets right) and the
     * subtitle they had, or none if it was off. When their audio is gone -- it was the track that
     * failed -- or nothing had been chosen yet, the preference decides again, as on a fresh start.
     */
    private fun reapplyAfterFallback(pending: AudioFallbackRepick.Pending) {
        val audioId = trackIdByLabel(pending.audioLabel, audioTracks)
        if (audioId == null) {
            android.util.Log.i("ExoTracks", "audio fallback: '${pending.audioLabel}' is gone, the preference decides again")
            alreadyAutoPickedExo = false
            autoPickLanguageExo()
            return
        }
        android.util.Log.i("ExoTracks", "audio fallback: keeping '${pending.audioLabel}'")
        applyAudioExo(audioId)
        if (pending.spuLabel == null) {
            applySpuExo(-1)
        } else {
            trackIdByLabel(pending.spuLabel, spuTracks)?.let { applySpuExo(it) }
        }
        alreadyAutoPickedExo = true
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

        // A subtitle picked by hand on this title wins over the language rule ("Continuar").
        if (restoreTitleSubtitle() || titleSubtitlePending) return
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
        val code = fmt.language?.trim()?.takeIf { it.isNotEmpty() && !isUndeterminedLanguage(it) } ?: return fallback
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
        // The one already on, picked again: nothing changes here, but a TV that didn't take it gets
        // asked again (the Chromecast keeps no other way to retry short of picking another and back).
        if (id == curSpu || (id < 0 && curSpu < 0)) runCatching { graph.castSubtitles.reapply() }
        val exo = exoRef ?: return
        if (id < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            curSpu = -1
            rememberTitleSubtitle(TitleSubtitleMemory.OFF)
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
        val name = nameOf(spuTracks, id) ?: return
        rememberTitleSubtitle(name)
        promoteLanguage(name, spuTracks.realNames(), isAudio = false)
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

    /** A hand pick: this title reopens with it ([restoreTitleSubtitle]). */
    private fun rememberTitleSubtitle(choice: String) {
        titleSubtitlePending = false
        runCatching { graph.subtitlePrefs.rememberTitleSubtitle(episodeId, choice) }
    }

    private fun nameOf(tracks: List<Pair<Int, String>>, id: Int): String? =
        tracks.firstOrNull { it.first == id }?.second
}

@Composable
internal fun rememberTracksState(local: Player?, graph: AppGraph, episodeId: String): TracksState {
    return remember(local, graph, episodeId) { TracksState(graph, local, episodeId) }
}

/**
 * [TracksState.castAudioChoice], pure: the selected audio [selected] as a [CastAudioChoice], when it
 * is one of the container's own [embeddedCount] tracks (of [menuSize] in the menu) and [formatOf]
 * knows it.
 */
internal fun castAudioChoiceOf(
    selected: Int,
    menuSize: Int,
    embeddedCount: Int,
    formatOf: (Int) -> AudioTrackRef?,
): CastAudioChoice? {
    if (selected < 0 || selected >= menuSize || selected >= embeddedCount) return null
    val ref = formatOf(selected) ?: return null
    return CastAudioChoice(selected, ref.id, ref.language, ref.label)
}

/**
 * [TracksState.castTextSelection], pure: [selected] of [menuSize] text tracks, [formatOf] giving a
 * track's `Format.id` and language. No tracks reported yet is [CastTextSelection.Unknown].
 */
internal fun castTextSelectionOf(
    selected: Int,
    menuSize: Int,
    formatOf: (Int) -> Pair<String?, String?>?,
): com.arkiv.player.cast.CastTextSelection {
    if (menuSize == 0) return com.arkiv.player.cast.CastTextSelection.Unknown
    if (selected < 0 || selected >= menuSize) return com.arkiv.player.cast.CastTextSelection.Off
    val (id, language) = formatOf(selected) ?: return com.arkiv.player.cast.CastTextSelection.Off
    return com.arkiv.player.cast.CastTextSelection.On(id, language)
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

/**
 * Display name for the audio menu entry at index [id], when [pluginTracks] is a stream's own
 * separately-hosted audio tracks (see [ResolvedAudioTrack]), merged into the video source right
 * after the container's own [embeddedCount] ones -- `MergingMediaSource` reports one flat list of
 * groups, so [TracksState.updateExoTracks] doesn't know the split any other way, only their count
 * and order. An index below [embeddedCount] keeps [embeddedLabel] untouched (the container's own
 * `Format`, see `TracksState.exoTrackLabel`); at or past it, the plugin's own `label` wins, then its
 * `lang` classified the same way [LangTokens] reads any other track. Unlike the embedded case, an
 * unrecognized `lang` is shown uppercased rather than guessed from `Locale`: a plugin that wants a
 * precise name should send `label`.
 */
/**
 * ISO 639 codes that name no language: `und` (undetermined, what a muxer writes when nobody set
 * one), `mul`, `zxx` and `mis`. `Locale` has no name for them, so without this check the menu
 * showed the raw code uppercased ("UND") next to a plugin's properly named dub.
 */
internal fun isUndeterminedLanguage(code: String): Boolean =
    code.trim().lowercase() in setOf("und", "mul", "zxx", "mis")

/** The menu name of an ExoPlayer audio track with no usable language or label: the first is the file's own ("Original"). */
internal fun audioFallbackLabel(index: Int): String = if (index == 0) "Original" else "Audio ${index + 1}"

internal fun audioTrackLabel(id: Int, embeddedCount: Int, pluginTracks: List<ResolvedAudioTrack>, embeddedLabel: String): String {
    val extra = pluginTracks.getOrNull(id - embeddedCount) ?: return embeddedLabel
    extra.label.takeIf { it.isNotBlank() }?.let { return it }
    if (extra.lang.isBlank() || extra.lang.equals("und", ignoreCase = true)) return embeddedLabel
    LangTokens.classifyCode(extra.lang).takeIf { it != TrackLang.UNKNOWN }?.label()?.let { return it }
    return extra.lang.uppercase()
}

/**
 * The audio menu of an in-screen ExoPlayer: [groupLabels] is each audio group's own name (from its
 * `Format`, in the order ExoPlayer reports them) and [merged] the stream's side audio tracks merged
 * in after the container's own -- the ones merged NOW, which after a fallback is fewer than the
 * Stream listed. See [audioTrackLabel].
 */
internal fun audioMenu(groupLabels: List<String>, merged: List<ResolvedAudioTrack>): List<Pair<Int, String>> {
    val embeddedCount = (groupLabels.size - merged.size).coerceAtLeast(0)
    return groupLabels.mapIndexed { i, own -> i to audioTrackLabel(i, embeddedCount, merged, own) }
}

/** The id of the menu entry named exactly [label], or null (no label, or no such entry). */
internal fun trackIdByLabel(label: String?, tracks: List<Pair<Int, String>>): Int? =
    label?.let { wanted -> tracks.firstOrNull { it.second == wanted }?.first }

/**
 * Notices when a stream's merged side audio tracks change under the SAME playback -- which only
 * happens when `StreamExoPlayer`'s fallback drops a failing one and rebuilds the merged source --
 * and holds what the person had then, so it can be chosen again once the rebuilt source reports
 * its tracks. The rebuild is needed because the earlier choice was a `TrackSelectionOverride` on the
 * previous merge's `TrackGroup`s: on the new ones it matches nothing and ExoPlayer's default wins
 * (often the embedded original), while the one-shot language auto-pick is already spent.
 *
 * Pure (no player, no preferences) so it runs on the JVM; [TracksState] drives it.
 */
internal class AudioFallbackRepick {
    /** What the person had when the rebuild was noticed; [spuLabel] null = subtitles off. */
    data class Pending(val audioLabel: String?, val spuLabel: String?)

    private var merged: List<ResolvedAudioTrack>? = null
    private var pending: Pending? = null

    /** A new playback: nothing merged yet, nothing to re-pick. */
    fun reset() {
        merged = null
        pending = null
    }

    /**
     * Every track report, BEFORE the menu is relabelled: [merged] is the side audio merged now,
     * [audioLabel]/[spuLabel] what is selected, named against the previous report's list. The first
     * change is the one kept: a second failure before the tracks come back finds the menu already
     * emptied, and would lose the choice.
     */
    fun onReport(merged: List<ResolvedAudioTrack>, audioLabel: String?, spuLabel: String?) {
        val before = this.merged
        this.merged = merged
        if (before != null && before != merged && pending == null) pending = Pending(audioLabel, spuLabel)
    }

    /** Once the rebuilt source reports audio ([audioTracks] non-empty): the pending re-pick, consumed; else null. */
    fun take(audioTracks: List<Pair<Int, String>>): Pending? {
        if (audioTracks.isEmpty()) return null
        return pending.also { pending = null }
    }
}

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
    /** While casting: what the choices here do on the TV (see [castTracksNote]); null otherwise. */
    castNote: String? = null,
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
                if (castNote != null) {
                    Text(castNote, color = ArkivTextSecondary, modifier = Modifier.padding(bottom = 4.dp))
                }
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

/**
 * The line the audio and subtitles menu shows while casting, so the person knows what a choice does
 * on the TV and not only on the phone. Null when not casting.
 *
 * The TV gets the source's own subtitle files ([externalSubtitles]: a Xuper title's, a plugin's),
 * switched without reloading the video (see `CastSubtitles`); a subtitle embedded in the file never
 * reaches it (a remux writes none).
 */
internal fun castTracksNote(casting: Boolean, route: com.arkiv.player.cast.CastAudioRoute, externalSubtitles: List<ResolvedSub>?): String? {
    if (!casting) return null
    val audio = if (route == com.arkiv.player.cast.CastAudioRoute.REMUX) {
        "En la TV: si cambias el audio, se prepara de nuevo y sigue desde donde ibas (puede tardar un poco). "
    } else {
        "En la TV: este video no permite cambiar el audio, suena el que trae por defecto. "
    }
    val subtitles = if (externalSubtitles.isNullOrEmpty()) "Los subtítulos solo se ven en el teléfono."
    else "Los subtítulos que elijas aquí también cambian en la TV."
    return audio + subtitles
}
