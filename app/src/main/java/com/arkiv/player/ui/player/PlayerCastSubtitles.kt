package com.arkiv.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.arkiv.player.AppGraph
import com.arkiv.player.cast.CastSubtitleSource
import com.arkiv.player.cast.CastSubtitleText
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.playback.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the cast's subtitles ([AppGraph.castSubtitles]) on what this screen shows: the title's
 * external subtitles (offered, with the phone's own way to fetch them) and the one the menu has on.
 * Chromecast and DLNA read both from there, with or without this screen.
 *
 * One call from `PlayerContent` on purpose: that function is at ART's verifier limit, and the
 * effects, the `remember` and the fetcher live here instead.
 */
@Composable
internal fun CastSubtitlesSync(graph: AppGraph, item: PlayerData?, episodeId: String, extras: WebExtras?, tracks: TracksState) {
    val titleId = item?.episodeId ?: episodeId
    val sources = remember(titleId, item?.kind, extras) { castSubtitleSources(titleId, item?.kind, extras) }
    LaunchedEffect(titleId, sources, item) {
        withContext(Dispatchers.IO) {
            graph.castSubtitles.offer(titleId, sources, subtitleFetcher(graph, item, sources))
        }
    }
    val selection = tracks.castTextSelection
    LaunchedEffect(titleId, selection) { graph.castSubtitles.select(titleId, selection) }
}

/**
 * The external subtitles of [titleId] a TV can get: [extras]' when they are that title's, none for a
 * live channel (a live cast never carries subtitles) or for anything else.
 */
internal fun castSubtitleSources(titleId: String, kind: SourceKind?, extras: WebExtras?): List<CastSubtitleSource> {
    if (extras == null || extras.episodeId != titleId) return emptyList()
    if (kind == SourceKind.LIVE || PluginIds.isLiveEpisode(titleId)) return emptyList()
    return extras.subtitles
        .filter { it.url.startsWith("http://") || it.url.startsWith("https://") || it.url.startsWith("file:") }
        .map { CastSubtitleSource(it.lang, it.url, it.format) }
}

/**
 * How the phone fetches [item]'s subtitles: a plugin's through that plugin's host-gated client
 * (the same gate and headers its player uses, side files strict when the hosts are relaxed), the
 * rest with the stream's headers like the phone's player (`StreamExoPlayer`'s default data source).
 */
private fun subtitleFetcher(graph: AppGraph, item: PlayerData?, sources: List<CastSubtitleSource>): (String) -> ByteArray? {
    val headers = item?.requestHeaders.orEmpty()
    val client: okhttp3.OkHttpClient = if (item?.kind == SourceKind.PLUGIN) {
        graph.pluginStreamClient(
            item.pluginHosts,
            item.pluginXuper,
            strictSideUrls(item.pluginHosts, sources.map { it.url }, emptyList()),
        )
    } else {
        plainSubtitleHttp
    }
    return { url ->
        if (url.startsWith("file:")) {
            java.io.File(java.net.URI(url)).takeIf { it.length() in 1..CastSubtitleText.MAX_BYTES.toLong() }?.readBytes()
        } else {
            fetchSubtitle(client, url, headers)
        }
    }
}

private val plainSubtitleHttp: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/** GET [url] with [headers] (the phone player's User-Agent when they name none), at most [CastSubtitleText.MAX_BYTES]. */
private fun fetchSubtitle(client: okhttp3.OkHttpClient, url: String, headers: Map<String, String>): ByteArray? {
    val request = okhttp3.Request.Builder().url(url)
        .apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "okhttp/4.12.0")
        }
        .build()
    return client.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        val body = resp.body ?: return@use null
        if (body.contentLength() > CastSubtitleText.MAX_BYTES) return@use null
        body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > CastSubtitleText.MAX_BYTES) return@use null
            }
            out.toByteArray()
        }
    }
}
