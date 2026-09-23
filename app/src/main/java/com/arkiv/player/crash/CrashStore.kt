package com.arkiv.player.crash

import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Queue of local reports on disk.
 *
 * Exists for one single reason: when an uncaught exception fires, the process is dying, and
 * writing a file is the only thing that can finish before it's gone. There's no upload anywhere
 * anymore (Task 9, sub-project 2B: `CrashUploader`, which sent this to PocketBase, is gone) --
 * the report stays here and is read via `adb logcat`.
 */
class CrashStore(
    private val dir: File,
    /** Cap on saved reports. Past the cap, the oldest ones go: if the app got stuck in a crash
     *  loop, the most recent one is what's useful for debugging. */
    private val maxPending: Int = 20,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Breaks ties between two reports from the same millisecond (a crash that drags another thread down). */
    private val sequence = AtomicInteger(0)

    fun save(json: String): File {
        dir.mkdirs()
        val name = String.format(
            Locale.US,
            "%013d-%04d.json",
            now(),
            sequence.getAndIncrement() % 10_000,
        )
        // Write aside and rename: a cut mid-write this way leaves a `.json.tmp` the queue ignores,
        // instead of a broken `.json` that whoever reads this locally (adb logcat) couldn't parse.
        val temp = File(dir, "$name.tmp")
        temp.writeText(json)
        val destination = File(dir, name)
        if (!temp.renameTo(destination)) {
            destination.writeText(json)
            temp.delete()
        }
        prune()
        return destination
    }

    /** The pending ones from oldest to newest (the name is zero-padded on purpose). */
    fun pending(): List<File> =
        (dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedBy { it.name }

    private fun prune() {
        val current = pending()
        if (current.size <= maxPending) return
        current.take(current.size - maxPending).forEach { it.delete() }
    }
}
