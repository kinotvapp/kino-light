package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAttachPolicyTest {

    private class Spy {
        var attaches = 0
        var detaches = 0
        val policy = VideoAttachPolicy(attach = { attaches++ }, detach = { detaches++ })
    }

    /** Entering the screen: the layout already attached itself when built, so the initial ON_START
     * (which the Lifecycle dispatches on registering the observer) must NOT tear down and redo the vout. */
    @Test fun onStart_initial_does_not_reattach() {
        val s = Spy()
        s.policy.onStart()
        assertEquals(0, s.attaches)
        assertEquals(0, s.detaches)
    }

    /** The bug: leaving to another app and coming back left the screen black because nobody reattached. */
    @Test fun leaving_to_another_app_and_back_reattaches() {
        val s = Spy()
        s.policy.onStop()
        assertEquals(1, s.detaches)
        assertEquals(0, s.attaches)
        s.policy.onStart()
        assertEquals(1, s.attaches)
    }

    /** Several back-and-forths in a row: one attach per return, without piling up. */
    @Test fun several_back_and_forths() {
        val s = Spy()
        repeat(3) {
            s.policy.onStop()
            s.policy.onStart()
        }
        assertEquals(3, s.detaches)
        assertEquals(3, s.attaches)
    }

    /** Repeated events must not duplicate: attachViews() overwrites the previous VideoHelper without
     * releasing it (a leak), so every attach has to come from a real detach. */
    @Test fun repeated_events_do_not_duplicate() {
        val s = Spy()
        s.policy.onStop()
        s.policy.onStop()
        assertEquals(1, s.detaches)
        s.policy.onStart()
        s.policy.onStart()
        assertEquals(1, s.attaches)
    }
}
