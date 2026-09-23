package com.arkiv.player.data.newcontent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The number for the "there are new episodes" badge. See [NewEpisodeCounter] for why it's counted
 * this way and not by dates.
 */
class NewEpisodeCounterTest {

    @Test fun never_having_looked_means_no_badge() {
        // The whole existing library would fall into this case: if `null` counted as 0 seen, the
        // day this ships every series would show up with a badge for all its episodes.
        assertEquals(0, NewEpisodeCounter.count(current = 26, seen = null))
    }

    @Test fun two_more_episodes_than_last_time() {
        assertEquals(2, NewEpisodeCounter.count(current = 26, seen = 24))
    }

    @Test fun with_nothing_new_there_is_no_badge() {
        assertEquals(0, NewEpisodeCounter.count(current = 26, seen = 26))
    }

    @Test fun if_episodes_were_deleted_there_is_no_negative_badge() {
        // Happens if a source is removed from the group, or if the source re-derived with fewer files.
        assertEquals(0, NewEpisodeCounter.count(current = 24, seen = 26))
    }

    @Test fun an_empty_series_has_no_badge() {
        assertEquals(0, NewEpisodeCounter.count(current = 0, seen = 0))
    }

    @Test fun there_is_a_badge_if_it_should_show() {
        assertEquals(false, NewEpisodeCounter.shouldShow(26, 26))
        assertEquals(false, NewEpisodeCounter.shouldShow(26, null))
        assertEquals(true, NewEpisodeCounter.shouldShow(26, 24))
    }

    @Test fun saving_episodes_yourself_does_not_turn_on_the_badge() {
        // You save the whole season of a series you already had with 3 episodes and its detail
        // already open: the 17 that show up aren't new from the portal, you brought them in.
        assertEquals(20, NewEpisodeCounter.reseal(seen = 3, totalNow = 20))
        assertEquals(0, NewEpisodeCounter.count(20, NewEpisodeCounter.reseal(3, 20)))
    }

    @Test fun a_series_you_never_opened_still_has_no_counter() {
        // `null` is "you never opened the detail": sealing it here would permanently turn off the
        // badge for episodes that WILL be new later on.
        assertNull(NewEpisodeCounter.reseal(seen = null, totalNow = 20))
    }
}
