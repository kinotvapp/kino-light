package com.arkiv.player.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the 18+ section shows, with what code, and on which device.
 *
 * The code is chosen by the person in Ajustes and starts at a known default (0000). Unlocks only
 * the device it was typed on: it doesn't travel in sync, so the living room TV doesn't inherit
 * what got unlocked on the phone, and reinstalling the app turns it off.
 */
class AdultsLockTest {

    private val code = "482913"

    @Test fun `with the correct code it unlocks`() {
        assertTrue(AdultsLock.unlocks(entered = "482913", actualCode = code))
    }

    @Test fun `with any other code it doesn't`() {
        assertFalse(AdultsLock.unlocks("482914", code))
        assertFalse(AdultsLock.unlocks("1234", code))
    }

    /** Extra whitespace from an on-screen keyboard can't be the difference between getting in or not. */
    @Test fun `extra whitespace doesn't count`() {
        assertTrue(AdultsLock.unlocks("  482913 ", code))
    }

    /**
     * THE DANGEROUS EDGE. With an empty actual code, an empty field would give a "match" and
     * unlock the section for anyone who hits OK without typing anything. Today [AdultsLock.effectiveCode]
     * keeps the actual code from arriving empty, but the guard stays: it's the last line if prefs
     * ever ended up blank for any reason.
     */
    @Test fun `with no code configured NOTHING unlocks, not even empty against empty`() {
        assertFalse(AdultsLock.unlocks("", ""))
        assertFalse(AdultsLock.unlocks("   ", ""))
        assertFalse(AdultsLock.unlocks("482913", ""))
    }

    @Test fun `an empty attempt against a real code doesn't unlock either`() {
        assertFalse(AdultsLock.unlocks("", code))
    }

    // --- The default and its notice ----------------------------------------------------------

    /** Nothing was ever saved: the default rules, and that's why it has to be announced. */
    @Test fun `with nothing saved the code is the default`() {
        assertEquals("0000", AdultsLock.effectiveCode(null))
        assertTrue(AdultsLock.isDefault(null))
    }

    /** A prefs file with the key blank counts the same as not having it: never empty. */
    @Test fun `a blank saved value falls back to the default instead of leaving the code empty`() {
        assertEquals("0000", AdultsLock.effectiveCode(""))
        assertEquals("0000", AdultsLock.effectiveCode("   "))
    }

    @Test fun `with a code of one's own the default no longer rules`() {
        assertEquals(code, AdultsLock.effectiveCode(code))
        assertFalse(AdultsLock.isDefault(code))
    }

    /**
     * Choosing 0000 by hand is just as default as not having chosen anything: the notice has to
     * stay there, because what it announces is that anyone knows that code.
     */
    @Test fun `setting 0000 by hand is still the default`() {
        assertTrue(AdultsLock.isDefault("0000"))
    }

    // --- The hidden reset ----------------------------------------------------------------------

    /**
     * The way out for whoever forgot the code they set. Not announced anywhere: the sign that it
     * worked is that the "default 0000" notice shows up again on its own.
     */
    @Test fun `9999 requests a reset`() {
        assertTrue(AdultsLock.requestsReset("9999"))
        assertTrue(AdultsLock.requestsReset(" 9999 "))
    }

    @Test fun `anything else doesn't reset`() {
        assertFalse(AdultsLock.requestsReset("0000"))
        assertFalse(AdultsLock.requestsReset("999"))
        assertFalse(AdultsLock.requestsReset("99999"))
        assertFalse(AdultsLock.requestsReset(""))
    }

    /**
     * 9999 can't be chosen as one's own code. If it could, the reset would be blocked by it:
     * whoever typed it to get in would end up erasing their own code without opening anything.
     */
    @Test fun `9999 is reserved and can't be set as a code`() {
        assertTrue(AdultsLock.isReserved("9999"))
        assertTrue(AdultsLock.isReserved(" 9999 "))
        assertFalse(AdultsLock.isReserved("0000"))
        assertFalse(AdultsLock.isReserved("1234"))
    }

    // --- New code format -----------------------------------------------------------------------

    @Test fun `the new code is four digits`() {
        assertTrue(AdultsLock.isValidFormat("1234"))
        assertTrue(AdultsLock.isValidFormat(" 1234 "))
        assertTrue(AdultsLock.isValidFormat("0000"))
    }

    @Test fun `not shorter, not longer, no letters, not empty`() {
        assertFalse(AdultsLock.isValidFormat("123"))
        assertFalse(AdultsLock.isValidFormat("12345"))
        assertFalse(AdultsLock.isValidFormat("12a4"))
        assertFalse(AdultsLock.isValidFormat(""))
        assertFalse(AdultsLock.isValidFormat("    "))
    }

    // --- What shows ------------------------------------------------------------------------------

    /** Without unlocking there's no button, no lock icon, no gray row: a disabled button
     *  announces something exists, and announcing it is half the problem. */
    @Test fun `without unlocking the section doesn't show`() {
        assertFalse(AdultsLock.shouldShowSection(unlocked = false))
    }

    @Test fun `unlocked it shows`() {
        assertTrue(AdultsLock.shouldShowSection(unlocked = true))
    }

    /** The field to type the code is always there while it hasn't been entered: now there's
     *  ALWAYS a code, even if it's the default, so there's no longer a case of asking for one that doesn't unlock. */
    @Test fun `locked shows the field and unlocked doesn't`() {
        assertTrue(AdultsLock.shouldShowField(unlocked = false))
        assertFalse(AdultsLock.shouldShowField(unlocked = true))
    }
}
