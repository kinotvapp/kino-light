package com.arkiv.player.data.local

import com.arkiv.player.playback.VideoContainer
import java.io.File

/**
 * Names and paths of the files saved on the device. Pure on purpose (doesn't touch `Context`) so
 * it can be tested without Robolectric: the one who knows the root directory is
 * `LocalDownloadManager`, which gets it from `getExternalFilesDir(DIRECTORY_MOVIES)`.
 */
object LocalFilePaths {

    private const val DEFAULT_EXT = "mp4"

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * The target name is the sanitized `episodeId` + the origin's extension. The episodeId is used
     * and not the release's title because it's the key the file is later looked up by at play
     * time, and that way the mapping is direct without depending on the table.
     */
    fun fileNameFor(episodeId: String, sourceName: String?): String {
        // The extension comes from the SINGLE container list, normalized as a URL, not from a cut
        // at the last dot: the NUC download used to get a page URL as its origin name, and on
        // `https://sitio.com/peli` that cut would return `"com/peli"`. Everything else after a dot
        // is part of the title.
        val ext = sourceName?.let { VideoContainer.videoExtension(it) } ?: DEFAULT_EXT
        return "${sanitize(episodeId)}.$ext"
    }

    /** Partial file: written here and renamed at the end, so a half-done target never exists. */
    fun partOf(file: File): File = File(file.parentFile, file.name + ".part")

    /**
     * The partial's ORIGIN mark: saves which URL (or which item) the bytes already in the `.part`
     * came from, so as not to resume against a different source.
     *
     * Without this, a retry could ask `Range: bytes=<40%>-` against an origin URL different from
     * the one that left that same `.part` half-downloaded (for example if the signed link expired
     * and gets resolved again): the server answers 206, the tail of one file gets appended to
     * another's prefix, and the size check doesn't catch it because the numbers add up. The result
     * used to get marked "Listo" and was garbage. (The original case that motivated this was
     * archive.org's original/derivative `downloadQuality` toggle, removed in this branch's
     * pruning; the underlying risk — resuming a `.part` against a source different from the one
     * that wrote it — still exists with Magis, so the mark stays.)
     *
     * A sibling file and not a table column on purpose: the downloader is pure HTTP + disk
     * (doesn't know Room), and this way the `.part`/mark pair travels together and gets swept by
     * the same prefix cleanup in `LocalDownloadManager.remove`.
     */
    fun originOf(file: File): File = File(file.parentFile, partOf(file).name + ".src")
}
