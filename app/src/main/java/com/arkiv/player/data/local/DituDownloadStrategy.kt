package com.arkiv.player.data.local

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifest
import com.arkiv.player.data.ArkivRepository
import com.arkiv.player.data.caracol.CaracolStore
import com.arkiv.player.data.caracol.CaracolQuality
import com.arkiv.player.data.caracol.TrackKey
import com.arkiv.player.data.caracol.CaracolDownload
import com.arkiv.player.data.caracol.CaracolTrack
import com.arkiv.player.data.gateway.ContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Caracol: saves an episode's ENCRYPTED segments in media3's cache.
 *
 * It's the odd one of the two strategies. Magis downloads ONE file and that file then plays on
 * its own, with no dependency. Caracol can't: its video is DASH with Widevine, and what's left on
 * disk is segments only the device's CDM knows how to open, with a license that has to be
 * requested again every time. Nothing gets decrypted here — see [CaracolStore].
 *
 * The result then isn't a playable video but the download's RECORD ([CaracolDownload]): a little
 * JSON file next to the normal downloads, with the manifest's URL and which quality was
 * downloaded. That's what [DownloadOutcome.Done] returns and what ends up in
 * `downloads.filePath`. It goes this way, and not as a new column, because all the machinery that
 * already exists —`LocalDownloadManager.remove`'s prefix sweep, the UI row, the twin— works with a
 * path; and because `LocalLibrary.fileFor` knows not to hand it to the local-file player (it would
 * look at a JSON and show a black screen).
 *
 * Resolved at the moment of downloading and not when queuing: Caracol's `playback_token` lasts hours.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DituDownloadStrategy(
    private val repo: ArkivRepository,
    private val gateway: ContentSource,
    private val store: CaracolStore,
    /** Quality ceiling. Injected so the decision can be tested without touching the global constant. */
    private val targetHeight: Int = CaracolQuality.TARGET_HEIGHT,
) : DownloadStrategy {

    override suspend fun download(
        episodeId: String,
        alreadyConfirmed: Boolean,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val ref = repo.magisRefForEpisode(episodeId)
            ?: return DownloadOutcome.Failed("No se encontró la fuente de Caracol")

        val play = runCatching { gateway.resolve(ref) }.getOrElse {
            return DownloadOutcome.Failed(
                it.message ?: "No se pudo resolver el capítulo de Caracol",
                transient = DownloadRetryPolicy.isTransient(it),
            )
        }
        if (play.url.isBlank()) return DownloadOutcome.Failed("Caracol no devolvió un manifiesto")

        val headers = play.drmLicenseHeaders
        val manifest = runCatching {
            withContext(Dispatchers.IO) {
                DashUtil.loadManifest(store.httpFactory(headers).createDataSource(), Uri.parse(play.url))
            }
        }.getOrElse {
            return DownloadOutcome.Failed(
                it.message ?: "No se pudo leer el manifiesto de Caracol",
                transient = DownloadRetryPolicy.isTransient(it),
            )
        }

        val chosen = CaracolQuality.choose(tracksFrom(manifest), targetHeight)
        if (chosen.isEmpty()) return DownloadOutcome.Failed("Ese capítulo de Caracol no trae video")
        val keys = chosen.map { StreamKey(PERIOD, it.group, it.track) }
        val height = chosen.firstOrNull { it.isVideo }?.height ?: 0
        val estimatedBytes = CaracolQuality.estimatedBytes(chosen, manifest.durationMs)
        Log.i(
            TAG,
            "$episodeId: downloading ${height}p (~${estimatedBytes / 1_000_000}MB of ${manifest.durationMs}ms) keys=$keys",
        )

        // The record is written BEFORE a single byte downloads. If the download cuts off halfway,
        // what's left in the cache is still identifiable: without the record, those megabytes
        // would be anonymous junk nobody would know how to resume or delete.
        val record = File(targetDir, recordFileName(episodeId))
        val data = CaracolDownload(
            mpd = play.url,
            keys = chosen.map { TrackKey(PERIOD, it.group, it.track) },
            height = height,
        )
        runCatching { record.writeText(data.toJson()) }
            .onFailure { return DownloadOutcome.Failed("No se pudo anotar la descarga: ${it.message}") }

        val item = MediaItem.Builder()
            .setUri(play.url)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setStreamKeys(keys)
            .build()

        return try {
            // `runInterruptible` and not a bare `withContext`: `DashDownloader.download` BLOCKS the
            // thread, and a cancelled coroutine doesn't interrupt a blocking call on its own.
            // Without this, "Cancel" left the download running until it finished — spending data
            // for a row that no longer exists, exactly what `LocalDownloadManager.remove`
            // documents wanting to avoid.
            runInterruptible(Dispatchers.IO) {
                store.downloader(item, headers).download { contentLength, bytesDownloaded, _ ->
                    onProgress(bytesDownloaded, if (contentLength > 0) contentLength else estimatedBytes)
                }
            }
            Log.i(TAG, "$episodeId: done, ${store.bytesOnDisk() / 1_000_000}MB of Caracol on disk")
            DownloadOutcome.Done(record)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Propagated: the worker tells it apart from a failure, and what downloaded stays in
            // the cache so "Retry" continues from there instead of starting from scratch.
            throw ce
        } catch (t: Throwable) {
            DownloadOutcome.Failed(
                t.message ?: "Falló la descarga de Caracol",
                transient = DownloadRetryPolicy.isTransient(t),
            )
        }
    }

    /**
     * Deletes from the cache what THIS episode downloaded, without touching the others.
     *
     * The cache is a single one for everyone, so deleting a folder isn't enough: what knows which
     * bytes belong to whom is the downloader itself, rebuilt with the same manifest and the same
     * tracks that were recorded in the record.
     */
    override suspend fun clearLeftovers(episodeId: String, targetDir: File) {
        val record = File(targetDir, recordFileName(episodeId))
        val data = runCatching { CaracolDownload.fromJson(record.readText()) }.getOrNull()
        if (data == null) {
            Log.w(TAG, "$episodeId: no download record, nothing to clear from the cache")
            return
        }
        val item = MediaItem.Builder()
            .setUri(data.mpd)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setStreamKeys(data.keys.map { StreamKey(it.period, it.group, it.track) })
            .build()
        runCatching { runInterruptible(Dispatchers.IO) { store.downloader(item, emptyMap()).remove() } }
            .onFailure { Log.w(TAG, "$episodeId: couldn't clear the cache: ${it.message}") }
        Log.i(TAG, "$episodeId: cleared; ${store.bytesOnDisk() / 1_000_000}MB of Caracol left on disk")
    }

    /** The manifest, reduced to what [CaracolQuality] needs to choose. */
    private fun tracksFrom(manifest: DashManifest): List<CaracolTrack> {
        if (manifest.periodCount == 0) return emptyList()
        val period = manifest.getPeriod(PERIOD)
        return buildList {
            period.adaptationSets.forEachIndexed { group, set ->
                val isVideo = set.type == C.TRACK_TYPE_VIDEO
                if (!isVideo && set.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                set.representations.forEachIndexed { track, representation ->
                    val f = representation.format
                    add(
                        CaracolTrack(
                            group = group,
                            track = track,
                            isVideo = isVideo,
                            height = f.height.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: 0,
                            bitsPerSecond = f.bitrate.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: 0,
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "ArkivDituDl"

        /**
         * Caracol serves a single period. Named instead of writing a bare 0 in five places: a
         * multi-period manifest would need to download them all, and this way it's clear where to look.
         */
        private const val PERIOD = 0

        /** Name of an episode's record. The prefix is what `LocalDownloadManager.remove` sweeps by. */
        fun recordFileName(episodeId: String): String =
            "${LocalFilePaths.sanitize(episodeId)}.${CaracolDownload.EXTENSION}"
    }
}
