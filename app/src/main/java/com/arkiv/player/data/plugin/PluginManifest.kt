package com.arkiv.player.data.plugin

import org.json.JSONObject

/** A validated `kino-plugin.json`. Build it only through [ManifestParser.parse]. */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val entry: String,
    val description: String,
    val author: String,
    val homepage: String,
    val hosts: List<String>,
    val capabilities: Set<String>,
    /** `#RRGGBB` uppercased, or null for the neutral default (`PluginColors.DEFAULT`). */
    val color: String?,
    val icon: String?,
    /** From the closed list in [PluginSettings.PERMISSIONS] (empty in SDK v1); shown on the consent sheet. */
    val permissions: List<String> = emptyList(),
    /** What the plugin asks the person to configure (Ajustes ▸ Plugins ▸ Configurar). */
    val settings: List<PluginSetting> = emptyList(),
)

sealed interface ManifestResult {
    data class Valid(val manifest: PluginManifest) : ManifestResult
    /** [message] is shown to the person as-is: Spanish, naming the field. */
    data class Invalid(val field: String, val message: String) : ManifestResult
}

/**
 * Parses and validates a plugin manifest. Every rule here is in the spec's validation table; the
 * installer refuses on the first failure and shows [ManifestResult.Invalid.message].
 */
object ManifestParser {
    const val SUPPORTED_API = 1
    const val MAX_BYTES = 16 * 1024
    const val MIN_HOSTS = 1
    const val MAX_HOSTS = 20
    const val MAX_NAME_CHARS = 40
    const val MAX_DESCRIPTION_CHARS = 300
    const val MAX_AUTHOR_CHARS = 60
    const val MAX_HOMEPAGE_CHARS = 200
    val RESERVED_IDS = setOf("magis", "ditu", "live", "local", "unknown", "plugin")
    val CAPABILITIES = setOf("search", "home", "browse", "episodes", "resolve")
    val REQUIRED_CAPABILITIES = listOf("resolve")
    val AT_LEAST_ONE_OF_CAPABILITIES = listOf("search", "home")

    /** `internal`, not public API of [PluginManifest]: exposed only so tests can pin `contract.json`'s `idPattern` to it. */
    internal val ID = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

    /** `internal`, not public API of [PluginManifest]: exposed only so tests can pin `contract.json`'s `colorPattern` to it. */
    internal val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val PATH_SEGMENT = Regex("^[A-Za-z0-9._-]+$")

    fun parse(text: String, knownPermissions: Set<String> = PluginSettings.PERMISSIONS): ManifestResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return invalid("kino-plugin.json", "El manifiesto pesa más de 16 KB")
        }
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return invalid("kino-plugin.json", "El manifiesto no es un JSON válido")

        val id = o.optString("id")
        if (!ID.matches(id)) return invalid("id", "El campo \"id\" debe tener de 2 a 40 letras minúsculas, números o guiones")
        if (id in RESERVED_IDS) return invalid("id", "El id \"$id\" está reservado por Kino")

        val name = o.optString("name").trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return invalid("name", "El campo \"name\" debe tener de 1 a $MAX_NAME_CHARS caracteres")

        val version = o.optString("version")
        if (!SemVer.isValid(version)) return invalid("version", "El campo \"version\" debe ser del tipo 1.2.3")

        val api = o.opt("apiVersion")
        if (api !is Int) return invalid("apiVersion", "El campo \"apiVersion\" debe ser un número entero")
        if (api > SUPPORTED_API) return invalid("apiVersion", "Este plugin necesita una versión más nueva de Kino")
        if (api < 1) return invalid("apiVersion", "El campo \"apiVersion\" debe ser 1 o mayor")

        val entry = o.optString("entry")
        if (!isSafeRelativePath(entry) || !entry.endsWith(".js")) {
            return invalid("entry", "El campo \"entry\" debe ser una ruta relativa a un archivo .js")
        }

        val hostsJson = o.optJSONArray("hosts") ?: return invalid("hosts", "Falta el campo \"hosts\"")
        val hosts = (0 until hostsJson.length()).map { hostsJson.opt(it) as? String ?: "" }
        if (hosts.size < MIN_HOSTS || hosts.size > MAX_HOSTS) return invalid("hosts", "El campo \"hosts\" debe tener de $MIN_HOSTS a $MAX_HOSTS dominios")
        hosts.firstOrNull { !HostRules.isValidPattern(it) }?.let {
            return invalid("hosts", "El dominio \"$it\" no está permitido")
        }

        val capsJson = o.optJSONArray("capabilities") ?: return invalid("capabilities", "Falta el campo \"capabilities\"")
        val caps = (0 until capsJson.length()).map { capsJson.opt(it) as? String ?: "" }.toSet()
        caps.firstOrNull { it !in CAPABILITIES }?.let { return invalid("capabilities", "Capacidad desconocida: \"$it\"") }
        REQUIRED_CAPABILITIES.firstOrNull { it !in caps }?.let { return invalid("capabilities", "El plugin debe declarar \"$it\"") }
        if (AT_LEAST_ONE_OF_CAPABILITIES.none { it in caps }) {
            return invalid("capabilities", "El plugin debe declarar \"" + AT_LEAST_ONE_OF_CAPABILITIES.joinToString("\" o \"") + "\"")
        }

        val color = o.optString("color").takeIf { it.isNotEmpty() }
        if (color != null && !COLOR.matches(color)) return invalid("color", "El campo \"color\" debe ser del tipo #RRGGBB")

        val icon = o.optString("icon").takeIf { it.isNotEmpty() }
        if (icon != null && (!isSafeRelativePath(icon) || !icon.endsWith(".png"))) {
            return invalid("icon", "El campo \"icon\" debe ser una ruta relativa a un .png")
        }

        if (o.has("permissions") && o.optJSONArray("permissions") == null) {
            return invalid("permissions", "El campo \"permissions\" debe ser una lista")
        }
        val permissions = when (val p = PluginSettings.parsePermissions(o.optJSONArray("permissions"), knownPermissions)) {
            is PluginSettings.Parsed.Error -> return invalid("permissions", p.message)
            is PluginSettings.Parsed.Ok -> p.value
        }
        if (o.has("settings") && o.optJSONArray("settings") == null) {
            return invalid("settings", "El campo \"settings\" debe ser una lista")
        }
        val settings = when (val p = PluginSettings.parseSettings(o.optJSONArray("settings"))) {
            is PluginSettings.Parsed.Error -> return invalid("settings", p.message)
            is PluginSettings.Parsed.Ok -> p.value
        }

        return ManifestResult.Valid(
            PluginManifest(
                id = id, name = name, version = version, apiVersion = api, entry = entry,
                description = text(o, "description", MAX_DESCRIPTION_CHARS), author = text(o, "author", MAX_AUTHOR_CHARS),
                homepage = text(o, "homepage", MAX_HOMEPAGE_CHARS), hosts = hosts.distinct(), capabilities = caps,
                color = color?.uppercase(), icon = icon, permissions = permissions, settings = settings,
            ),
        )
    }

    /** A path inside the plugin's repo folder: no absolute paths, no `..`, no backslashes. */
    fun isSafeRelativePath(p: String): Boolean =
        p.isNotEmpty() && p.length <= 200 && !p.startsWith("/") && '\\' !in p &&
            p.split('/').all { it.isNotEmpty() && it != "." && it != ".." && PATH_SEGMENT.matches(it) }

    private fun text(o: JSONObject, key: String, max: Int): String =
        (o.opt(key) as? String).orEmpty().trim().take(max)

    private fun invalid(field: String, message: String) = ManifestResult.Invalid(field, message)
}
