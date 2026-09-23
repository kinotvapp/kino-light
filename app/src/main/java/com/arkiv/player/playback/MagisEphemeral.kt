package com.arkiv.player.playback

/**
 * Playback that leaves NO trace: it resolves, plays, and doesn't write a single row.
 *
 * Exists because of adult content. Everything else from Magis reaches the player by going through
 * the library first (`ArkivRepository.addMagisSource` saves the item and returns the `episodeId`
 * used to navigate), and from there `loadMagis` reads the `ref` to ask the gateway for the stream.
 * That path is exactly the one that can't exist here: the rule in [AdultContent] is **don't
 * write**, and a library row is exactly what there can't be — it would show up in "Continue
 * watching" and keep local playback progress on this device (no cloud sync left to spread it
 * further).
 *
 * So the `ref` travels outside. It's the same pattern (and for the same reason) as
 * `LiveZappingSource`: the navigation route is a `String`, the `ref` is a long, opaque token that
 * has no business riding inside a URL, and this survives the screen being recreated.
 *
 * It's not a security lock and doesn't claim to be — the lock is the per-device code, which is the
 * only thing that makes the 18+ section appear at all. This is the guarantee that, once inside, no
 * trace is left: with no library row there's no progress to save, no "continue watching" to paint,
 * and nothing to upload.
 */
object MagisEphemeral {

    /** `magis:` so [PlayerSource.kindFor] keeps recognizing it as Magis. */
    const val PREFIX = "magis:efimero:"

    fun idFor(contentId: String): String = "$PREFIX$contentId"

    fun isEphemeral(episodeId: String): Boolean = episodeId.startsWith(PREFIX)

    /**
     * The only thing needed to play without a library entry.
     *
     * [adulto] travels explicitly and isn't inferred from "it came through here". Today they're
     * the same thing -this path exists for adult content and nothing else comes through it-, but
     * the day something else uses it (a preview, a trailer) that inference, written nowhere, would
     * silently stop logging things that did need logging.
     */
    data class Pending(
        val episodeId: String,
        val ref: String,
        val titulo: String,
        val adulto: Boolean,
    )

    @Volatile
    private var pending: Pending? = null

    fun leave(p: Pending) { pending = p }

    /** What was left for [episodeId], or null if what's saved belongs to a different playback. */
    fun take(episodeId: String): Pending? = pending?.takeIf { it.episodeId == episodeId }
}
