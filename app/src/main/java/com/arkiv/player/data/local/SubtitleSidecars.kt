package com.arkiv.player.data.local

import android.util.Log
import com.arkiv.player.data.gateway.GatewaySubtitle
import java.io.File

/**
 * Saves a resolved stream's external subtitles next to the downloaded video, one sidecar each, and
 * records the manifest offline playback reads (see [OfflineSubtitleFiles]). Shared by every strategy
 * that downloads a file whose subtitles the source serves separately (Magis/Xuper, plugins).
 *
 * Best-effort on purpose: a subtitle that won't download must NOT fail the movie that already did.
 * Already-present sidecars are kept (a re-run doesn't re-fetch them).
 */
object SubtitleSidecars {
    suspend fun save(http: HttpRangeDownloader, episodeId: String, subtitles: List<GatewaySubtitle>, targetDir: File) {
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

    private const val TAG = "ArkivSubtitleDl"
}
