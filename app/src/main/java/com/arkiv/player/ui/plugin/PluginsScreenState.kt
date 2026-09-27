package com.arkiv.player.ui.plugin

/*
 * The pure decisions behind the Plugins screen: which tab it opens on, when the "Agregar" modal is on
 * screen, whether its button does anything, and the label of the Instalados tab. Nothing here touches
 * Compose, so the phone and the TV share it and it is tested on the JVM.
 */

/** The two tabs of the Plugins screen. */
enum class PluginsTab { RECOMMENDED, INSTALLED }

/**
 * Which tab a screen opens on. Both entries give [PluginsTab.RECOMMENDED]: from Ajustes the person is
 * usually looking for something new, and the first-launch picker has nothing installed yet.
 */
internal fun initialPluginsTab(mode: AddPluginMode): PluginsTab = when (mode) {
    AddPluginMode.SETTINGS -> PluginsTab.RECOMMENDED
    AddPluginMode.ONBOARDING -> PluginsTab.RECOMMENDED
}

/**
 * Whether the "Agregar" modal must be shown. It needs the person's request ([requested]) and it yields to
 * anything that takes over the screen: the consent sheet (a preview succeeded, so the modal has done its
 * job and closes by itself), Configurar and the uninstall confirmation, which must never stack under it.
 */
internal fun addModalVisible(requested: Boolean, state: PluginsUiState): Boolean =
    requested && state.consent == null && state.configuring == null && state.confirmUninstall == null

/**
 * Whether "Agregar" does anything with the field: not while another action runs (the view model would
 * ignore it) and not for an empty or whitespace-only address ([PluginsViewModel.add] trims it too).
 */
internal fun canSubmitCustom(address: String, busy: Boolean): Boolean = !busy && address.trim().isNotEmpty()

/** The label of the Instalados tab: "Instalados", or "Instalados (n)" when [count] is above zero. */
internal fun installedTabLabel(count: Int): String = if (count > 0) "Instalados ($count)" else "Instalados"
