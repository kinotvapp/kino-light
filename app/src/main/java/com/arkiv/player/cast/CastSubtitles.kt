package com.arkiv.player.cast

/**
 * What one Chromecast load carries for subtitles: how ([delivery]), every subtitle the title has
 * ([tracks]) and the one to start with ([activeId], null = none).
 */
data class CastTextLoad(val delivery: CastSubtitleDelivery, val tracks: List<CastTextTrack>, val activeId: Long?)

/**
 * The cast's subtitles, app-wide like the cast itself: the title's external subtitles (offered by the
 * player screen), the one the phone's menu has on, and what each TV load gets.
 *
 * The choice lives here, not on the screen, so it outlives it: a reconnect or a retry of the cast
 * with the player closed still loads the subtitle that was on, exactly like the audio.
 * `CastSessionManager` asks [forLoad] on every load and keeps the receiver's active text track on
 * [wanted]; `RemuxHlsServer` asks [manifestRenditions]; DLNA asks [dlnaSidecar].
 */
class CastSubtitles(
    val server: CastSubtitleServer,
    /** `debug.kino.cast_subs` (see [CastSubtitleDelivery]), read off the main thread; null = unset. */
    private val deliveryOverride: () -> String? = { null },
) {
    /** The title the choice belongs to, and the choice: an index into its sources, null = off. */
    @Volatile private var choiceEpisode: String? = null
    @Volatile private var choice: Int? = null

    /** The last Chromecast load's subtitles, for its manifest and for keeping its active track. */
    @Volatile private var loaded: Loaded? = null

    private class Loaded(val episodeId: String, val load: CastTextLoad)

    /** Read once per offer, off the main thread (it may spawn `getprop`). */
    @Volatile private var override: String? = null

    /** Called (any thread) when the phone's choice changes: the cast applies it to the receiver. */
    @Volatile var onChoiceChanged: () -> Unit = {}

    /**
     * The title on screen and its subtitles, fetched with [fetch] (the phone's own client for that
     * source). Off the main thread. The same title keeps its choice; another one starts with none
     * until the phone reports its own.
     */
    fun offer(episodeId: String, sources: List<CastSubtitleSource>, fetch: (String) -> ByteArray?) {
        override = runCatching(deliveryOverride).getOrNull()
        server.offer(episodeId, sources, fetch)
        if (choiceEpisode != episodeId) {
            choiceEpisode = episodeId
            choice = null
        }
        // The phone may have reported its choice before the offer landed: apply it now.
        lastSelection?.takeIf { it.first == episodeId }?.let { select(it.first, it.second) }
    }

    /** The phone's last reported selection, kept until the offer it refers to arrives. */
    @Volatile private var lastSelection: Pair<String, CastTextSelection>? = null

    /** The phone's subtitle menu now has [selection] on for [episodeId]. */
    fun select(episodeId: String, selection: CastTextSelection) {
        if (selection == CastTextSelection.Unknown) return
        lastSelection = episodeId to selection
        val sources = server.sourcesFor(episodeId)
        if (sources.isEmpty()) return
        val index = CastTextTracks.indexOf(selection, sources)
        if (choiceEpisode == episodeId && choice == index) return
        choiceEpisode = episodeId
        choice = index
        CastDiag.i("subtitle on the phone → ${index?.let { "#$it (${sources[it].lang})" } ?: "off"}")
        onChoiceChanged()
    }

    /** The choice for [episodeId], or null (off, or another title). */
    private fun choiceFor(episodeId: String): Int? = choice.takeIf { choiceEpisode == episodeId }

    /**
     * The subtitles for a load of [r], or null for none (no subtitles offered for it, a live
     * stream, no LAN address). Remembered as the load in course.
     */
    fun forLoad(r: CastRequest): CastTextLoad? {
        val load = buildLoad(r)
        loaded = load?.let { Loaded(r.episodeId, it) }
        if (load != null) {
            CastDiag.i("subtitles for the TV: ${load.tracks.size} via ${load.delivery}, on=${load.activeId ?: "none"}")
        }
        return load
    }

    private fun buildLoad(r: CastRequest): CastTextLoad? {
        if (r.asLive && !r.hlsFmp4) return null
        val sources = server.sourcesFor(r.episodeId)
        if (sources.isEmpty()) return null
        val delivery = CastTextTracks.deliveryFor(r.hlsFmp4, override)
        if (delivery == CastSubtitleDelivery.OFF) return null
        val base = server.baseUrl(r.episodeId, r.offsetMs) ?: return null
        val tracks = CastTextTracks.build(sources, { i ->
            if (delivery == CastSubtitleDelivery.MANIFEST) CastSubtitleRoutes.segmentBase(base, i)
            else CastSubtitleRoutes.fileUrl(base, i, "vtt")
        })
        if (tracks.isEmpty()) return null
        val active = choiceFor(r.episodeId)?.let { i -> tracks.firstOrNull { it.index == i }?.id }
        return CastTextLoad(delivery, tracks, active)
    }

    /** The subtitle the receiver should have on for [episodeId] in the load in course, or null = none. */
    fun wanted(episodeId: String): CastTextTrack? {
        val l = loaded?.takeIf { it.episodeId == episodeId } ?: return null
        val i = choiceFor(episodeId) ?: return null
        return l.load.tracks.firstOrNull { it.index == i }
    }

    /** Does the load in course for [episodeId] carry subtitles at all? */
    fun hasTracks(episodeId: String): Boolean = loaded?.episodeId == episodeId

    /** Was the load in course for [episodeId] given its subtitles in the manifest? */
    fun inManifest(episodeId: String): Boolean =
        loaded?.takeIf { it.episodeId == episodeId }?.load?.delivery == CastSubtitleDelivery.MANIFEST

    /** The remux master playlist's subtitle renditions: the load in course's, when it went that way. */
    fun manifestRenditions(): List<com.arkiv.player.playback.RemuxHls.SubtitleRendition> {
        val l = loaded?.load?.takeIf { it.delivery == CastSubtitleDelivery.MANIFEST } ?: return emptyList()
        return l.tracks.map { com.arkiv.player.playback.RemuxHls.SubtitleRendition(it.name, it.language, it.url) }
    }

    /**
     * What a DLNA cast of the title on screen sends along: the phone's choice and the rest, as SRT on
     * the timeline of what the TV plays -- the title's, moved back by [offsetMs] when that is a remux
     * started mid-title (`TsStart`). Null without subtitles.
     */
    internal fun dlnaSidecar(offsetMs: Long = 0L): com.arkiv.player.dlna.DlnaSidecar? {
        val episodeId = server.episodeId ?: return null
        val sources = server.sourcesFor(episodeId)
        if (sources.isEmpty()) return null
        val base = server.baseUrl(episodeId, offsetMs) ?: return null
        // Not capped here: the selected one may be past the cap, and DlnaSubtitles caps after it.
        val all = sources.mapIndexed { i, s ->
            com.arkiv.player.dlna.DlnaSubtitle(
                CastSubtitleRoutes.fileUrl(base, i, "srt"),
                CastTextTracks.languageTag(s.lang),
                CastTextTracks.displayName(s.lang, i),
            )
        }
        val selected = choiceFor(episodeId)?.let { all.getOrNull(it) }
        return com.arkiv.player.dlna.DlnaSidecar(selected, all.filter { it != selected })
    }
}
