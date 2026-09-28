package com.arkiv.player.data.onboarding

import com.arkiv.player.data.plugin.InstalledPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Whether this device was already activated before the build that brought "Elige tus fuentes"
 * (spec 2026-09-28 §4). [LEGACY] (updating) gets the Xuper migration; [NEW] picks its own sources.
 * Either one gets the picker at start while it has no source ([Onboarding.opensPickerOnStart]).
 */
enum class OnboardingKind(val wire: String) {
    LEGACY("legacy"),
    NEW("new");

    companion object {
        fun fromWire(value: String?): OnboardingKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Where the onboarding kind persists ([com.arkiv.player.data.SettingsStore]); faked in tests. */
interface OnboardingPrefs {
    /** Null until [Onboarding.classifyOnce] has run on this build. */
    val onboardingKind: OnboardingKind?

    /** Blocking (commit()); call off the main thread. */
    fun setOnboardingKind(kind: OnboardingKind)
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
     * A source, for the mandatory picker: at least one plugin installed AND switched on. Deliberately not
     * [InstalledPlugin.isUsable]: a plugin that merely timed out (unresponsive) or whose files need a
     * reinstall (damaged) is still the person's choice, and must not throw them back to the picker.
     * "Listo" uses the same rule, so it is enabled exactly when the next start would not reopen the picker.
     */
    fun hasSource(plugins: List<InstalledPlugin>): Boolean = plugins.any { it.record.enabled }

    /**
     * Amendment 2026-09-28 (user; replaces ruling R8's "never finished it" and "new devices only"): the
     * picker opens at every start, new or updating device alike, while there is no [hasSource]; it is the
     * only way into the app. An unrecorded kind never opens it: classification failed this start, so this
     * may be an updating device whose Xuper migration was skipped ([runsXuperMigration] of null) and will
     * run on the next start.
     */
    fun opensPickerOnStart(kind: OnboardingKind?, plugins: List<InstalledPlugin>): Boolean =
        kind != null && !hasSource(plugins)

    /**
     * [opensPickerOnStart], decided only once [ready] is true (`AppGraph.pickerDecisionReady`): for an
     * updating device that is after this start's Xuper migration step ([decisionWaitsForMigration]), so
     * nobody about to get Xuper installed sees the picker flash; for any other device it is as soon as the
     * kind is recorded, never the rest of warm-up. [kind] and [plugins] are read after the wait, never before.
     */
    suspend fun opensPickerWhenReady(
        ready: Flow<Boolean>,
        kind: () -> OnboardingKind?,
        plugins: () -> List<InstalledPlugin>,
    ): Boolean {
        ready.first { it }
        return opensPickerOnStart(kind(), plugins())
    }

    /**
     * Whether warm-up holds the start decision until its Xuper migration step ran: only for an updating
     * device, the only kind that runs it ([runsXuperMigration]). Any other kind is decided as soon as it is
     * recorded, so the slow rest of warm-up (the 3DES, the registry, a GitHub preview) never leaves Home
     * usable without a source.
     */
    fun decisionWaitsForMigration(kind: OnboardingKind?): Boolean = runsXuperMigration(kind)
}
