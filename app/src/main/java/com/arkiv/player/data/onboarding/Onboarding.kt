package com.arkiv.player.data.onboarding

import com.arkiv.player.data.plugin.InstalledPlugin

/**
 * Whether this device was already activated before the build that brought "Elige tus fuentes"
 * (spec 2026-09-28 §4). [LEGACY] (updating) keeps everything as it was: the Xuper migration, no
 * picker. [NEW] picks its own sources.
 */
enum class OnboardingKind(val wire: String) {
    LEGACY("legacy"),
    NEW("new");

    companion object {
        fun fromWire(value: String?): OnboardingKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Where the two onboarding facts persist ([com.arkiv.player.data.SettingsStore]); faked in tests. */
interface OnboardingPrefs {
    /** Null until [Onboarding.classifyOnce] has run on this build. */
    val onboardingKind: OnboardingKind?

    /** Blocking (commit()); call off the main thread. */
    fun setOnboardingKind(kind: OnboardingKind)

    /** "Listo" or "Ahora no" was pressed once: the picker never opens by itself again. */
    val sourcePickerDone: Boolean
    fun setSourcePickerDone(done: Boolean)
}

object Onboarding {
    /**
     * Records the kind the first time it is asked on this build and returns the recorded one ever after,
     * whatever [activated] says later (a device activated after this build shipped stays [OnboardingKind.NEW]).
     * [activated]: credentials were present at that first start (ruling R11).
     */
    fun classifyOnce(prefs: OnboardingPrefs, activated: Boolean): OnboardingKind {
        prefs.onboardingKind?.let { return it }
        val kind = if (activated) OnboardingKind.LEGACY else OnboardingKind.NEW
        prefs.setOnboardingKind(kind)
        return kind
    }

    /** The Xuper auto-install migration is for devices that used Xuper before plugins existed. */
    fun runsXuperMigration(kind: OnboardingKind?): Boolean = kind == OnboardingKind.LEGACY

    /**
     * Ruling R8: the picker opens by itself when the root composes, for a new device that never finished it
     * and has no usable plugin. That is right after activation, and again after a start that died mid-picker.
     * An unrecorded kind never opens it.
     */
    fun opensPickerOnStart(kind: OnboardingKind?, pickerDone: Boolean, plugins: List<InstalledPlugin>): Boolean =
        kind == OnboardingKind.NEW && !pickerDone && plugins.none { it.isUsable }
}
