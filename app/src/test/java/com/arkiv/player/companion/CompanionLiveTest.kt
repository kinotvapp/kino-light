package com.arkiv.player.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionLiveTest {
    @Test fun `a xuper channel still needs the linked account`() {
        assertEquals(LiveSendTarget.Play("live:cyx-RCNHD"), liveSendTarget("cyx-RCNHD", hasMagisLink = true))
        assertEquals(LiveSendTarget.Refuse("no_link"), liveSendTarget("cyx-RCNHD", hasMagisLink = false))
    }

    @Test fun `a plugin channel needs no Magis link, only a well-formed code`() {
        assertEquals(LiveSendTarget.Play("live:plugin:own-server:c1"), liveSendTarget("plugin:own-server:c1", hasMagisLink = false))
        assertEquals(LiveSendTarget.Refuse("bad_live_code"), liveSendTarget("plugin:own-server", hasMagisLink = true))
    }
}
