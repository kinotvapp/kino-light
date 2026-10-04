package app.kino.demo

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Is this device a TV (driven by a remote)? Picks the TV or the phone interface. */
object DeviceKind {
    fun isTelevision(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("amazon.hardware.fire_tv")) return true
        // No touchscreen: a remote-driven box running tablet firmware.
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true
        return context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
    }
}
