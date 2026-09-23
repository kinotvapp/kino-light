package com.arkiv.player.thumbnails

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where each chapter's JPEG lives.
 *
 * One file per chapter, with the name derived from the episodeId by hash: the identifiers carry
 * `:` and `/` (`web:series:tt01/1x03`), which don't work as a filename. Deriving it instead of
 * storing it in a column keeps the row and the file from ever going out of sync.
 */
class FrameStore(private val dir: File) {

    fun fileFor(episodeId: String): File = File(dir, "${hash(episodeId)}.jpg")

    /**
     * Overwrites: a chapter has ONE live image, never two.
     *
     * Writes to a temp file and renames it over the destination, which within the same filesystem
     * is atomic: either the previous frame stays, or the whole new one does, never a half-written
     * JPEG. Writing straight to the destination does leave a truncated file if the process dies
     * midway, and that's worse than no frame: the broken file exists, so [pathIfExists] returns
     * it, `ThumbnailChoice` picks it, Coil can't decode it and the card ends up EMPTY instead of
     * falling back to the TMDB still -- until that chapter gets played again.
     *
     * The temp filename carries a unique suffix because two captures of the same chapter can
     * overlap (pause and immediately leave): with a fixed name they'd step on each other's temp
     * file.
     */
    fun save(episodeId: String, jpeg: ByteArray): File {
        dir.mkdirs()
        val destination = fileFor(episodeId)
        // Old temp files for THIS chapter are swept before writing: if the process died mid a
        // previous write, that temp file would be claimed by nobody else (per-chapter deletion
        // only knows the destination's `.jpg`) and would stay on disk forever.
        tempFilesFor(destination)?.forEach { it.delete() }
        val temp = File(dir, "${destination.name}.${System.nanoTime()}$TEMP_SUFFIX")
        try {
            temp.writeBytes(jpeg)
            if (!temp.renameTo(destination)) throw IOException("could not publish the frame for $episodeId")
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return destination
    }

    fun pathIfExists(episodeId: String): String? = fileFor(episodeId).takeIf { it.exists() }?.absolutePath

    fun delete(episodeId: String) {
        val destination = fileFor(episodeId)
        destination.delete()
        tempFilesFor(destination)?.forEach { it.delete() }
    }

    private fun tempFilesFor(destination: File): Array<File>? = dir.listFiles { file ->
        file.name.startsWith("${destination.name}.") && file.name.endsWith(TEMP_SUFFIX)
    }

    /**
     * Empties the whole directory. It's the logout wipe's job, not a single chapter's: the new
     * identity can't be left with the previous person's watched-scene JPEGs.
     *
     * Sweeps EVERYTHING in the directory, not just `.jpg` files: that way it also takes any temp
     * file left over from an interrupted write.
     */
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Extension of [save]'s temp files: never the destination's, which is `.jpg`. */
        const val TEMP_SUFFIX = ".tmp"
    }
}
