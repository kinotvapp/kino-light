package app.kino.demo

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.kino.demo.data.loadCatalog
import app.kino.demo.ui.LocalReducedEffects
import app.kino.demo.ui.Navigator
import app.kino.demo.ui.PhoneApp
import app.kino.demo.ui.Route
import app.kino.demo.ui.TvApp
import app.kino.demo.ui.brand.KinoSplash
import app.kino.demo.ui.systemAnimationsOff
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTheme
import app.kino.demo.ui.tv.FocusRescue
import com.arkiv.player.data.update.OtaRuntime
import com.arkiv.player.data.update.UpdateCheckResult
import com.arkiv.player.data.update.UpdateInfo
import com.arkiv.player.data.update.UpdateWorker
import com.arkiv.player.ui.update.UpdateDialog
import java.util.concurrent.TimeUnit

/**
 * What the OTA check found. The app paints at once; [UpdateAvailable] puts the opt-in dialog on top
 * of it when the check resolves.
 */
private sealed interface OtaPhase {
    data object Ready : OtaPhase
    data class UpdateAvailable(val info: UpdateInfo) : OtaPhase
}

class MainActivity : ComponentActivity() {
    private var isTv = false
    private val ota: OtaRuntime by lazy { OtaRuntime(applicationContext) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isTv && keyCode in DPAD_KEYS && FocusRescue.rescue()) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceKind.isTelevision(this)
        val rows = runCatching { loadCatalog(this) }.getOrDefault(emptyList())
        val nav = Navigator(listOf(Route.Home))
        val reduced = systemAnimationsOff(this)

        // OTA self-update: at most every 3h, retries the check with exponential backoff (10, 20, 40... min)
        // when every source failed. UPDATE (not KEEP) so an existing install moves to the new schedule
        // on the next launch.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "kino_ota_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build(),
        )

        setContent {
            KinoTheme {
                CompositionLocalProvider(LocalReducedEffects provides reduced) {
                    var splashVisible by rememberSaveable { mutableStateOf(true) }
                    val navigator = remember { nav }
                    var otaPhase by remember { mutableStateOf<OtaPhase>(OtaPhase.Ready) }

                    LaunchedEffect(Unit) {
                        ota.recordOtaStart()
                        val result = ota.checkForUpdate("startup")
                        otaPhase = when (result) {
                            is UpdateCheckResult.Available -> OtaPhase.UpdateAvailable(result.info)
                            else -> OtaPhase.Ready
                        }
                    }

                    Box(Modifier.fillMaxSize().background(KinoBlack)) {
                        if (isTv) TvApp(rows, navigator) else PhoneApp(rows, navigator)
                        if (splashVisible) {
                            KinoSplash(isTv = isTv, canExit = true, onFinished = { splashVisible = false })
                        }
                        // Opt-in: declining ("Ahora no") just clears otaPhase; it comes back next launch.
                        (otaPhase as? OtaPhase.UpdateAvailable)?.let { phase ->
                            UpdateDialog(
                                info = phase.info,
                                ota = ota,
                                onDismiss = {
                                    ota.dismissPendingUpdate()
                                    otaPhase = OtaPhase.Ready
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val DPAD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
)