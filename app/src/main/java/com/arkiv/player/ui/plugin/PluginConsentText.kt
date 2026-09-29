package com.arkiv.player.ui.plugin

import com.arkiv.player.data.plugin.PluginHosts
import com.arkiv.player.data.plugin.XuperPrivilege

/**
 * What the Plugins screen shows for a plugin's network reach. The recognized Xuper repo, new or legacy
 * ([XuperPrivilege.isOfficial]), never calls `kino.fetch` itself -- its real network activity runs
 * inside privileged native functions the sandbox's host gate never sees -- so a host list would be
 * meaningless for it; every other plugin gets the honest list of hosts it declared/was approved for.
 */
/**
 * An installed plugin's broad video permission, said plainly on its details (phone sheet, TV actions
 * dialog) next to "Quitar permiso de video amplio"; null when the person never granted it.
 */
fun installedAnyVideoHostLine(record: com.arkiv.player.data.plugin.InstalledRecord): String? =
    if (record.anyVideoHost) "Puede reproducir video desde cualquier servidor" else null

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
    if (XuperPrivilege.isOfficial(address)) {
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
