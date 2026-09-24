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

    /** A saved subtitle sidecar: its file, the language label to show, and whether it's SubRip. */
    data class Saved(val file: File, val lang: String, val srt: Boolean)

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
            arr.put(JSONObject().put("name", s.file.name).put("lang", s.lang).put("srt", s.srt))
        }
        manifest(dir, episodeId).writeText(JSONObject().put("subs", arr).toString())
    }

    /**
     * The saved subtitle sidecars for [episodeId], from the manifest. Empty when there's no
     * manifest (a streaming item, or a download from before this existed) or it can't be read; a
     * sidecar whose file is gone is skipped.
     */
    fun read(dir: File, episodeId: String): List<Saved> = runCatching {
        val file = manifest(dir, episodeId)
        if (!file.exists()) return emptyList()
        val arr = JSONObject(file.readText()).optJSONArray("subs") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sidecar = File(dir, o.optString("name"))
            if (o.optString("name").isBlank() || !sidecar.exists()) return@mapNotNull null
            Saved(sidecar, o.optString("lang"), o.optBoolean("srt"))
        }
    }.getOrDefault(emptyList())
}
