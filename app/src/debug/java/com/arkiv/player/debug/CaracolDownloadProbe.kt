package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arkiv.player.AppGraph
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.ui.search.SearchPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only: exercises the REAL Caracol download, end to end, without touching the screen.
 *
 * Unlike an earlier spike -- which built its own downloader to answer "is this possible at all"
 * -- this one goes through the shipping path: it saves the series the way the chapter dialog
 * does, enqueues with the source `DownloadSource` picks, and then `LocalDownloadWorker` runs
 * `DituDownloadStrategy`. Nothing here is a stand-in for production code; it only replaces the
 * finger that would tap the button.
 *
 * ```
 * adb shell am broadcast -n com.arkiv.player.light/com.arkiv.player.debug.CaracolDownloadProbe
 * adb logcat -s ArkivCaracolDownloadProbe ArkivDituDl ArkivLocalDl
 * ```
 * `--es series <contentId>` and `--ei chapter <n>` pick something other than the defaults.
 *
 * Pass `--es step status` instead to ask what the app thinks it has on disk for that chapter --
 * that is, what the player will read at play time.
 */
class CaracolDownloadProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val seriesId = intent.getStringExtra("series")?.takeIf { it.isNotBlank() } ?: SERIES
        val chapterNumber = intent.getIntExtra("chapter", 1)
        val step = intent.getStringExtra("step")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                when (step) {
                    "status" -> status(app, seriesId, chapterNumber)
                    "delete" -> delete(app, seriesId, chapterNumber)
                    else -> enqueue(app, seriesId, chapterNumber)
                }
            }.onFailure { Log.e(TAG, "the probe blew up", it) }
        }
    }

    private suspend fun enqueue(context: Context, seriesId: String, chapterNumber: Int) {
        val graph = AppGraph.from(context)
        val (season, chapters, series) = load(graph, seriesId) ?: return
        val chosen = chapters.getOrNull(chapterNumber - 1) ?: run {
            Log.e(TAG, "chapter $chapterNumber doesn't exist (${chapters.size} in the season)")
            return
        }
        Log.w(TAG, "queuing «${chosen.title}» (#${chosen.number}) of ${chapters.size}")

        val playback = SearchPlayback(graph)
        val enqueued = playback.enqueueCaracolDownload(season, chapters, listOf(chosen), series)
        val epId = playback.dituEpisodeIdFor(season, chapters, chosen, series)
        Log.w(TAG, "queued=$enqueued episodeId=$epId · source=${epId?.let { sourceOf(it) }}")
        Log.w(TAG, "now watch ArkivLocalDl / ArkivDituDl; then run this with `--es step status`")
    }

    private suspend fun status(context: Context, seriesId: String, chapterNumber: Int) {
        val graph = AppGraph.from(context)
        val (season, chapters, series) = load(graph, seriesId) ?: return
        val chosen = chapters.getOrNull(chapterNumber - 1) ?: return
        val epId = SearchPlayback(graph).dituEpisodeIdFor(season, chapters, chosen, series) ?: return

        val row = graph.database.downloadDao().get(epId)
        val download = graph.localLibrary.caracolDownload(epId)
        val asFile = graph.localLibrary.fileFor(epId)
        Log.w(
            TAG,
            "episodeId=$epId\n" +
                "  row      : state=${row?.state} source=${row?.source} bytes=${row?.bytesDone}/${row?.bytes}\n" +
                "  record   : ${download?.let { "${it.height}p, ${it.keys.size} tracks, mpd=${it.mpd.take(60)}" } ?: "none"}\n" +
                "  asFile   : ${asFile ?: "null (correct: Caracol must NOT go to the local-file player)"}\n" +
                "  onDisk   : ${graph.caracolStore.bytesOnDisk() / 1_000_000}MB of Caracol",
        )
    }

    /**
     * Removes the download, which for Caracol is the trickiest part of the lot: its bytes live in
     * a cache SHARED by every chapter, so deleting too much takes down what someone else saved,
     * and deleting too little leaves hundreds of megs that nothing will ever reclaim.
     */
    private suspend fun delete(context: Context, seriesId: String, chapterNumber: Int) {
        val graph = AppGraph.from(context)
        val (season, chapters, series) = load(graph, seriesId) ?: return
        val chosen = chapters.getOrNull(chapterNumber - 1) ?: return
        val epId = SearchPlayback(graph).dituEpisodeIdFor(season, chapters, chosen, series) ?: return

        val before = graph.caracolStore.bytesOnDisk()
        graph.localDownloads.remove(epId)
        val after = graph.caracolStore.bytesOnDisk()
        val row = graph.database.downloadDao().get(epId)
        val record = java.io.File(
            graph.localDownloads.targetDir(),
            com.arkiv.player.data.local.DituDownloadStrategy.recordFileName(epId),
        )
        Log.w(
            TAG,
            "REMOVED $epId · cache ${before / 1_000_000}MB -> ${after / 1_000_000}MB " +
                "(freed ${(before - after) / 1_000_000}MB) · row=${row?.state ?: "gone"} " +
                "· record=${if (record.exists()) "STILL THERE" else "gone"}",
        )
    }

    /** The season the way the chapter window builds it, plus its list and its series. */
    private suspend fun load(
        graph: AppGraph,
        seriesId: String,
    ): Triple<GatewayResult, List<com.arkiv.player.data.gateway.GatewayEpisode>, com.arkiv.player.data.gateway.GatewaySeries?>? {
        val ref = "ditu1:BUNDLE:$seriesId"
        val (chapters, series) = runCatching { graph.contentSource.episodesWithSeries(ref) }
            .getOrElse { Log.e(TAG, "couldn't list the season", it); return null }
        if (chapters.isEmpty()) { Log.e(TAG, "that season has no chapters"); return null }
        val season = GatewayResult(
            source = "ditu",
            title = series?.title?.takeIf { it.isNotBlank() } ?: "Caracol $seriesId",
            ref = ref,
            kind = "series",
        )
        return Triple(season, chapters, series)
    }

    private fun sourceOf(epId: String) = com.arkiv.player.data.local.DownloadSource.sourceFor(epId)

    private companion object {
        const val TAG = "ArkivCaracolDownloadProbe"

        /** "Dulce Amor": the BUNDLE everything else was measured against. */
        const val SERIES = "1500000246"
    }
}
