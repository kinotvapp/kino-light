package com.arkiv.player.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The subtitle sidecars saved next to a downloaded video so it has subtitles OFFLINE.
 *
 * A Magis download is a single `.ts`/`.mp4` with the audio muxed in but NO subtitles: those are
 * external VTT/SRT files the portal serves apart, and online playback side-loads them from the
 * network. Downloaded, they're fetched into sidecars here so the offline player can side-load them
 * from disk instead.
 *
 * Layout, all in the same downloads dir as the media (so the `sanitize(episodeId)` prefix sweep in
 * [LocalDownloadManager.remove] deletes them together with it):
 * - `<sanitize(episodeId)>.sub.<i>.<vtt|srt>` — one file per subtitle, indexed (NOT named by
 *   language: a language name like "Español" isn't filesystem-safe and two could collide once
 *   sanitized).
 * - `<sanitize(episodeId)>.subs.json` — the manifest pairing each sidecar with its real language
 *   label, so the track picker can show "Español" and not a mangled filename.
 */
object OfflineSubtitleFiles {

    /**
     * A saved subtitle sidecar: its file, the language label to show, and whether it's SubRip.
     * [origin]: the online subtitle's file in the app's cache this sidecar is a copy of (see
     * [Mp4SubtitleSidecars]), null for the download's own subtitles.
     */
    data class Saved(val file: File, val lang: String, val srt: Boolean, val origin: String? = null)

    /** Where subtitle [index] of [episodeId] is written, picking the extension from [format]. */
    fun fileFor(dir: File, episodeId: String, index: Int, format: String): File {
        val ext = if (format.contains("srt", ignoreCase = true)) "srt" else "vtt"
        return File(dir, "${LocalFilePaths.sanitize(episodeId)}.sub.$index.$ext")
    }

    private fun manifest(dir: File, episodeId: String): File =
        File(dir, "${LocalFilePaths.sanitize(episodeId)}.subs.json")

    /** Records which sidecars were saved for [episodeId] and their languages. No-op on empty. */
    fun writeManifest(dir: File, episodeId: String, saved: List<Saved>) {
        if (saved.isEmpty()) return
        val arr = JSONArray()
        saved.forEach { s ->
            val o = JSONObject().put("name", s.file.name).put("lang", s.lang).put("srt", s.srt)
            s.origin?.let { o.put("origin", it) }
            arr.put(o)
        }
        manifest(dir, episodeId).writeText(JSONObject().put("subs", arr).toString())
    }

    /**
     * The saved subtitle sidecars for [episodeId], from the manifest, as offline playback offers
     * them. Empty when there's no manifest (a streaming item, or a download from before this
     * existed) or it can't be read; a sidecar whose file is gone is skipped. So is the copy of an
     * online subtitle while its cached original is still there: the player already brings that one
     * back as an online subtitle, and listing both would show the same subtitle twice.
     */
    fun read(dir: File, episodeId: String): List<Saved> =
        readAll(dir, episodeId).filter { s -> s.origin == null || !File(s.origin).exists() }

    /** Every sidecar in [episodeId]'s manifest whose file is there, online copies included. */
    fun readAll(dir: File, episodeId: String): List<Saved> = runCatching {
        val file = manifest(dir, episodeId)
        if (!file.exists()) return emptyList()
        val arr = JSONObject(file.readText()).optJSONArray("subs") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sidecar = File(dir, o.optString("name"))
            if (o.optString("name").isBlank() || !sidecar.exists()) return@mapNotNull null
            Saved(sidecar, o.optString("lang"), o.optBoolean("srt"), o.optString("origin").ifBlank { null })
        }
    }.getOrDefault(emptyList())
}
