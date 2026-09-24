package com.arkiv.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.ViewTreeObserver
import com.arkiv.player.crash.Crash
import com.arkiv.player.crash.StartupProfile
import com.arkiv.player.data.CpuBench
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.DeviceEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Measures how fast this device really is at startup and reports it ONCE per app version, next to its
 * model and RAM. TELEMETRY ONLY: nothing changes behavior because of it. It exists to collect the data
 * needed to pick real "slow device" thresholds (the startup warm-up time turned out to be no proxy for
 * speed, and the cheapest TV boxes report nonsense RAM), instead of guessing them.
 *
 * Two numbers:
 *  - time to the first drawn frame since the process started (what the person feels on opening), and
 *  - a fixed CPU workload ([CpuBench]), run a few seconds AFTER that frame so the app's own startup work
 *    isn't competing with it, on a background thread so it can't cost the UI a frame.
 */
internal object StartupProfiler {

    /** Let the startup settle before benchmarking, or the benchmark measures our own startup work. */
    private const val SETTLE_AFTER_FIRST_FRAME_MS = 6_000L

    /** Past this the process was already running (a warm relaunch): the time to first frame isn't a cold-start figure. */
    private const val COLD_START_MAX_MS = 60_000L

    @Volatile
    private var started = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(activity: Activity, settings: SettingsStore, isTv: Boolean) {
        if (started || settings.startupProfileReportedVersion == BuildConfig.VERSION_CODE) return
        started = true
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var done = false

            override fun onDraw() {
                if (done) return
                done = true
                val sinceProcessStartMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                // A listener can't be removed while the tree is dispatching draws: post it.
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                scope.launch { report(activity.applicationContext, settings, isTv, sinceProcessStartMs) }
            }
        })
    }

    private suspend fun report(context: Context, settings: SettingsStore, isTv: Boolean, sinceProcessStartMs: Long) {
        delay(SETTLE_AFTER_FIRST_FRAME_MS)
        val benchMs = CpuBench.measureMs()
        val cold = sinceProcessStartMs in 0..COLD_START_MAX_MS
        // Stable message, the numbers as extras: one GlitchTip issue collects every device.
        Crash.report(
            StartupProfile("startup profile"),
            "startup-profile",
            extras = mapOf(
                "model" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "ram_mb" to DeviceEffects.totalRamMb(context).toString(),
                "cores" to Runtime.getRuntime().availableProcessors().toString(),
                "budget_ms" to "%.1f".format(DeviceEffects.frameBudgetMs(context)),
                "ttff_ms" to (if (cold) sinceProcessStartMs.toString() else "warm"),
                "bench_ms" to "%.0f".format(benchMs),
                "tv" to isTv.toString(),
                "version" to BuildConfig.VERSION_CODE.toString(),
            ),
        )
        settings.markStartupProfileReported(BuildConfig.VERSION_CODE)
    }
}
