package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginHosts
import com.arkiv.player.data.plugin.XuperPrivilege

/**
 * What Ajustes ▸ Plugins shows for a plugin's network reach. The recognized Xuper repo
 * ([XuperPrivilege.SOURCE_REPO]) never calls `kino.fetch` itself -- its real network activity runs
 * inside privileged native functions the sandbox's host gate never sees -- so a host list would be
 * meaningless for it; every other plugin gets the honest list of hosts it declared/was approved for.
 */
fun pluginConsentHostLine(address: String, hostsLabel: String): String =
    pluginConsentProtectedLine(address)
        // No declared host (apiVersion 2 with a `url` setting) and no server typed yet: never an empty list.
        ?: if (hostsLabel.isBlank()) "Se conectará solo a los servidores que escribas en su configuración" else "Se conectará a: $hostsLabel"

/**
 * The line that replaces the host list for the recognized Xuper repo, or null for every other
 * address. [address] is the canonical form the gate compares (`InstallPreview.address.canonical`
 * on the install sheet, `InstalledRecord.address` on an installed row), so both screens agree with
 * [XuperPrivilege.grants] on exactly which installs get it.
 */
fun pluginConsentProtectedLine(address: String): String? =
    if (address == XuperPrivilege.SOURCE_REPO) {
        "Este plugin usa la conexión protegida de Xuper dentro de la app; no se conecta a internet por su cuenta."
    } else {
        null
    }

/**
 * The hosts the install sheet lists under "Se va a conectar con:": the declared ones, minus any
 * reserved `.invalid` placeholder ([PluginHosts.isReservedInvalid]). Empty -- a plugin whose only
 * reach is the server the person types -- means the sheet shows no header at all.
 */
fun pluginConsentHosts(hosts: List<String>): List<String> = hosts.filterNot(PluginHosts::isReservedInvalid)
