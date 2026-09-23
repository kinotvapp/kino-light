package com.arkiv.player.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Looks at the device and builds the [RootSignals]. Everything that touches Android lives here so
 * [RootDetection] stays pure and testable.
 *
 * None of this can crash the app: every read is guarded. A device that denies access to `/proc`
 * is not a reason to leave someone without a player.
 */
object RootSignalCollector {

    fun collect(context: Context): RootSignals = RootSignals(
        suBinaries = RootDetection.SU_PATHS.filter { exists(it) },
        rootPackages = RootDetection.ROOT_PACKAGES.filter { isInstalled(context, it) },
        magiskTraces = RootDetection.MAGISK_TRACES.filter { exists(it) },
        tags = Build.TAGS.orEmpty(),
        type = Build.TYPE.orEmpty(),
        suspiciousMounts = suspiciousMounts(),
        inconsistentMounts = inconsistentMounts(),
    )

    private fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** Only the first few are kept: enough to decide and to explain, without dumping the whole table. */
    private fun suspiciousMounts(): List<String> = runCatching {
        File("/proc/self/mountinfo").readLines()
            .filter { RootDetection.isMountSuspicious(it) }
            .take(3)
    }.getOrDefault(emptyList())

    /**
     * Do two threads of the same process see different mount tables?
     *
     * They shouldn't: the mount namespace belongs to the process. When it's manipulated at boot
     * to hide root, threads that already existed keep the old view and the difference shows up.
     * It's the signal that survives Shamiko, because it doesn't look for a specific trace but for
     * a contradiction in the system itself.
     *
     * Compared against the thread with the lowest tid (the main one, the oldest). With a single
     * thread there's nothing to compare and it answers `false`: absence of proof, not proof of
     * absence.
     */
    private fun inconsistentMounts(): Boolean = runCatching {
        val own = File("/proc/self/mountinfo").readText()
        val threads = File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.sorted().orEmpty()
        val oldest = threads.firstOrNull() ?: return@runCatching false
        val fromThread = File("/proc/self/task/$oldest/mountinfo").readText()
        own.lines().size != fromThread.lines().size
    }.getOrDefault(false)
}
