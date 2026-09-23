package com.arkiv.player.thumbnails

/**
 * What image shows on a card: the captured frame if there is one, otherwise the first non-empty
 * fallback.
 *
 * Lives here and not in each screen because there are FOUR surfaces with the same rule and
 * different fallbacks (hero, "Continue watching", chapter list and series card). With the rule
 * repeated in each one, it only takes one going out of sync for the same series to look different
 * in two places on the same screen.
 *
 * Doesn't take progress on purpose: the frame ONLY exists if the chapter was started, so "only wins
 * on what's been started" is already implicit in whether [frame] is null or not.
 */
object ThumbnailChoice {

    fun choose(frame: String?, vararg fallbacks: String?): String? =
        frame?.takeIf { it.isNotBlank() }
            ?: fallbacks.firstOrNull { !it.isNullOrBlank() }
}
