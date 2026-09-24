package com.arkiv.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Is this device a TV? Used by MainActivity (to pick the UI root) and AppGraph (wiring). */
object DeviceType {
    fun isTelevision(context: Context): Boolean {
        val pm = context.packageManager
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
        ) {
            return true
        }
        // Fallback for cheap "Android box" set-tops: plain Android on an HDMI box that reports
        // NEITHER the TV ui-mode NOR leanback/fire_tv, so it used to fall through to the tablet UI.
        // No touchscreen = it's driven by a remote, i.e. a TV, not a tablet. A phone/tablet always
        // reports FEATURE_TOUCHSCREEN, so this never misclassifies a handheld. (Resolution is NOT a
        // reliable signal here -- a 1080p TV has FEWER pixels than a large tablet.)
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
