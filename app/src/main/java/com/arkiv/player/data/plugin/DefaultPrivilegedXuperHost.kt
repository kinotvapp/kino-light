package com.arkiv.player.data.plugin

/**
 * The production [PrivilegedXuperHost]. [DefaultPluginHost] is `final` (this codebase has no other
 * open production class), so every ordinary `kino.*` member -- fetch/storage/cookies/config/crypto,
 * all unchanged from any other installed plugin -- is delegated to one, by interface delegation
 * rather than inheritance. Only the 5 `xuper*` members are this class's own, and every one of them
 * is a stub here: Tasks 5-10 replace each throw with the real, protected Magis call.
 */
class DefaultPrivilegedXuperHost(
    id: String,
    http: PluginHttp,
    storage: PluginStorage,
    config: PluginConfig,
    cookies: PluginCookies?,
    hosts: EffectiveHosts,
) : PluginHost by DefaultPluginHost(id, http, storage, config, cookies, hosts), PrivilegedXuperHost {
    override suspend fun xuperSearch(argsJson: String): String = throw NotImplementedError("Task 5")
    override suspend fun xuperHome(): String = throw NotImplementedError("Task 9")
    override suspend fun xuperBrowse(ref: String, cursor: String?): String = throw NotImplementedError("Task 10")
    override suspend fun xuperEpisodes(ref: String): String = throw NotImplementedError("Task 7")
    override suspend fun xuperResolve(ref: String): String = throw NotImplementedError("Task 6")
}

/**
 * The one production decision of which [PluginHost] a plugin's runtime gets: [DefaultPrivilegedXuperHost]
 * only for the plugin [XuperPrivilege.grants], [DefaultPluginHost] for every other one.
 * `AppGraph.openPluginRuntime` calls this rather than inlining the branch, and so does
 * `XuperPrivilegeGateTest` -- the SAME function, not a reproduction of its condition, so a later
 * change to the gate (or its removal) can't leave a test green while the real gate silently breaks.
 */
fun pluginHostFor(
    plugin: InstalledPlugin,
    http: PluginHttp,
    storage: PluginStorage,
    config: PluginConfig,
    cookies: PluginCookies?,
    hosts: EffectiveHosts,
): PluginHost = if (XuperPrivilege.grants(plugin.record)) {
    DefaultPrivilegedXuperHost(plugin.id, http, storage, config, cookies, hosts)
} else {
    DefaultPluginHost(plugin.id, http, storage, config, cookies, hosts)
}
