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

/** How many hosts the consent sheet lists before the rest folds behind "Ver todos". */
const val CONSENT_HOSTS_COLLAPSED = 3

/**
 * What the consent sheet's host section shows. [visible] is the rows drawn right now; [hiddenCount]
 * (and how many of those are new, [hiddenNewCount]) is what the "y N más" line summarizes while
 * collapsed; [collapsible] says whether the "Ver todos" / "Ver menos" toggle exists at all.
 */
data class ConsentHostSummary(
    val visible: List<String>,
    val hiddenCount: Int,
    val hiddenNewCount: Int,
    val collapsible: Boolean,
)

/**
 * The consent sheet's host list, folded to [limit] rows unless [expanded]. The new hosts of an
 * update ([newHosts], empty on a fresh install) come first, so the collapsed view shows what the
 * person has not approved yet rather than hosts they already did; the order is otherwise kept.
 * At most [limit] hosts means no toggle and nothing hidden.
 */
fun pluginConsentHostSummary(
    hosts: List<String>,
    newHosts: Collection<String>,
    expanded: Boolean,
    limit: Int = CONSENT_HOSTS_COLLAPSED,
): ConsentHostSummary {
    val (fresh, known) = hosts.partition { it in newHosts }
    val ordered = fresh + known
    val collapsible = ordered.size > limit
    val visible = if (collapsible && !expanded) ordered.take(limit) else ordered
    val hidden = ordered.drop(visible.size)
    return ConsentHostSummary(visible, hidden.size, hidden.count { it in newHosts }, collapsible)
}

/** The heading above the host rows: the total is said up front whenever some of them can be folded away. */
fun pluginConsentHostsHeader(count: Int, collapsible: Boolean): String =
    if (collapsible) "Se va a conectar con $count servidores:" else "Se va a conectar con:"

/** The collapsed sheet's "y N más" line, naming how many of the folded hosts are new; null when nothing is hidden. */
fun pluginConsentHiddenHostsLine(summary: ConsentHostSummary): String? {
    if (summary.hiddenCount == 0) return null
    val news = when (summary.hiddenNewCount) {
        0 -> ""
        1 -> " (1 nuevo)"
        else -> " (${summary.hiddenNewCount} nuevos)"
    }
    return "y ${summary.hiddenCount} más$news"
}
