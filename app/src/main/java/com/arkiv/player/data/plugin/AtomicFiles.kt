package com.arkiv.player.data.plugin

import java.io.File

/**
 * Writes [bytes] to [target] via a `.tmp` sibling + rename, so a crash mid-write never leaves a
 * half-written file in place. `File.renameTo` isn't guaranteed atomic across filesystems (or even
 * always succeeds on the same one), so a failed rename falls back to a direct overwrite. Shared by
 * every plugin file writer (manifests, storage, cached scripts) that needs this guarantee.
 */
fun writeFileAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
        target.writeBytes(bytes)
        tmp.delete()
    }
}
