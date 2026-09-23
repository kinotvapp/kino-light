package com.arkiv.player.data.caracol

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.offline.DashDownloader
import java.io.File

/**
 * Where Caracol episodes downloaded to the device live, and how they're read.
 *
 * A Caracol episode can't be saved as a file: its video is DASH, i.e. thousands of segments, and
 * on top of that it comes ENCRYPTED (CENC/Widevine). What gets saved is those segments exactly as
 * the CDN serves them, inside media3's cache. Nothing gets decrypted here or anywhere else in the
 * app: the key never leaves the device's CDM, and that's why playback still needs to ask
 * Caracol's server for a streaming license —a few KB— at the moment of hitting play. It doesn't
 * grant persistent licenses: measured, it answers 500 with `X-DRM-Error` (see
 * `CaracolOfflineProbe`, in `src/debug`).
 *
 * A SINGLE instance per process: `SimpleCache` refuses to open the same folder twice, so this
 * lives in `AppGraph` and both the download and the player ask for the same object.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CaracolStore(private val context: Context, private val folder: File) {

    /**
     * No automatic eviction ([NoOpCacheEvictor]): this is NOT a cache that fills and cleans
     * itself, it's what the person asked to download. A size-based evictor would delete episodes
     * someone saved on purpose, and the `downloads` row would keep saying "Done". What deletes is
     * `LocalDownloadManager.remove`, on explicit request.
     */
    val cache: Cache by lazy {
        SimpleCache(folder.apply { mkdirs() }, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    /** What all Caracol downloads together take up on disk. */
    fun bytesOnDisk(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /**
     * The data source for DOWNLOADING: cache with the network behind it, writing whatever it brings.
     *
     * [headers] carries the `playback_token` cookie. The video CDN doesn't need it —it serves
     * segments to whoever asks— but the manifest does, and sending it unnecessarily costs nothing.
     */
    fun factoryForDownload(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory(headers))

    /**
     * The data source for PLAYING: disk first, network only for what's missing.
     *
     * Doesn't write ([setCacheWriteDataSinkFactory] to null) on purpose. If it wrote, watching an
     * episode by streaming would leave bytes in the same cache with no `downloads` row claiming
     * them: disk that grows and that nothing knows how to delete. What goes in here comes in
     * through a requested download.
     */
    fun factoryForPlayback(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory(headers))
            .setCacheWriteDataSinkFactory(null)

    /** The downloader for one episode. The same object knows how to download and how to DELETE what it downloaded. */
    fun downloader(item: MediaItem, headers: Map<String, String>): DashDownloader =
        DashDownloader(item, factoryForDownload(headers))

    /**
     * The same three headers `DituClient` uses. Without them Caracol's CDN answers 403 to the
     * manifest, and that failure reads as "the video doesn't exist".
     */
    fun httpFactory(headers: Map<String, String>): HttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes") + headers)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
}
