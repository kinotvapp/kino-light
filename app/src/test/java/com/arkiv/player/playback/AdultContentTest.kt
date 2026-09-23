package com.arkiv.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What content is NOT logged to history.
 *
 * On 2026-08-14, two +18 channels showed up in the "Live channels" row on the home screen, with
 * name and logo. Deleting them from the device wasn't enough: `live_recents` synced through
 * PocketBase (removed in this branch's pruning), so they had already traveled there and could
 * reach the phone and the other TV. Both ends had to be cleaned up.
 *
 * Hence the rule: **don't write it**, instead of filtering it out on read. What isn't written
 * can't slip through some screen we forgot to filter —"continue watching" is painted on the TV's
 * home, on the phone's, and in the library— and, back when sync existed, couldn't be uploaded
 * either.
 *
 * Lives out here and not inside `saveProgress` so its edges can be pinned down: failing the other
 * way —no longer saving normal content's progress— is just as bad and much quieter.
 */
class AdultContentTest {

    @Test fun `adult content does not get logged`() {
        assertFalse(AdultContent.shouldLog(isAdult = true))
    }

    @Test fun `normal content gets logged as always`() {
        assertTrue(AdultContent.shouldLog(isAdult = false))
    }

    /**
     * THE DANGEROUS EDGE, and it goes this way on purpose: what ISN'T known gets logged. A `null`
     * means "I don't have the data", and treating it as adult would stop saving normal films'
     * progress without anyone noticing — silent damage that's hard to trace. The opposite risk is
     * already covered elsewhere: adult content is only reachable through a section that doesn't
     * exist without the device's code.
     */
    @Test fun `with no data, it gets logged`() {
        assertTrue(AdultContent.shouldLog(isAdult = null))
    }
}
