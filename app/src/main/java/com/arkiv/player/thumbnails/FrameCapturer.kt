package com.arkiv.player.thumbnails

import android.graphics.Bitmap
import android.view.TextureView
import com.arkiv.player.data.db.EpisodeFrameDao
import com.arkiv.player.data.db.EpisodeFrameEntity
import com.arkiv.player.data.db.PlaybackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Captures the frame currently being watched and saves it.
 *
 * Best-effort end to end: if something fails -no TextureView, the decoder returns black, the disk
 * is full- nothing happens and the previous frame is kept. This is a visual nicety, never a reason
 * to bother whoever is watching something.
 *
 * Does NOT capture already-watched chapters, and the guard lives HERE and not in the triggers on
 * purpose: there are three of them (the 5-minute poll, pausing, and the exit `onDispose`, all in
 * `PlayerScreen`) and just one forgetting it reopens the hole. See [publish] for why this exact
 * moment is checked.
 */
class FrameCapturer(
    private val store: FrameStore,
    private val dao: EpisodeFrameDao,
    /**
     * To know whether the chapter already ended up watched. No default: it's part of what this
     * class promises ("the frame only exists if the chapter was started and not finished"), not an
     * optional extra that could be forgotten at a new call site.
     */
    private val playbackDao: PlaybackDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Called from the main thread on purpose: `getBitmap()` reads the TextureView's hardware layer
     * and only works there. The expensive part -half a million pixels, compressing to JPEG, writing
     * to disk and to Room- goes to [Dispatchers.IO]: whoever triggers the capture is already on the
     * UI thread (`viewModelScope` uses `Main.immediate`, so without an explicit thread switch the
     * body would run INLINE on the composition thread until its first real suspension), and on the
     * Fire TV that's tens of ms = dropped video frames on every capture.
     */
    suspend fun capture(episodeId: String, positionMs: Long, textureView: TextureView?): Boolean {
        if (!FrameGuards.positionQualifies(positionMs)) return false
        val view = textureView ?: return false
        val bitmap = runCatching { view.getBitmap(WIDTH, HEIGHT) }.getOrNull() ?: return false
        // The try/finally wraps the withContext, it isn't nested inside it: that way recycle()
        // also runs if the coroutine is cancelled before the IO block gets to execute (the
        // ViewModel gets cleared meanwhile, for instance), the only path that would leak the
        // bitmap.
        try {
            // Everything that follows -reading pixels, compressing, writing to disk, writing to
            // the DB- stays inside the same runCatching: a full disk or a Room exception can't take
            // playback down with it, so it just becomes "wasn't saved" and that's it.
            return withContext(Dispatchers.IO) {
                runCatching {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    if (!FrameGuards.isNotNearBlack(pixels)) return@runCatching false
                    val output = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) return@runCatching false
                    publish(episodeId, positionMs, output.toByteArray())
                }.getOrDefault(false)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Writes the file and the row, unless the chapter is ALREADY watched.
     *
     * The check happens here -after compressing, right next to the write- and not at the start of
     * [capture], because the problem is a RACE, not intent: on leaving the player, `onDispose`
     * fires `saveProgress` (which marks watched and destroys the frame past 60%) and the capture
     * immediately after. Since compressing the JPEG costs tens of ms, the capture lands LAST:
     * checking `watched` on entry would answer "not yet" and it would write anyway, leaving the
     * finished chapter with a live frame nobody will ever delete (no more player ticks are left to
     * call the destructor again) -- before Task 5 that frame would also get uploaded to PocketBase
     * and propagated to other devices; without cloud sync the damage stays contained to this
     * device, but it's still an orphan frame that breaks the invariant `ThumbnailChoice` lives on: a
     * frame only exists if the chapter was started.
     *
     * `playback` is read and not `episode_frame` itself: the destroyer's tombstone might not have
     * been written yet, while being watched is the fact that triggers it.
     *
     * `internal` and not private so the test can exercise the guard without a TextureView (which
     * doesn't exist outside a device). The only production caller is [capture].
     */
    internal suspend fun publish(episodeId: String, positionMs: Long, jpeg: ByteArray): Boolean {
        if (playbackDao.get(episodeId)?.watched == true) return false
        store.save(episodeId, jpeg)
        dao.upsert(
            EpisodeFrameEntity(
                episodeId = episodeId,
                positionMs = positionMs,
                capturedAt = now(),
                updatedAt = now(),
            ),
        )
        return true
    }

    private companion object {
        const val WIDTH = 960
        const val HEIGHT = 540
        const val QUALITY = 80
    }
}
