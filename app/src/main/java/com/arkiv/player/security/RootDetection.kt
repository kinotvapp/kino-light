package com.arkiv.player.security

/**
 * What could be observed about the device. Kept separate from the decision on purpose: collecting
 * this touches the filesystem and the PackageManager, and that way [RootDetection.reasons] stays
 * pure and testable without booting Android.
 */
data class RootSignals(
    /** Paths of `su` binaries that exist. */
    val suBinaries: List<String> = emptyList(),
    /** Installed root-manager packages. */
    val rootPackages: List<String> = emptyList(),
    /** Magisk-specific paths that exist. */
    val magiskTraces: List<String> = emptyList(),
    /** `Build.TAGS`. */
    val tags: String = "",
    /** `Build.TYPE`. */
    val type: String = "",
    /** Suspicious lines found in the process's own mount table. */
    val suspiciousMounts: List<String> = emptyList(),
    /**
     * Whether two threads of the SAME process see different mount tables. A normal process can't:
     * the namespace belongs to the process. See [RootDetection].
     */
    val inconsistentMounts: Boolean = false,
)

/**
 * Decides whether the device is rooted.
 *
 * **What this is NOT**: it doesn't prevent installing the app. Android offers no mechanism for a
 * sideloaded APK to refuse to install based on device state -- the installer doesn't run our code.
 * The only option is to refuse to RUN, which is what [MainActivity] does.
 *
 * **And it's not foolproof**: Magisk with Shamiko/PIF exists specifically to hide itself. Against
 * that setup, the "surface" signals (`su` binaries, packages, tags) fail: Shamiko hooks process
 * startup BEFORE the app looks, unmounts whatever gives it away and hides its packages from the
 * PackageManager. That's why there are the two mount signals, the only thing current practice
 * reports as resistant:
 *
 * - **Traces in the mount table.** Even if the obvious stuff is cleaned up, systemless mode leaves
 *   entries pointing at `/data/adb` or overlaying `/system`.
 * - **Inconsistency between threads.** The mount namespace belongs to the PROCESS: two threads of
 *   the same process CANNOT see different tables. When the namespace is manipulated at boot,
 *   threads created before that moment keep the old view, and the difference gives away the
 *   manipulation no matter how well everything else is hidden.
 *
 * **Play Integrity is not an option here**, even though it's what Google recommends: it requires
 * Google Play Services, and the Fire TV Stick runs Fire OS, which doesn't have them. And the APK
 * is distributed outside Play, so the app's own verdict wouldn't apply either way.
 *
 * The signals are chosen to avoid a false positive on a factory device. Measured on the Fire TV
 * Stick (AFTKM) on 2026-08-12: `tags=amz-p,release-keys`, `type=user`, no `su` binaries. Watch out
 * for `tags`: it carries more than just "release-keys", so look for "test-keys" inside it, don't
 * compare the whole string.
 */
object RootDetection {

    /** Common locations for the `su` binary. */
    val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
    )

    /** Root managers and apps that only make sense with root. */
    val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "com.noshufou.android.su",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "me.weishu.kernelsu",
        "com.yellowes.su",
    )

    /** Traces Magisk leaves behind even without its app installed. */
    val MAGISK_TRACES = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/cache/.disable_magisk",
    )

    /** Build types that aren't from a factory device. */
    private val OPEN_BUILD_TYPES = setOf("userdebug", "eng")

    /**
     * What gives away a mount. `/data/adb` is where Magisk lives, and an `overlay`/`tmpfs` mounted
     * over `/system` or `/vendor` is exactly the systemless technique: the real system isn't
     * touched, something else is laid over it.
     */
    fun isMountSuspicious(line: String): Boolean {
        val l = line.lowercase()
        if ("magisk" in l || "/data/adb" in l || "kernelsu" in l) return true
        val overlaid = " overlay " in l || " tmpfs " in l
        return overlaid && (" /system" in l || " /vendor" in l)
    }

    /**
     * Why this device is considered rooted. Empty = clean.
     *
     * Reasons are returned instead of a boolean so the lock screen can say what was found: a
     * warning that explains nothing is indistinguishable from a bug. These strings are shown
     * on-screen, so they stay in Spanish.
     */
    fun reasons(signals: RootSignals): List<String> = buildList {
        signals.suBinaries.forEach { add("binario su en $it") }
        signals.rootPackages.forEach { add("app de root instalada: $it") }
        signals.magiskTraces.forEach { add("rastro de Magisk en $it") }
        if (signals.tags.contains("test-keys", ignoreCase = true)) {
            add("compilación firmada con test-keys")
        }
        if (signals.type.lowercase() in OPEN_BUILD_TYPES) {
            add("compilación de desarrollo (${signals.type})")
        }
        signals.suspiciousMounts.forEach { add("montaje sospechoso: $it") }
        if (signals.inconsistentMounts) {
            add("dos hilos del proceso ven tablas de montaje distintas")
        }
    }

    fun hasRoot(signals: RootSignals): Boolean = reasons(signals).isNotEmpty()
}
