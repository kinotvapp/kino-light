package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.sync.PeerOfferStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPluginsSectionTest {
    @Test fun `every status says something different`() {
        val texts = PeerOfferStatus.entries.map(::peerOfferStatusText)
        assertTrue(texts.all { it.isNotBlank() })
        assertTrue(texts.toSet().size == texts.size)
    }

    @Test fun `Instalar waits while the plugin installs by itself or another action runs`() {
        assertTrue(peerOfferInstallEnabled(PeerOfferStatus.NEEDS_CONSENT, busy = false))
        assertTrue(peerOfferInstallEnabled(PeerOfferStatus.FAILED, busy = false))
        assertFalse(peerOfferInstallEnabled(PeerOfferStatus.INSTALLING, busy = false))
        assertFalse(peerOfferInstallEnabled(PeerOfferStatus.FAILED, busy = true))
    }
}
