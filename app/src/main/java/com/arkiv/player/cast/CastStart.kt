package com.arkiv.player.cast

/**
 * Where a cast starts on the TV, the SAME rule for every protocol (Chromecast and DLNA): where the
 * person is, never 0:00 when they were elsewhere. Pure.
 *
 * How the TV is then put there is the protocol's own: the Chromecast loads the media at that
 * position, a DLNA renderer gets an AVTransport `Seek` once it plays (see `DlnaSeek`).
 */
object CastStart {

    /**
     * The resume point, freshest source first:
     * 1. [receiverMs], the TV's own position for this title when it is already on it (another audio
     *    picked while casting): the phone's player has sat paused where the cast began;
     * 2. [livePositionMs], the phone player's position -- only when above 0: a player that does not
     *    exist yet or holds nothing (for Magis the service player, which is not the one playing)
     *    answers 0, and that silently cast from the beginning every time the race went that way;
     * 3. [itemStartMs], where the item was told to open, right whenever the live position is not
     *    available yet.
     */
    fun resumePointMs(receiverMs: Long?, livePositionMs: Long?, itemStartMs: Long): Long =
        (receiverMs ?: livePositionMs?.takeIf { it > 0 } ?: itemStartMs).coerceAtLeast(0L)
}
