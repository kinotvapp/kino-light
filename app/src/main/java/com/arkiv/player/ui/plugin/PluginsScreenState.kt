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
 * Whether the person's "Agregar" request ([requested]) outlives their answer to the consent sheet. Cancelling
 * keeps it: the sheet only took the screen over, so the modal reopens with the address still typed.
 * Confirming ends it: the install carries on behind the progress bar and the message of the main screen, and
 * the modal must not come back over it (the address is still in the field until the install completes, so
 * [addModalVisible] alone would raise it again). A sheet that did not come from the modal ([requested] is
 * false: an update check, a card) never raises it.
 */
internal fun addRequestAfterConsent(requested: Boolean, confirmed: Boolean): Boolean = requested && !confirmed

/**
 * What the address field holds after the person dismisses the "Agregar" dialog with Cancelar or Back: nothing.
 * The dialog then reopens empty. A keyboard types at the end of what the field holds (on the TV, always), so a
 * dialog that reopened with the old text made the person type a doubled repository. Cancelling the CONSENT sheet
 * the dialog raised is not a dismissal ([addRequestAfterConsent]): the dialog returns with the text as it was
 * left, and a confirmed install clears the address itself when it completes. Applied through
 * [PluginsViewModel.onAddressChange], which also drops the message the last try left.
 */
internal fun addressAfterDialogDismissed(): String = ""

/**
 * Whether "Agregar" does anything with the field: not while another action runs (the view model would
 * ignore it) and not for an empty or whitespace-only address ([PluginsViewModel.add] trims it too).
 */
internal fun canSubmitCustom(address: String, busy: Boolean): Boolean = !busy && address.trim().isNotEmpty()

/** The label of the Instalados tab: "Instalados", or "Instalados (n)" when [count] is above zero. */
internal fun installedTabLabel(count: Int): String = if (count > 0) "Instalados ($count)" else "Instalados"
