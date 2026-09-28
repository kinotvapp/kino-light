package com.arkiv.player.data.onboarding

import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnboardingTest {
    private class FakePrefs : OnboardingPrefs {
        // Backing fields, not `override var`: a var's JVM setter would clash with setOnboardingKind().
        private var kind: OnboardingKind? = null
        override val onboardingKind: OnboardingKind? get() = kind
        var kindWrites = 0
        override fun setOnboardingKind(kind: OnboardingKind) { kindWrites++; this.kind = kind }
    }

    private fun plugin(enabled: Boolean, unresponsive: Boolean = false, damaged: Boolean = false) = InstalledPlugin(
        PluginManifest("demo", "Demo", "1.0.0", 1, "plugin.js", "", "", "", listOf("example.com"), setOf("search", "resolve"), null, null),
        InstalledRecord("o/r", "1.0.0", "x", listOf("example.com"), 0L, enabled = enabled, unresponsive = unresponsive, damaged = damaged),
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

    // Amendment 2026-09-28 (user): the picker is mandatory. Whoever has no installed-and-enabled plugin
    // gets it at every start, new or updating device alike; nothing can mark it done.

    @Test fun `the picker opens on start for any recorded device with no installed and enabled plugin`() {
        for (kind in OnboardingKind.entries) {
            assertTrue(Onboarding.opensPickerOnStart(kind, plugins = emptyList()))
            assertTrue(Onboarding.opensPickerOnStart(kind, plugins = listOf(plugin(enabled = false))))
            assertFalse(Onboarding.opensPickerOnStart(kind, plugins = listOf(plugin(enabled = true))))
            assertFalse(Onboarding.opensPickerOnStart(kind, plugins = listOf(plugin(enabled = false), plugin(enabled = true))))
        }
    }

    @Test fun `an enabled plugin that merely stopped answering, or is damaged, never forces the picker`() {
        assertFalse(Onboarding.opensPickerOnStart(OnboardingKind.NEW, plugins = listOf(plugin(enabled = true, unresponsive = true))))
        assertFalse(Onboarding.opensPickerOnStart(OnboardingKind.LEGACY, plugins = listOf(plugin(enabled = true, damaged = true))))
    }

    @Test fun `an unrecorded kind never opens the picker`() {
        assertFalse(Onboarding.opensPickerOnStart(null, plugins = emptyList()))
    }

    @Test fun `the start decision waits for warm-up, so an updating device is judged after its Xuper migration`() = runTest {
        val warmedUp = MutableStateFlow(false)
        var installed = emptyList<InstalledPlugin>()
        val decision = async { Onboarding.opensPickerAfterWarmUp(warmedUp, { OnboardingKind.LEGACY }, { installed }) }
        runCurrent()
        assertFalse(decision.isCompleted) // nothing judged while the migration may still be running
        installed = listOf(plugin(enabled = true)) // the migration installed Xuper
        warmedUp.value = true
        assertFalse(decision.await())
    }

    @Test fun `after warm-up, an updating device whose migration installed nothing gets the picker`() = runTest {
        assertTrue(Onboarding.opensPickerAfterWarmUp(MutableStateFlow(true), { OnboardingKind.LEGACY }, { emptyList() }))
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
        // Mandatory picker: if the migration left nothing installed, the picker opens (after warm-up).
        assertTrue(Onboarding.opensPickerOnStart(prefs.onboardingKind, emptyList()))
    }

    @Test fun `installed fresh on this build and activated later -- new, never migrated`() {
        val prefs = FakePrefs()
        assertFalse(start(prefs, activated = false)) // warm-up runs before the activation screen
        // activation happens in this process; no second warm-up in it
        assertFalse(start(prefs, activated = true)) // next cold start
        assertEquals(OnboardingKind.NEW, prefs.onboardingKind)
        assertTrue(Onboarding.opensPickerOnStart(prefs.onboardingKind, emptyList()))
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
        }
        assertFalse(start(prefs, activated = true))
        assertFalse(Onboarding.opensPickerOnStart(prefs.onboardingKind, emptyList()))

        broken = false
        assertTrue(start(prefs, activated = true)) // never blocked forever
        assertEquals(OnboardingKind.LEGACY, prefs.onboardingKind)
    }
}
