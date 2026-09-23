package com.arkiv.player.data.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arkiv.player.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The download notification's "Cancel" button.
 *
 * A receiver and not an Activity because cancelling has nothing to show: opening the app to stop
 * a download is exactly what needed to be avoided.
 *
 * `goAsync()` because [LocalDownloadManager.cancel] touches the database and the worker: without
 * it the process can die as soon as `onReceive` returns and the cancellation is left half-done —
 * the row marked but the worker never cut off.
 */
class DownloadActionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppGraph.from(app).localDownloads.cancel(episodeId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.arkiv.player.CANCELAR_DESCARGA"
        const val EXTRA_EPISODE_ID = "episodeId"
    }
}
