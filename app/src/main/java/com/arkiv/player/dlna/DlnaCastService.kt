package com.arkiv.player.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arkiv.player.AppGraph

/**
 * Keeps the phone's DLNA proxy alive while a TV is playing from it.
 *
 * With DLNA the PHONE serves the video: the TV pulls it from a server running inside this app. When the
 * person leaves Kino, Android cuts the app's network within seconds (measured on a real Samsung: Kino paused
 * at 21:43:52, the TV's connection aborted at 21:43:57, the TV unreachable from the phone at 21:44:04), the
 * TV runs out of buffered video and reports it can't connect. A foreground service is exempt from that, so
 * the cast keeps going with the phone in a pocket.
 *
 * Also holds a WiFi lock (so the radio doesn't drop into power-save and stall the stream) and a partial wake
 * lock (so the CPU keeps serving with the screen off). All released when the cast ends. The notification has
 * a "Detener" action, since the person may not have the app open to stop it.
 */
class DlnaCastService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DlnaLog.i("cast service: Stop pressed in the notification")
            // Off the main thread (the controller does network); the service goes away with the cast.
            AppGraph.from(this).dlna.stopActive()
            stopSelf()
            return START_NOT_STICKY
        }
        val tvName = intent?.getStringExtra(EXTRA_TV_NAME).orEmpty().ifBlank { "la TV" }
        goForeground(tvName)
        acquireLocks()
        DlnaLog.i("cast service: foreground for '$tvName', proxy + WiFi kept alive")
        // Not sticky: if the system ever kills it, the cast is gone with the process and restarting an empty
        // service would only show a notification for nothing.
        return START_NOT_STICKY
    }

    private fun goForeground(tvName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Enviando a la TV", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DlnaCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Reproduciendo en $tvName")
            .setContentText("Kino sigue enviando el video a la TV")
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "kino:dlna").apply { setReferenceCounted(false); acquire() }
        }.onFailure { DlnaLog.w("cast service: WiFi lock failed: ${it.message}") }
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            // A timeout as a safety net: a lock leaked by a crash must not drain the battery forever.
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kino:dlna").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
        }.onFailure { DlnaLog.w("cast service: wake lock failed: ${it.message}") }
    }

    override fun onDestroy() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        DlnaLog.i("cast service: stopped, locks released")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "arkiv_dlna_cast"
        private const val NOTIFICATION_ID = 4242
        private const val ACTION_STOP = "com.arkiv.player.dlna.STOP"
        private const val EXTRA_TV_NAME = "tv_name"
        private const val WAKE_LOCK_MAX_MS = 9L * 60 * 60 * 1000

        /** Starts it. Called with the app in front (the person just chose a TV), which is what lets Android allow it. */
        fun start(context: Context, tvName: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DlnaCastService::class.java).putExtra(EXTRA_TV_NAME, tvName),
                )
            }.onFailure { DlnaLog.w("cast service could not start: ${it.javaClass.simpleName}: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DlnaCastService::class.java)) }
        }
    }
}
