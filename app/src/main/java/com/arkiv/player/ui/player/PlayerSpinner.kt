package com.arkiv.player.ui.player

/**
 * When to cover the screen with the loading spinner.
 *
 * Lives outside the Composable on purpose -- same criterion as `DrawerDpad` -- because the
 * condition is evaluated in TWO places: the overlay that draws it and the log that diagnoses it.
 * Written twice it goes out of sync on the first change, and then the log stops describing what's
 * actually on screen, which is exactly what it exists for.
 *
 * The four reasons are different and all end in a black screen:
 *  - [noPlaylist]: what to play hasn't been resolved yet.
 *  - [buffering]: the player is loading data.
 *  - [noFirstFrame]: "starts black with sound" -- the player already lets the audio
 *    through but hasn't rendered the first frame yet, and there `playbackState` is NOT
 *    BUFFERING, so without this flag the screen was left with no spinner and no picture.
 *  - [lostVideoOutput]: there WAS a picture and it was lost coming back from the background
 *    (up to 15s).
 *
 * [casting] cancels the last one: the TV puts up the picture, not us, so waiting on the local
 * video output doesn't matter and never will arrive. The other three still apply while casting --
 * while the receiver loads, the local screen still has to explain what's going on.
 */
internal fun shouldShowSpinner(
    noPlaylist: Boolean,
    buffering: Boolean,
    noFirstFrame: Boolean,
    lostVideoOutput: Boolean,
    casting: Boolean,
): Boolean = noPlaylist || buffering || noFirstFrame || (lostVideoOutput && !casting)
