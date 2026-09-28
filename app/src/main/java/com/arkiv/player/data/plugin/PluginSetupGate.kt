package com.arkiv.player.data.plugin

/**
 * The one place a plugin that still needs setup is stopped: every capability call of a plugin
 * whose required settings are empty fails with `auth_required` WITHOUT running (spec §1.3) —
 * search, Home, "Ver más", episodes and resolve all go through the [PluginCaller] AppGraph builds
 * with this. [needsSetup] reads the registry's cached state: no IO here.
 */
class SetupGatedCaller(
    private val needsSetup: (pluginId: String) -> Boolean,
    private val delegate: PluginCaller,
) : PluginCaller {
    override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
        if (needsSetup(pluginId)) throw PluginErrorException(PluginErrors.AUTH_REQUIRED, "")
        return delegate.call(pluginId, function, argJson, timeoutMs)
    }
}

/**
 * After a playback failure, whether to resolve the plugin title once more: only when the plugin
 * said its URLs expire ([expiresInSeconds] > 0), that time has passed since [resolvedAtMs], and it
 * hasn't been retried yet (spec §3.5). Pure: `PlayerViewModel` keeps one per loaded plugin stream.
 */
data class PluginStreamExpiry(val resolvedAtMs: Long, val expiresInSeconds: Int, val retried: Boolean = false) {
    fun shouldResolveAgain(nowMs: Long): Boolean =
        expiresInSeconds > 0 && !retried && nowMs - resolvedAtMs >= expiresInSeconds * 1000L
}

/**
 * One extra line of the consent sheet; [warning] ones get the warning icon, [isNew] the "nuevo"
 * chip, [danger] draws the text itself in red (an insecure connection, never combined with [warning]).
 */
data class ConsentLine(val text: String, val warning: Boolean = false, val isNew: Boolean = false, val danger: Boolean = false)

/** The consent sheet's lines beyond the host list (spec §1.2, §1.3, and round 2's §"Security"). Pure. */
object PluginConsent {
    fun extraLines(preview: InstallPreview): List<ConsentLine> {
        val m = preview.manifest
        val out = ArrayList<ConsentLine>()
        m.permissions.forEach { p -> out += ConsentLine("Permiso: $p", warning = true, isNew = preview.isUpdate && p in preview.newPermissions) }
        if (m.settings.any { it.type == SettingType.PASSWORD }) out += ConsentLine("Este plugin usa tu usuario y contraseña")
        if (m.settings.any { it.type == SettingType.URL }) out += ConsentLine("Se conectará a los servidores que escribas en su configuración")
        if ("download" in m.capabilities) {
            out += ConsentLine("Puede descargar videos para verlos sin conexión", isNew = preview.isUpdate && "download" in preview.newCapabilities)
        }
        if ("drm" in m.capabilities) {
            out += ConsentLine("Reproduce video protegido (DRM)", isNew = preview.isUpdate && "drm" in preview.newCapabilities)
        }
        if (ManifestParser.CHANNELS in m.capabilities) {
            out += ConsentLine("Agrega canales en vivo a la pestaña En vivo", isNew = preview.isUpdate && ManifestParser.CHANNELS in preview.newCapabilities)
        }
        m.insecureHosts.forEach { host ->
            out += ConsentLine("Conexión sin cifrar con $host", danger = true, isNew = preview.isUpdate && host in preview.newInsecureHosts)
        }
        return out
    }
}
