package com.arkiv.player.data.caracol

/**
 * A representation from the DASH manifest, reduced to what's needed to choose.
 *
 * Not `androidx.media3...Representation` on purpose: that way the decision is tested on the JVM,
 * with no manifest, no network and no Android.
 */
data class CaracolTrack(
    /** Index of the AdaptationSet within the period. */
    val group: Int,
    /** Index of the Representation within its AdaptationSet. */
    val track: Int,
    val isVideo: Boolean,
    /** Height in pixels; 0 on audio. */
    val height: Int,
    val bitsPerSecond: Int,
)

/**
 * What quality is downloaded for a Caracol episode.
 *
 * Exists because the manifest offers six qualities and downloading them all makes no sense: ONE
 * video has to be chosen, and that choice is the difference between 97 MB and 1.4 GB. Measured on
 * Cristian's phone on 2026-09-13, over a 47-minute episode: the smallest (256x144) weighed 97 MB
 * and downloaded in 229 s; the largest, at the ~3.4 Mbps that network gave against the CDN, would
 * have taken almost as long as watching the whole episode. That's why the default ceiling isn't
 * "the best one".
 *
 * And the choice isn't only made when downloading: when PLAYING these same tracks have to be
 * declared again or the selector picks by bandwidth over a menu that lies —the manifest announces
 * all six whether or not they're on disk— and asks for one nobody downloaded. That happened in the
 * first measurement: `init-f4-v1-x3` against a disk that had f1. See [CaracolDownload], where
 * they end up saved.
 */
object CaracolQuality {

    /**
     * Default height ceiling, in pixels.
     *
     * 720 and not 1080: on a phone the difference is barely noticeable and the weight nearly
     * doubles. It's a ceiling, not a target — if the series only has 480p, 480p is downloaded
     * without complaint.
     */
    const val TARGET_HEIGHT = 720

    /**
     * The tracks to download: ONE video and ONE audio.
     *
     * Video: the tallest one that doesn't exceed [targetHeight]. If ALL of them exceed it (a
     * source that only publishes 1080p), the smallest of all — better to download something heavy
     * than to not offer the download.
     *
     * Audio: the one with the most bits. Next to video, audio is a rounding error (measured: 98
     * kbps against the SMALLEST video's 164 kbps), so saving there buys nothing and is audible.
     *
     * Empty list if there's no video: an episode with no video track isn't something that can be
     * offered.
     */
    fun choose(tracks: List<CaracolTrack>, targetHeight: Int = TARGET_HEIGHT): List<CaracolTrack> {
        val videos = tracks.filter { it.isVideo }
        if (videos.isEmpty()) return emptyList()
        val video = videos.filter { it.height <= targetHeight }.maxByOrNull { it.height }
            ?: videos.minByOrNull { it.height }
            ?: return emptyList()
        val audio = tracks.filter { !it.isVideo }.maxByOrNull { it.bitsPerSecond }
        return listOfNotNull(video, audio)
    }

    /**
     * How much it will weigh, in bytes, from the declared bitrates and the duration.
     *
     * It's an estimate and is used to WARN before starting, not to reserve disk space: the
     * manifest's bitrate is the declared average, and the real file lands close but not exact.
     */
    fun estimatedBytes(chosen: List<CaracolTrack>, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val bits = chosen.sumOf { it.bitsPerSecond.toLong() }
        return bits * durationMs / 1000L / 8L
    }
}
