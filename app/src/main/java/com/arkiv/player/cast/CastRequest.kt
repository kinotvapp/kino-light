package com.arkiv.player.cast

/** What gets sent to the Chromecast receiver. */
data class CastRequest(
    val uri: String,
    val mimeType: String,
    val episodeId: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val startPositionMs: Long,
    /**
     * How long the title really runs, or 0 when unknown.
     *
     * Needed because a fragmented MP4 served WHILE it is written cannot state its own length, and
     * the receiver then reads one off the fragments it happens to have -- five seconds for a
     * two-hour film. See [DurationAwareMediaItemConverter].
     */
    val durationMs: Long = 0,
    /**
     * Announce this as a LIVE stream rather than a fixed-length one.
     *
     * For a file that is still being WRITTEN, "buffered" is a lie: it tells the receiver the media
     * has a definite end, so it works one out from the fragments that have arrived and plays
     * toward it. Measured on the KALLEY -- `kDurationChanged 75.25 … 80.25`, a new duration every
     * second or two -- and every time playback caught that moving end it stalled. A live stream
     * has no end to reach, which is the truth here and also what stops the chase.
     */
    val asLive: Boolean = false,
    /**
     * Where this media BEGINS inside the title, in ms.
     *
     * A remux is clipped to start where playback was, so the receiver counts from its own zero
     * while the title is minutes further along. Everything that records a position -- the progress
     * the app saves, the bar -- has to add this back or it files minute 62 as minute 0.
     */
    val offsetMs: Long = 0,
)

/**
 * Derives the cast request from the source. Pure: testable without Android.
 *
 * Local and magis: `castUrl` (mp4 h.264, compatible with the receiver) is preferred over
 * `mediaUrl` when it's set -- archive.org and web, which were the origin of this case, were
 * removed in this branch's pruning.
 * Live (Task 18): the URL is that of the LOCAL HTTP server (the `LiveHlsProxy` proxy) reachable
 * over the LAN -- `mediaUrl` is always the loopback the local player consumes on this same device, and
 * `castUrl` doesn't exist for live channels (there's never a fallback mp4 h.264, it's a live
 * feed). A live feed also has no "where you were": `startPositionMs` is forced to 0 no matter
 * what's requested, and the MIME is always that of an HLS playlist, not what the file extension
 * would guess (`mimeForUrl` doesn't know `.m3u8`).
 *
 * `requiresLanUrl` is live's URL rule without the rest of live's behaviour, for a source whose
 * origin IS remote but that the receiver still cannot fetch by itself. Magis is the case: its VOD
 * is served behind `Content-Auth`/`Content-License`, and the Cast Default Media Receiver has no
 * way to send custom headers (only a custom receiver app could), so the CDN answers it 401. Our
 * proxy is the only thing that adds those headers, so the receiver must come through it -- and
 * when there's no LAN url, the honest answer is `null`, not a fallback to a URL that will fail on
 * the TV with nothing in our logs to explain it. Unlike live, a Magis VOD does keep its
 * `startPositionMs`.
 */
object CastRequestBuilder {

    /** The Chromecast Default Media Receiver decides whether to open the stream as HLS from this. */
    private const val MIME_HLS = "application/vnd.apple.mpegurl"

    @Suppress("LongParameterList")
    fun build(
        episodeId: String,
        title: String,
        subtitle: String,
        artworkUrl: String,
        mediaUrl: String,
        castUrl: String?,
        lanUrl: String?,
        startPositionMs: Long,
        isLive: Boolean = false,
        mimeOverride: String? = null,
        requiresLanUrl: Boolean = false,
        durationMs: Long = 0,
        asLive: Boolean = false,
        offsetMs: Long = 0,
    ): CastRequest? {
        val uri = when {
            isLive || requiresLanUrl -> lanUrl
            else -> castUrl?.takeIf { it.isNotBlank() } ?: mediaUrl
        }
        if (uri.isNullOrBlank()) return null
        return CastRequest(
            uri = uri,
            mimeType = when {
                isLive -> MIME_HLS
                // What the bytes say, when the caller could read them. [mimeForUrl] guesses from the
                // extension, and the local file server's URL has none ("…/file"), so it always fell
                // back to mp4 while the server served the real thing -- we announced one container
                // and delivered another, which is exactly the mistake this file's KDoc warns about.
                mimeOverride != null -> mimeOverride
                else -> mimeForUrl(uri)
            },
            episodeId = episodeId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            startPositionMs = if (isLive) 0L else startPositionMs.coerceAtLeast(0),
            // A live channel has no length to state; anything else passes through what it knows.
            durationMs = if (isLive) 0L else durationMs.coerceAtLeast(0),
            asLive = asLive,
            offsetMs = offsetMs.coerceAtLeast(0),
        )
    }

    /**
     * MIME by extension. The receiver decides based on this, so getting it wrong is paid for with
     * a video that doesn't start, or starts with no sound.
     *
     * Here the bytes can't be inspected (the URL is remote, there's no file to open), so the
     * extension is all there is; what IS shared with the rest of the app is the TABLE, so there
     * aren't three different versions of "what MIME does a .ts have" floating around. See
     * [com.arkiv.player.playback.VideoContainer].
     */
    internal fun mimeForUrl(url: String): String =
        com.arkiv.player.playback.VideoContainer.mimeByName(url)
}
