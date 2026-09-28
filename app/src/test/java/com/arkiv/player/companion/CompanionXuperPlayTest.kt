package com.arkiv.player.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Xuper title sent to the paired TV (`KIND_MAGIS`) plays there only through the TV's own official
 * Xuper plugin: the native Magis source is gone, `LegacyXuperRefSource` forwards every Magis ref to
 * that plugin. So the TV refuses up front when it can't, and the phone says why in Spanish instead
 * of a generic failure.
 */
class CompanionXuperPlayTest {

    @Test fun `a TV with the official Xuper plugin usable plays it`() {
        assertNull(xuperPlayRefusal(xuperInstalled = true, xuperUsable = true))
    }

    @Test fun `a TV without the Xuper plugin refuses with no_xuper`() {
        assertEquals(REASON_NO_XUPER, xuperPlayRefusal(xuperInstalled = false, xuperUsable = false))
    }

    @Test fun `a TV with Xuper installed but disabled or damaged refuses with xuper_unusable`() {
        assertEquals(REASON_XUPER_UNUSABLE, xuperPlayRefusal(xuperInstalled = true, xuperUsable = false))
    }

    @Test fun `the phone explains each refusal in Spanish`() {
        assertEquals("Instala el plugin Xuper en el TV para verlo allí", companionPlayFailureText(REASON_NO_XUPER))
        assertEquals("Activa el plugin Xuper en el TV para verlo allí", companionPlayFailureText(REASON_XUPER_UNUSABLE))
        assertEquals("El TV necesita vincular su cuenta", companionPlayFailureText("no_link"))
        assertEquals("No se pudo reproducir en el TV", companionPlayFailureText("resolve_failed"))
    }
}
