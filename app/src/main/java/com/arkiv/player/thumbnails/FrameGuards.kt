package com.arkiv.player.thumbnails

/**
 * When a frame is NOT good enough to save.
 *
 * These exist because a capture OVERWRITES: a bad frame doesn't get added alongside a good one, it
 * replaces it. Without these two guards, pausing on a fade leaves the card black and, worse, loses
 * the good frame you already had.
 *
 * They work on an ARGB IntArray of pixels (what `Bitmap.getPixels` returns) and not on a Bitmap so
 * they can be tested without Robolectric.
 */
object FrameGuards {

    /** The first 60 s are distributor logos and black screens. Adjustable starting point. */
    const val MIN_POSITION_MS = 60_000L

    /** Below this (out of 255) the frame is considered black and isn't saved. */
    const val MIN_LUMINANCE = 10

    fun positionQualifies(positionMs: Long): Boolean = positionMs >= MIN_POSITION_MS

    /** Average luminance 0..255, with the usual integer weights (77/150/29 over 256). */
    fun averageLuminance(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0
        var sum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += ((r * 77 + g * 150 + b * 29) shr 8).toLong()
        }
        return (sum / pixels.size).toInt()
    }

    fun isNotNearBlack(pixels: IntArray): Boolean =
        pixels.isNotEmpty() && averageLuminance(pixels) >= MIN_LUMINANCE
}
