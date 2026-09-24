package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.gateway.ContentSource
import com.arkiv.player.data.gateway.GatewaySubtitle
import java.io.File

/**
 * Magis: resolves the saved `ref` against the gateway and downloads the file from the CDN.
 *
 * The CDN requires `Content-Auth` and `Content-License`, which come in `MagisResolve.resolveVod`'s
 * response. Those tokens are needed to **download**, not to play: once on disk the file plays like
 * any other, without depending on the token still being alive.
 *
 * Resolved at the moment of downloading (not when queuing) because the CDN token lives ~48h: an
 * item that sat in the queue for a day would arrive with an expired token.
 */
class MagisDownloadStrategy(
    private val repo: ArkivRepository,
    private val gateway: ContentSource,
    private val http: HttpRangeDownloader,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val ref = repo.magisRefForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró la fuente de Xuper")

        val playable = runCatching { gateway.resolve(ref) }.getOrElse {
            return DownloadOutcome.Failed(
                it.message ?: "No se pudo resolver la fuente de Xuper",
                transient = DownloadRetryPolicy.isTransient(it),
            )
        }
        if (playable.url.isBlank()) {
            return DownloadOutcome.Failed("Xuper no devolvió un archivo descargable")
        }

        // The extension comes from the CDN URL (`..._media.ts` / `..._media.mp4`): the container
        // matters so the local player picks the right demuxer.
        val extension = playable.url.substringAfterLast('.', "mp4").take(4)
        val target = File(targetDir, LocalFilePaths.fileNameFor(episodeId, "magis.$extension"))

        // Explicit resumeKey: the URL carries a token that changes on every resolution, so using
        // it as the key (the default) would discard the `.part` on every retry and start over.
        return http.download(
            playable.url,
            target,
            playable.headers,
            resumeKey = episodeId,
            onProgress = onProgress,
        ).fold(
            onSuccess = { file ->
                // The media file carries the audio but NO subtitles (Magis serves those as external
                // VTT/SRT). Fetch them into sidecars next to it so offline playback has them. It's
                // best-effort: a subtitle that won't download must NOT fail the movie that already did.
                saveSubtitles(episodeId, playable.subtitles, targetDir)
                DownloadOutcome.Done(file)
            },
            onFailure = {
                DownloadOutcome.Failed(
                    it.message ?: "Falló la descarga",
                    transient = DownloadRetryPolicy.isTransient(it),
                )
            },
        )
    }

    /**
     * Downloads each external subtitle into a sidecar and records the manifest (see
     * [OfflineSubtitleFiles]). Already-present sidecars are kept (a re-run doesn't re-fetch them).
     * Everything here is wrapped so a subtitle failure only loses that subtitle, never the download.
     */
    private suspend fun saveSubtitles(
        episodeId: String,
        subtitles: List<GatewaySubtitle>,
        targetDir: File,
    ) {
        if (subtitles.isEmpty()) return
        val saved = mutableListOf<OfflineSubtitleFiles.Saved>()
        subtitles.forEachIndexed { index, sub ->
            if (sub.url.isBlank()) return@forEachIndexed
            val target = OfflineSubtitleFiles.fileFor(targetDir, episodeId, index, sub.format)
            val ok = target.exists() && target.length() > 0 || runCatching {
                // No headers (same as the online SubtitleConfiguration, which loads the URL bare)
                // and a stable resumeKey so a retry doesn't discard the tiny partial.
                http.download(sub.url, target, resumeKey = "$episodeId.sub.$index", onProgress = { _, _ -> })
                    .getOrThrow()
                true
            }.getOrElse {
                Log.w(TAG, "subtitle #$index (${sub.lang}) failed: ${it.message}")
                false
            }
            if (ok) saved += OfflineSubtitleFiles.Saved(target, sub.lang, sub.format.contains("srt", true))
        }
        runCatching { OfflineSubtitleFiles.writeManifest(targetDir, episodeId, saved) }
    }

    private companion object {
        const val TAG = "ArkivMagisDl"
    }
}

