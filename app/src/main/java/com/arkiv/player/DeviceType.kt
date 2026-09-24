package com.arkiv.player

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.BatteryManager
import com.arkiv.player.data.SettingsStore

/** Is this device a TV? Used by MainActivity (to pick the UI root), AppGraph/ArkivApp (wiring). */
object DeviceType {

    /**
     * The user's manual "force TV layout" switch, persisted in [SettingsStore.PREFS_NAME]. The
     * guaranteed escape for a box that fools every auto-detection signal below: turned on, the app
     * is a TV no matter what. Read straight from prefs here so EVERY caller honors it without going
     * through the graph. There's no "force phone": auto-detection never misreads a real handheld as
     * a TV, and this switch is reachable from the tablet layout to undo itself.
     */
    const val KEY_FORCE_TV = "force_tv_design"

    /**
     * A screen SO large in dp that no real tablet reaches it -- only a low-density / 4K set-top box.
     * Deliberately far above the biggest tablets (~1000-1100dp smallest width): screen size is NOT
     * a reliable TV signal on its own (a 1080p TV at 320dpi is only ~540dp, LESS than a big tablet),
     * so this is a last-resort catch that must never flip a handheld. The reliable signals are the
     * ones above it (no touchscreen, no battery). See [isTelevision].
     */
    private const val TV_MIN_SMALLEST_WIDTH_DP = 1200

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the user forced the TV layout in settings. */
    fun forcedTv(context: Context): Boolean = prefs(context).getBoolean(KEY_FORCE_TV, false)

    fun isTelevision(context: Context): Boolean = forcedTv(context) || detectTelevision(context)

    private fun detectTelevision(context: Context): Boolean {
        val pm = context.packageManager
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
        ) {
            return true
        }
        // No real touchscreen = driven by a remote = a TV, not a tablet. Two independent ways to
        // tell, because some cheap boxes run TABLET firmware that LIES about the FEATURE while the
        // runtime Configuration still reports no touch:
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        if (context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH) return true
        // A set-top box has NO battery; a phone/tablet always has one. Only an EXPLICIT "not present"
        // counts (unknown stays handheld), so this can never misclassify a real handheld.
        if (batteryAbsent(context)) return true
        // Last resort (see the constant): a screen larger in dp than any tablet.
        if (context.resources.configuration.smallestScreenWidthDp >= TV_MIN_SMALLEST_WIDTH_DP) return true
        return false
    }

    /** True only when the device reports it has no battery at all (a set-top box). */
    private fun batteryAbsent(context: Context): Boolean {
        val sticky = runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        // EXTRA_PRESENT defaults to true when the intent omits it, so absence needs an explicit false.
        return !sticky.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    }

    /**
     * The raw classification signals, for telemetry — so a box misread as a tablet in the field can
     * be diagnosed from its actual numbers instead of guessing (see [ArkivApp]'s device-profile log).
     */
    fun debugSignals(context: Context): String {
        val pm = context.packageManager
        val cfg = context.resources.configuration
        val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
        val sticky = runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val batteryPresent = sticky?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        return "forcedTv=${forcedTv(context)} uiModeTv=${uiMode == Configuration.UI_MODE_TYPE_TELEVISION} " +
            "leanback=${pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)} " +
            "fireTv=${pm.hasSystemFeature("amazon.hardware.fire_tv")} " +
            "touchFeature=${pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)} " +
            "touchCfg=${cfg.touchscreen} battery=$batteryPresent " +
            "swDp=${cfg.smallestScreenWidthDp} wDp=${cfg.screenWidthDp} dpi=${cfg.densityDpi} " +
            "-> tv=${isTelevision(context)}"
    }
}
