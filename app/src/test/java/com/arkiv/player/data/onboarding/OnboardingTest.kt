package com.arkiv.player.data.onboarding

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    private class FakePrefs : OnboardingPrefs {
        // Backing fields, not `override var`: a var's JVM setter would clash with setOnboardingKind().
        private var kind: OnboardingKind? = null
        private var pickerDone = false
        override val onboardingKind: OnboardingKind? get() = kind
        override val sourcePickerDone: Boolean get() = pickerDone
        var kindWrites = 0
        override fun setOnboardingKind(kind: OnboardingKind) { kindWrites++; this.kind = kind }
        override fun setSourcePickerDone(done: Boolean) { pickerDone = done }
    }

    private fun plugin(enabled: Boolean) = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled),
        iconFile = null,
    )

    @Test fun `a device already activated on this build's first start is an updating one`() {
        val prefs = FakePrefs()
        assertEquals(OnboardingKind.LEGACY, Onboarding.classifyOnce(prefs, activated = true))
        assertEquals(OnboardingKind.LEGACY, prefs.onboardingKind)
    }

    @Test fun `a device not activated yet is new`() {
        assertEquals(OnboardingKind.NEW, Onboarding.classifyOnce(FakePrefs(), activated = false))
    }

    @Test fun `the kind is recorded once and never judged again`() {
        val prefs = FakePrefs()
        Onboarding.classifyOnce(prefs, activated = false)
        assertEquals(OnboardingKind.NEW, Onboarding.classifyOnce(prefs, activated = true))
        assertEquals(1, prefs.kindWrites)
    }

    @Test fun `only an updating device runs the Xuper migration`() {
        assertTrue(Onboarding.runsXuperMigration(OnboardingKind.LEGACY))
        assertFalse(Onboarding.runsXuperMigration(OnboardingKind.NEW))
        assertFalse(Onboarding.runsXuperMigration(null))
    }

    @Test fun `the picker opens on start only for a new device that has not finished it and has nothing usable`() {
        assertTrue(Onboarding.opensPickerOnStart(OnboardingKind.NEW, pickerDone = false, plugins = emptyList()))
        assertTrue(Onboarding.opensPickerOnStart(OnboardingKind.NEW, pickerDone = false, plugins = listOf(plugin(enabled = false))))
        assertFalse(Onboarding.opensPickerOnStart(OnboardingKind.NEW, pickerDone = false, plugins = listOf(plugin(enabled = true))))
        assertFalse(Onboarding.opensPickerOnStart(OnboardingKind.NEW, pickerDone = true, plugins = emptyList()))
        assertFalse(Onboarding.opensPickerOnStart(OnboardingKind.LEGACY, pickerDone = false, plugins = emptyList()))
        assertFalse(Onboarding.opensPickerOnStart(null, pickerDone = false, plugins = emptyList()))
    }

    @Test fun `the kind survives as its wire value, an unknown value reads as unrecorded`() {
        OnboardingKind.entries.forEach { assertEquals(it, OnboardingKind.fromWire(it.wire)) }
        assertNull(OnboardingKind.fromWire("maybe"))
        assertNull(OnboardingKind.fromWire(null))
    }

    // --- Event orders across cold starts. Each `start` is what warm-up does at the top of a cold
    // start: classify from whether credentials exist right then, then decide the migration from the
    // RECORDED kind. The prefs object outlives the process, like SharedPreferences.

    /** One cold start's warm-up: returns whether it would run the Xuper migration. */
    private fun start(prefs: OnboardingPrefs, activated: Boolean): Boolean {
        runCatching { Onboarding.classifyOnce(prefs, activated) }
        return activated && Onboarding.runsXuperMigration(prefs.onboardingKind)
    }

    @Test fun `activated on an old build, first start of this build -- updating, migration on every start until done`() {
        val prefs = FakePrefs() // the old build never wrote the key
        assertTrue(start(prefs, activated = true))
        assertTrue(start(prefs, activated = true)) // e.g. the first attempt had no network: it retries
        assertEquals(OnboardingKind.LEGACY, prefs.onboardingKind)
        assertFalse(Onboarding.opensPickerOnStart(prefs.onboardingKind, prefs.sourcePickerDone, emptyList()))
    }

    @Test fun `installed fresh on this build and activated later -- new, never migrated`() {
        val prefs = FakePrefs()
        assertFalse(start(prefs, activated = false)) // warm-up runs before the activation screen
        // activation happens in this process; no second warm-up in it
        assertFalse(start(prefs, activated = true)) // next cold start
        assertEquals(OnboardingKind.NEW, prefs.onboardingKind)
        assertTrue(Onboarding.opensPickerOnStart(prefs.onboardingKind, prefs.sourcePickerDone, emptyList()))
    }

    @Test fun `process death after the kind was written but before activation -- still new`() {
        val prefs = FakePrefs()
        start(prefs, activated = false)
        // process dies on the activation screen; the next start is still not activated
        assertFalse(start(prefs, activated = false))
        assertFalse(start(prefs, activated = true))
        assertEquals(OnboardingKind.NEW, prefs.onboardingKind)
        assertEquals(1, prefs.kindWrites)
    }

    @Test fun `process death before the kind was written on a fresh install -- the next start classifies it`() {
        val prefs = FakePrefs() // nothing reached disk
        assertFalse(start(prefs, activated = false))
        assertEquals(OnboardingKind.NEW, prefs.onboardingKind)
    }

    @Test fun `an unrecorded kind neither migrates nor opens the picker, and the next start retries the record`() {
        var broken = true
        val prefs = object : OnboardingPrefs {
            var stored: OnboardingKind? = null
            override val onboardingKind: OnboardingKind? get() = if (broken) null else stored
            override fun setOnboardingKind(kind: OnboardingKind) { if (broken) error("prefs unavailable"); stored = kind }
            override val sourcePickerDone = false
            override fun setSourcePickerDone(done: Boolean) = Unit
        }
        assertFalse(start(prefs, activated = true))
        assertFalse(Onboarding.opensPickerOnStart(prefs.onboardingKind, prefs.sourcePickerDone, emptyList()))

        broken = false
        assertTrue(start(prefs, activated = true)) // never blocked forever
        assertEquals(OnboardingKind.LEGACY, prefs.onboardingKind)
    }
}
