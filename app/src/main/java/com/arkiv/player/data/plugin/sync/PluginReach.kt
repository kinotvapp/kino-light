package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.plugin.HostRules
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.InstallPreview
import com.arkiv.player.data.plugin.ManifestParser
import com.arkiv.player.data.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the person approved for one plugin on one of their devices, as it travels in a synced
 * `plugin_installs` row: the hosts (declared and approved on the spot), the hosts they said "no" to,
 * the permissions, capabilities, insecure hosts and every "any" flag. The other device installs or
 * updates the plugin WITHOUT asking only when what IT fetched asks for nothing beyond this ([covers]);
 * anything more goes to the normal consent sheet. Never the plugin's code: each device fetches that itself.
 */
data class PluginReach(
    val hosts: List<String> = emptyList(),
    val rejectedHosts: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val insecureHosts: List<String> = emptyList(),
    val liveStreamHostsAny: Boolean = false,
    val streamHostsAny: Boolean = false,
    val fetchHostsAny: Boolean = false,
    val sealedSecrets: Boolean = false,
    /** The broad video permission ("Permitir video de cualquier servidor"), granted in a dialog. */
    val anyVideoHost: Boolean = false,
) {
    /**
     * Whether installing [preview] would grant nothing beyond this approval: every host it declares
     * approved (itself, or under an approved `*.` pattern), every permission, every capability that
     * needs consent, every insecure host, and each "any" flag it asks for. A Nuvio preview's
     * `fetchHosts: "any"` counts only for a Nuvio preview, as in the installer.
     */
    fun covers(preview: InstallPreview): Boolean {
        val m = preview.manifest
        val hostsOk = m.hosts.all { h -> h in hosts || (!h.startsWith("*.") && HostRules.matches(h, hosts)) }
        val capsOk = m.capabilities.filter { it in ManifestParser.APPROVAL_CAPABILITIES }.all { it in capabilities }
        return hostsOk &&
            m.permissions.all { it in permissions } &&
            capsOk &&
            m.insecureHosts.all { it in insecureHosts } &&
            (!m.liveStreamHostsAny || liveStreamHostsAny) &&
            (!m.streamHostsAny || streamHostsAny) &&
            (!(m.fetchHostsAny && preview.nuvioOrigin != null) || fetchHostsAny) &&
            (m.secrets.isEmpty() || sealedSecrets)
    }

    /** Both approvals together: every list joined, every flag granted on either side (capped like the record). */
    fun union(other: PluginReach): PluginReach = PluginReach(
        hosts = (hosts + other.hosts).distinct().take(PluginRegistry.MAX_APPROVED_HOSTS),
        rejectedHosts = (rejectedHosts + other.rejectedHosts).distinct().takeLast(PluginRegistry.MAX_REJECTED_HOSTS),
        permissions = (permissions + other.permissions).distinct(),
        capabilities = (capabilities + other.capabilities).distinct(),
        insecureHosts = (insecureHosts + other.insecureHosts).distinct(),
        liveStreamHostsAny = liveStreamHostsAny || other.liveStreamHostsAny,
        streamHostsAny = streamHostsAny || other.streamHostsAny,
        fetchHostsAny = fetchHostsAny || other.fetchHostsAny,
        sealedSecrets = sealedSecrets || other.sealedSecrets,
        anyVideoHost = anyVideoHost || other.anyVideoHost,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("hosts", JSONArray(hosts))
        .put("rejectedHosts", JSONArray(rejectedHosts))
        .put("permissions", JSONArray(permissions))
        .put("capabilities", JSONArray(capabilities))
        .put("insecureHosts", JSONArray(insecureHosts))
        .put("liveStreamHostsAny", liveStreamHostsAny)
        .put("streamHostsAny", streamHostsAny)
        .put("fetchHostsAny", fetchHostsAny)
        .put("sealedSecrets", sealedSecrets)
        .put("anyVideoHost", anyVideoHost)

    companion object {
        const val MAX_NAMES = 32
        private val NAME = Regex("^[a-z][a-z0-9_-]{0,31}$")

        /** What [record] says the person approved on THIS device. */
        fun fromRecord(record: InstalledRecord): PluginReach = PluginReach(
            hosts = record.hosts,
            rejectedHosts = record.rejectedHosts,
            permissions = record.permissions,
            capabilities = record.capabilities,
            insecureHosts = record.insecureHosts,
            liveStreamHostsAny = record.liveStreamHostsAny,
            streamHostsAny = record.streamHostsAny,
            fetchHostsAny = record.fetchHostsAny,
            sealedSecrets = record.sealedSecrets,
            anyVideoHost = record.anyVideoHost,
        )

        /**
         * A peer's approval, read defensively: anything that is not a well-formed public host or a short
         * lowercase name is dropped (never the whole row), and every list is capped like the record's own.
         */
        fun fromJson(o: JSONObject?): PluginReach {
            if (o == null) return PluginReach()
            fun strings(key: String, max: Int, ok: (String) -> Boolean): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until minOf(a.length(), max)).mapNotNull { a.opt(it) as? String }.filter(ok).distinct()
            }
            val host: (String) -> Boolean = { HostRules.isValidPattern(it) || HostRules.isPublicIpv4Literal(it) }
            return PluginReach(
                hosts = strings("hosts", PluginRegistry.MAX_APPROVED_HOSTS, host),
                rejectedHosts = strings("rejectedHosts", PluginRegistry.MAX_REJECTED_HOSTS, host),
                permissions = strings("permissions", MAX_NAMES) { NAME.matches(it) },
                capabilities = strings("capabilities", MAX_NAMES) { NAME.matches(it) },
                insecureHosts = strings("insecureHosts", PluginRegistry.MAX_APPROVED_HOSTS, host),
                liveStreamHostsAny = o.optBoolean("liveStreamHostsAny"),
                streamHostsAny = o.optBoolean("streamHostsAny"),
                fetchHostsAny = o.optBoolean("fetchHostsAny"),
                sealedSecrets = o.optBoolean("sealedSecrets"),
                anyVideoHost = o.optBoolean("anyVideoHost"),
            )
        }
    }
}
