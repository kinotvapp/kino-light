package com.arkiv.player.data.gateway

class GatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Same shape as [GatewayException], but for a rejection the portal itself explains as a
 * geographic/licensing restriction (Xuper's `portal100024` -- see
 * `com.arkiv.player.data.magis.PORTAL_ERROR_MESSAGES`'s KDoc for how that was confirmed) rather
 * than anything wrong with the request. A separate type so the player can show it as its own
 * dialog instead of the generic resolution-error pill -- that pill reads like a bug in Kino;
 * this is the portal itself saying no.
 */
class GatewayBlockedException(message: String) : RuntimeException(message)

/**
 * What's being searched for. Only the fields the actual source uses are left: `year`,
 * `anilistId`, `lang`, `sources`, `maxBytes` and `budgetMs` were gateway parameters -filter by
 * language, pick sources, cap torrents, cut off by time- and the Magis portal receives none of
 * that. Keeping them was promising a filter nobody applies.
 *
 * [tmdbId] IS used, and not for filtering: that's where the ORIGINAL title comes from that ranks
 * what the portal returns (see `MagisSource.formasDelTitulo`).
 */
data class GatewaySearchQuery(
    val q: String,
    val type: String = "movie",
    val season: Int = 0,
    val episode: Int = 0,
    val tmdbId: Int = 0,
)

/** A search result, built by the source (today `MagisSource`) against what the portal returns. */
data class GatewayResult(
    val source: String,
    val title: String,
    val ref: String,
    val kind: String = "movie",
    val lang: String = "",
    val quality: String = "",
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val year: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val extra: Map<String, String> = emptyMap(),
)

/**
 * The playable thing Magis's resolution returns (`MagisResolve`/`MagisLive`).
 *
 * [headers] is generic on purpose: it covers the web sources' Referer/User-Agent and magis's
 * Content-Auth/Content-License with no need for a field per source.
 */
data class GatewayPlayable(
    val kind: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mime: String = "",
    val expiresAt: String = "",
    val fallbackUrl: String? = null,
    /** Tracks the stream carries. Today the only source that populates this field is Magis, which
     *  sends them along with the play resolution (`MagisResolve.subtitulos`, see `MagisSource`). */
    val subtitles: List<GatewaySubtitle> = emptyList(),
    /**
     * Real duration in ms when the source knows it (0 = it doesn't).
     *
     * Exists because of magis's raw MPEG-TS: it doesn't carry it in any header and libVLC
     * couldn't deduce it over HTTP either, so without this value both ends of the file have to be
     * downloaded to read its PCR ([com.arkiv.player.playback.TsDurationProbe]) against a CDN that
     * takes between 0.2s and 20s to answer a range. When that probe loses, the movie ends up with
     * a full progress bar, 00:00 on the right, and no way to seek forward. The portal already
     * knows how long it runs: this carries that value.
     */
    val durationMs: Long = 0L,
    /** Video codec the source reports ("h264", "h265"…); "" if unknown. */
    val videoCodec: String = "",
    /**
     * Container exactly as the SOURCE names it ("ts", "mp4"…); "" if unknown.
     *
     * This is the value the app uses to declare the container to the demuxer before opening it.
     * It used to be deduced from [url]'s extension, which for magis isn't data from the source
     * but something `MagisResolve` builds by collapsing to `.mp4` everything the portal doesn't
     * call `ts`. "" = probe, never assume.
     */
    val container: String = "",
    /** Widevine license server URL; "" = no DRM (play directly).
     *  Exists because of Ditu (Caracol Streaming): its stream is MPEG-DASH with Widevine and
     *  ExoPlayer negotiates it automatically via MediaItem.DrmConfiguration. */
    val drmLicenseUrl: String = "",
    /** Extra headers for the DRM license request (e.g. Cookie: playback_token=…). */
    val drmLicenseHeaders: Map<String, String> = emptyMap(),
)

data class GatewaySubtitle(val lang: String, val url: String, val format: String = "")

/**
 * A season's chapter (Magis's or Caracol's).
 *
 * [still], [tmdbTitle] and [overview] are added by `MagisSource`, on the client itself, by
 * crossing the IMDb id the portal publishes against TMDB: the portal has NO image or real name
 * per chapter (its per-chapter `posterList` always comes back empty). Optional on purpose -- if
 * TMDB didn't resolve, the chapter shows with [title], which is the portal's.
 */
data class GatewayEpisode(
    val number: Int,
    val title: String,
    val ref: String,
    val still: String? = null,
    val tmdbTitle: String? = null,
    val overview: String? = null,
    /**
     * THIS chapter's season, when the source knows it per chapter; null = it doesn't say. Magis
     * doesn't send it: each of its seasons is a separate result, and its number travels in
     * [GatewaySeries.seasonNumber].
     *
     * Exists because of Caracol: a `GROUP_OF_BUNDLES` arrives as ONE list with all its seasons
     * flattened (`DituEpisodes`), and each season can bring its own chapter 1. With no season
     * alongside it, [number] alone isn't enough to know which is which.
     */
    val season: Int? = null,
)

/**
 * The series a season belongs to, when `MagisSource` was able to identify it (see its KDoc: it
 * travels whenever the portal gave an imdb, even if enrichment didn't come out).
 *
 * [title] is the name TMDB knows it by ("Neon Genesis Evangelion"), not the portal's ("Shin
 * seiki evangerion Temp.1"): it's what the library adopts as `tituloCanonico`. Comes **empty**
 * when TMDB didn't resolve -- not null, so that "there's no name" is one question, not two.
 */
data class GatewaySeries(
    val imdbId: String,
    val tmdbId: Int,
    val seasonNumber: Int,
    val title: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
)

/**
 * What the source emits while it searches. The event format is kept -instead of returning a
 * list- because the screen paints results as they arrive, and so the source's start/end/error
 * are explicit states, not absences.
 *
 * These events used to come as NDJSON from the gateway and `parseSearchEvent` built them; now
 * `MagisSource` emits them directly, so that parser is gone (and with it the `Unknown` event,
 * which existed to be able to ignore lines from a newer server).
 */
sealed interface SearchEvent {
    data class SourceStart(val source: String) : SearchEvent
    data class ResultEvent(val source: String, val item: GatewayResult) : SearchEvent
    data class SourceDone(val source: String, val count: Int, val ms: Long) : SearchEvent
    /**
     * [cause] is the exception, when the source has it on hand: `CaracolFailure` needs it to tell
     * the person what happened. `DituSource` sends it; `MagisSource` and `CompositeSource` don't.
     */
    data class SourceError(
        val source: String,
        val error: String,
        val ms: Long,
        val count: Int,
        val cause: Throwable? = null,
    ) : SearchEvent
    data class Done(val ms: Long) : SearchEvent
}

/** `program_type` values whose result is a whole season, not something playable. */
val MAGIS_SERIES = setOf("teleplay", "series", "variety")
