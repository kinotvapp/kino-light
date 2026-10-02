package com.arkiv.player.cast

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.arkiv.player.MainActivity
import com.arkiv.player.data.local.Mp4PrepWorker
import com.arkiv.player.playback.ACTION_OPEN_PLAYER
import com.arkiv.player.playback.EXTRA_EPISODE_ID

/**
 * "Listo para la TV": a download asked for by a TV that gave up ([DownloadForTv]) is prepared. Tapping
 * it opens the title's player from the file and asks which TV to send it to ([EXTRA_SEND_TO_TV]: the
 * app reopens the player, [PlayerReopen], and hands it a [SendToTv] request).
 */
object TvReadyNotice {

    /** On an [ACTION_OPEN_PLAYER] intent: reopen the player from the file and offer the TVs. */
    const val EXTRA_SEND_TO_TV = "sendToTv"

    fun post(context: Context, episodeId: String, title: String) {
        runCatching {
            Mp4PrepWorker.ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                ("tv:$episodeId").hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_OPEN_PLAYER
                    putExtra(EXTRA_EPISODE_ID, episodeId)
                    putExtra(EXTRA_SEND_TO_TV, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = if (title.isBlank()) "Ya está en tu teléfono. Tócalo para enviarlo a la TV."
            else "\"$title\" ya está en tu teléfono. Tócalo para enviarlo a la TV."
            val n = NotificationCompat.Builder(context, Mp4PrepWorker.CHANNEL_ID)
                .setContentTitle("Listo para la TV")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_play, "Enviar a la TV", open)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(("tv:$episodeId").hashCode(), n)
        }
    }
}
