package com.arkiv.player.data.local

import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewayPlayable
import com.arkiv.player.data.plugin.BackgroundPluginCall
import com.arkiv.player.data.plugin.PluginDownloadCall
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginRef
import com.arkiv.player.playback.Container
import com.arkiv.player.playback.VideoContainer
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The [DownloadSource.PLUGIN_DOWNLOAD] strategy: a title of any plugin that declared the `download`
 * capability (apiVersion 2), saved as one file.
 *
 * Resolves the saved ref ([refForEpisode], the library's `torrentData`, a wrapped `plg1:` ref) at
 * download time through [source] (the app's `contentSource`, which dispatches to the plugin's own
 * `PluginContentSource.resolve()`, so the URL and headers are exactly what playback would use and
 * already host-checked by `PluginOutput.stream`), asks [PluginDownloadEligibility] whether that
 * Stream is one file the downloader can save, and downloads it with the Stream's headers through
 * [downloaderFor] -- the plugin's own client, gated to its hosts like the player's (redirects and all),
 * built per plugin id by `AppGraph` -- with the response sniffed for a manifest in disguise
 * ([ManifestSniff]). An HLS stream (by URL, mime or sniffed) is saved as one file by [HlsDownloader]
 * over that same client. Every refusal is a PERMANENT [DownloadOutcome.Failed] ("Este video no se
 * puede descargar"): the row ends `refused`, with no "Reintentar" and no crash report. Subtitles become
 * sidecars ([SubtitleSidecars]); `audioTracks` are NOT saved: the offline copy has only the audio
 * inside the video file (documented in the SDK guide).
 *
 * Re-checks [offersDownloads] when the download runs, not only when the row was queued: a queued row
 * can outlive the plugin being disabled, uninstalled or updated without the capability, and nothing
 * of such a plugin may run. Resolved at download time (not at enqueue) for the same reason Magis is:
 * plugin URLs expire (`expiresInSeconds`), and a row can sit in the queue for a day.
 */
class PluginDownloadStrategy(
    private val refForEpisode: suspend (episodeId: String) -> String?,
    private val source: ContentSource,
    private val downloaderFor: (pluginId: String) -> HttpRangeDownloader,
    private val offersDownloads: (pluginId: String) -> Boolean,
    /** The HLS downloader over a plugin's [HttpRangeDownloader]: its client (the same gate) and disk measure. */
    private val hlsFor: (HttpRangeDownloader) -> HlsDownloader = { HlsDownloader(it.client, it.freeSpace) },
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val pluginId = PluginIds.pluginIdOfEpisode(episodeId)
            ?: return DownloadOutcome.Failed("Este video no es de un plugin")
        // Retryable once the plugin can download again; the person's own doing, so never a report.
        if (!offersDownloads(pluginId)) return DownloadOutcome.Failed(NOT_OFFERED, expected = true)

        val ref = refForEpisode(episodeId) ?: return DownloadOutcome.Failed("No se encontró la fuente del video")
        // A background call: a queue against a slow server must never switch the plugin off
        // ("No responde") for the person's own search and Home. A download's: the Stream is checked
        // against the same hosts as playing it (the broad video permission included).
        val playable = runCatching { withContext(BackgroundPluginCall + PluginDownloadCall) { source.resolve(ref) } }.getOrElse {
            return DownloadOutcome.Failed.ofResolve(it, "No se pudo resolver el video")
        }
        // Permanent: the stream's shape will not change, so the row ends `refused` (no retry, no report).
        // A live channel is read off the ref's kind: it has no end to save, whatever its stream looks like.
        val live = PluginRef.decode(ref)?.kind == PluginRef.LIVE
        PluginDownloadEligibility.refusal(playable, live = live)?.let { return DownloadOutcome.Failed(it, permanent = true) }

        val http = downloaderFor(pluginId)
        if (PluginDownloadEligibility.isHls(playable.url, playable.mime)) return downloadHls(http, episodeId, playable, targetDir, onProgress)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, "plugin.${extensionOf(playable)}"))
        // Explicit resumeKey: a plugin URL may carry a token that changes on every resolution, so
        // using it as the key (the default) would discard the `.part` on every retry and start over.
        // `refuseManifests`: what the eligibility check cannot see (a playlist behind an
        // extensionless URL with no mime) is caught from the response itself: an HLS playlist is
        // then saved as HLS, any other manifest refused.
        return http.download(playable.url, target, playable.headers, resumeKey = episodeId, refuseManifests = true, onProgress = onProgress).fold(
            onSuccess = { file -> done(http, episodeId, playable, targetDir, file) },
            onFailure = {
                when {
                    it is ManifestResponseException && it.hls -> downloadHls(http, episodeId, playable, targetDir, onProgress)
                    it is ManifestResponseException -> DownloadOutcome.Failed(PluginDownloadEligibility.NOT_DOWNLOADABLE, permanent = true)
                    else -> failed(it)
                }
            },
        )
    }

    /**
     * An HLS VOD stream saved as one file by [HlsDownloader], through the same gated client and with
     * the Stream's headers on every playlist, key and segment. What the playlist shows can never be
     * saved (live, SAMPLE-AES, separate audio only…) is a permanent refusal like any other.
     */
    private suspend fun downloadHls(
        http: HttpRangeDownloader,
        episodeId: String,
        playable: GatewayPlayable,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome = hlsFor(http).download(
        playable.url, playable.headers, targetDir, LocalFilePaths.sanitize(episodeId), resumeKey = episodeId, onProgress = onProgress,
    ).fold(
        onSuccess = { file -> done(http, episodeId, playable, targetDir, file) },
        onFailure = {
            if (it is HlsRefusedException) {
                android.util.Log.i(TAG, "HLS download of $episodeId refused: ${it.detail}")
                DownloadOutcome.Failed(PluginDownloadEligibility.NOT_DOWNLOADABLE, permanent = true)
            } else {
                android.util.Log.w(TAG, "HLS download of $episodeId failed: $it")
                // The row shows a Spanish sentence, never the JVM's or the server's own words.
                DownloadOutcome.Failed(HlsFailureText.of(it), transient = DownloadRetryPolicy.isTransient(it))
            }
        },
    )

    private suspend fun done(http: HttpRangeDownloader, episodeId: String, playable: GatewayPlayable, targetDir: File, file: File): DownloadOutcome {
        SubtitleSidecars.save(http, episodeId, playable.subtitles, targetDir, playable.headers)
        return DownloadOutcome.Done(file)
    }

    private fun failed(e: Throwable) =
        DownloadOutcome.Failed(e.message ?: "Falló la descarga", transient = DownloadRetryPolicy.isTransient(e))

    /**
     * The saved file's extension: the declared `mime` names the container when it is one we know,
     * else the URL's path does, else mp4 (the player sniffs the bytes anyway; the name only helps).
     */
    private fun extensionOf(playable: GatewayPlayable): String {
        val mime = playable.mime.substringBefore(';').trim()
        val byMime = Container.entries.firstOrNull { it.mime.equals(mime, ignoreCase = true) }?.let(VideoContainer::extensionFor)
        return byMime ?: VideoContainer.videoExtension(playable.url) ?: "mp4"
    }

    companion object {
        /** The plugin is disabled, gone, or no longer declares `download`: nothing of it runs. */
        const val NOT_OFFERED = "Este plugin ya no puede descargar videos"
        private const val TAG = "KinoPluginDownload"
    }
}
