package com.arkiv.player.data.local

import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.gateway.ContentSource
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
            onSuccess = { DownloadOutcome.Done(it) },
            onFailure = {
                DownloadOutcome.Failed(
                    it.message ?: "Falló la descarga",
                    transient = DownloadRetryPolicy.isTransient(it),
                )
            },
        )
    }
}

