package com.arkiv.player.playback

/**
 * What content is NOT logged to history: progress, "continue watching", library, recents, and
 * frame thumbnails — those last ones are an image of what was being watched, saved to disk, which
 * makes them the worst one on the list, not the least important.
 *
 * On 2026-08-14, two +18 channels showed up in the "Live channels" row on the home screen.
 * Deleting them from the device wasn't enough: that table synced through this branch's
 * now-removed cloud sync, so they had already traveled to the cloud and could reach the phone and
 * the other TV. Both ends had to be cleaned up.
 *
 * Hence the rule: **don't write it**, instead of filtering it out on read. What isn't written
 * can't slip through some screen we forgot to filter, and — back when sync existed — couldn't be
 * uploaded either.
 */
object AdultContent {

    /**
     * What ISN'T known gets logged — and that direction is deliberate.
     *
     * A `null` means "I don't have the data", not "it's adult". Treating it as adult would stop
     * saving normal content's progress without anyone noticing, which is silent damage that's hard
     * to trace. The opposite risk is already covered elsewhere: adult content is only reachable
     * through a section that doesn't exist without this device's code.
     */
    fun shouldLog(isAdult: Boolean?): Boolean = isAdult != true
}
