package com.arkiv.player.ui.player

/**
 * How long [DituExoPlayer] waits for the first frame before starting anyway.
 *
 * A CHOSEN number, not a measured one. It has to stay above how long the first frame actually
 * takes: shorter, and the audio would start without a picture in exactly the case this wait
 * exists to fix. The KDoc of `exoYaPintoAlgo` in `PlayerScreen` notes 6.5 s measured on ditu, and
 * the comment on `setSessionKeepaliveMs` in [DituExoPlayer] counts 14 s measured on `main` with
 * the previous channel's DRM session retained, which is exactly what that setting releases. If it
 * were too long and some device painted nothing while paused, the person would sit through that
 * stretch with the spinner and no sound before it starts anyway.
 *
 * 10 s sits above the 6.5 s and caps that wait. It doesn't cover the 14 s case: if that happened
 * again, the audio would start at 10 s with the screen still black, which is no worse than
 * before, when it started right away.
 */
internal const val MAX_FIRST_FRAME_WAIT_MS = 10_000L

/**
 * When Caracol starts: with the first frame, not before.
 *
 * [DituExoPlayer] primes paused and asks this when to give it play. It starts when the first
 * frame is painted ([frameArrived]) or, if it never arrives, when the wait expires ([expired]):
 * never both, and only once. If in the meantime the person touched play or pause
 * ([personDecided]), they're in charge and this no longer touches the player: a pause of theirs
 * is never confused with this wait. If the app goes to the background while waiting
 * ([suspendWait]), it doesn't start in the background; on return ([resume]) the wait continues,
 * with its deadline counted again from scratch: it never ends up waiting with no deadline.
 *
 * Kept separate and Android-free so it can be tested on the JVM, same as [DituState]. One per
 * player: a reload arms another player and, with it, another wait. If what failed was paused
 * ([wantedToPlay]), the reload's instance is armed with [autoStart] set to `false`: it stays
 * primed paused and the person decides.
 */
internal class StartOnFirstFrame(
    private val maxWaitMs: Long = MAX_FIRST_FRAME_WAIT_MS,
    private val autoStart: Boolean = true,
) {
    /** When it became primed paused, or `null` if not yet. */
    private var sinceMs: Long? = null

    /** Already started, or the person already decided: nothing left to wait for. */
    private var resolved = false

    /** The app went to the background while waiting. See [suspendWait]. */
    private var suspended = false

    /** Whether it's primed paused, waiting for the first frame (and with the app in view). */
    val waiting: Boolean get() = sinceMs != null && !resolved && !suspended

    /** Whether the wait is suspended because the app went to the background. */
    val isSuspended: Boolean get() = suspended

    /** The player became primed paused at [nowMs]: the wait begins. */
    fun start(nowMs: Long) {
        if (!autoStart) {
            resolved = true
            return
        }
        if (sinceMs == null) sinceMs = nowMs
    }

    /**
     * Did this player want to play when it failed? What the one arming the reload inherits: yes
     * if it was playing ([playWhenReady]) or still starting, even while suspended; no if it was
     * paused, whether because the person paused it or because the app went to the background.
     */
    fun wantedToPlay(playWhenReady: Boolean): Boolean = playWhenReady || waiting || suspended

    /** The first frame was painted. `true` = give it play now. */
    fun frameArrived(): Boolean = release()

    /** A clock reading. `true` = the wait expired with no frame: give it play now, anyway. */
    fun expired(nowMs: Long): Boolean {
        val since = sinceMs ?: return false
        if (nowMs - since < maxWaitMs) return false
        return release()
    }

    /** The person touched play or pause while waiting: from here on, they decide. */
    fun personDecided() {
        resolved = true
    }

    /**
     * The app went to the background while waiting: the wait is suspended. It doesn't start in
     * the background, not by the frame and not by expiring. On return, [resume].
     */
    fun suspendWait() {
        if (waiting) suspended = true
    }

    /**
     * The app came back: the wait continues, with its deadline counted again from [nowMs]. If
     * the frame never arrives, the safety exit starts it anyway.
     */
    fun resume(nowMs: Long) {
        if (!suspended) return
        suspended = false
        sinceMs = nowMs
    }

    private fun release(): Boolean {
        if (!waiting) return false
        resolved = true
        return true
    }
}
