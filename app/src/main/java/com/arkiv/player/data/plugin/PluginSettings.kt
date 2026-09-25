package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The five kinds of field a plugin can ask the person for; [wire] is the manifest's `type`. */
enum class SettingType(val wire: String, val maxChars: Int, val canBeRequired: Boolean) {
    TEXT("text", 500, true),
    URL("url", 2048, true),
    PASSWORD("password", 500, true),
    TOGGLE("toggle", 0, false),
    SELECT("select", 0, false),
    ;

    companion object {
        fun of(wire: String): SettingType? = entries.firstOrNull { it.wire == wire }
    }
}

data class SettingOption(val value: String, val label: String)

/**
 * One entry of the manifest's `settings`. [default] is a `String` (text, url, password, select),
 * a `Boolean` (toggle) or null. A toggle or select always has a value, so it can't be [required].
 */
data class PluginSetting(
    val key: String,
    val label: String,
    val type: SettingType,
    val required: Boolean = false,
    val hint: String = "",
    val default: Any? = null,
    val options: List<SettingOption> = emptyList(),
)

/**
 * The manifest's `settings` and `permissions` blocks, and the rule every value the person types
 * must meet. Messages are Spanish and shown as-is (install refusals, the Configurar screen).
 */
object PluginSettings {
    const val MAX_SETTINGS = 12
    const val MAX_LABEL_CHARS = 40
    const val MAX_HINT_CHARS = 80
    const val MAX_OPTIONS = 20
    const val MAX_OPTION_VALUE_CHARS = 40
    const val MAX_OPTION_LABEL_CHARS = 40
    val KEY = Regex("^[a-z][a-zA-Z0-9_]{0,31}$")

    /** The closed list of permissions a manifest may ask for. Empty in SDK v1: live adds names. */
    val PERMISSIONS: Set<String> = emptySet()

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Error(val message: String) : Parsed<Nothing>
    }

    /** [known] is [PERMISSIONS]; tests pass their own to exercise the mechanism SDK v1 can't reach. */
    fun parsePermissions(array: JSONArray?, known: Set<String> = PERMISSIONS): Parsed<List<String>> {
        if (array == null) return Parsed.Ok(emptyList())
        val out = ArrayList<String>()
        for (i in 0 until array.length()) {
            val p = array.opt(i) as? String ?: return Parsed.Error("El campo \"permissions\" solo puede tener textos")
            if (p !in known) return Parsed.Error("permiso desconocido: ${p.take(40)}")
            if (p !in out) out += p
        }
        return Parsed.Ok(out)
    }

    fun parseSettings(array: JSONArray?): Parsed<List<PluginSetting>> {
        if (array == null) return Parsed.Ok(emptyList())
        if (array.length() > MAX_SETTINGS) return Parsed.Error("El plugin pide más de $MAX_SETTINGS ajustes")
        val out = ArrayList<PluginSetting>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: return Parsed.Error("El ajuste #${i + 1} no es válido")
            val key = o.opt("key") as? String ?: ""
            if (!KEY.matches(key)) return Parsed.Error("El ajuste #${i + 1} tiene una clave inválida")
            if (out.any { it.key == key }) return Parsed.Error("El ajuste \"$key\" está repetido")
            val label = (o.opt("label") as? String).orEmpty().trim()
            if (label.isEmpty() || label.length > MAX_LABEL_CHARS) return Parsed.Error("El ajuste \"$key\" necesita un nombre de 1 a $MAX_LABEL_CHARS caracteres")
            val type = SettingType.of(o.opt("type") as? String ?: "") ?: return Parsed.Error("El ajuste \"$key\" tiene un tipo desconocido")
            val hint = (o.opt("hint") as? String).orEmpty().trim()
            if (hint.length > MAX_HINT_CHARS) return Parsed.Error("La ayuda del ajuste \"$key\" pasa de $MAX_HINT_CHARS caracteres")
            val required = o.opt("required")
            if (required != null && required !is Boolean) return Parsed.Error("\"required\" del ajuste \"$key\" debe ser true o false")
            if (required == true && !type.canBeRequired) return Parsed.Error("El ajuste \"$key\" no puede ser obligatorio")
            val options = if (type == SettingType.SELECT) {
                when (val parsed = parseOptions(key, o.optJSONArray("options"))) {
                    is Parsed.Error -> return parsed
                    is Parsed.Ok -> parsed.value
                }
            } else {
                emptyList()
            }
            val default = when (val parsed = parseDefault(key, type, o.opt("default"), options)) {
                is Parsed.Error -> return parsed
                is Parsed.Ok -> parsed.value
            }
            out += PluginSetting(key, label, type, required == true, hint, default, options)
        }
        return Parsed.Ok(out)
    }

    private fun parseOptions(key: String, array: JSONArray?): Parsed<List<SettingOption>> {
        if (array == null || array.length() == 0) return Parsed.Error("El ajuste \"$key\" necesita opciones")
        if (array.length() > MAX_OPTIONS) return Parsed.Error("El ajuste \"$key\" tiene más de $MAX_OPTIONS opciones")
        val out = ArrayList<SettingOption>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: return Parsed.Error("Una opción del ajuste \"$key\" no es válida")
            val value = o.opt("value") as? String ?: ""
            val label = (o.opt("label") as? String).orEmpty().trim()
            if (value.isEmpty() || value.length > MAX_OPTION_VALUE_CHARS || label.isEmpty() || label.length > MAX_OPTION_LABEL_CHARS) {
                return Parsed.Error("Una opción del ajuste \"$key\" no es válida")
            }
            if (out.any { it.value == value }) return Parsed.Error("El ajuste \"$key\" repite la opción \"$value\"")
            out += SettingOption(value, label)
        }
        return Parsed.Ok(out)
    }

    private fun parseDefault(key: String, type: SettingType, raw: Any?, options: List<SettingOption>): Parsed<Any?> {
        if (raw == null || raw == JSONObject.NULL) {
            return Parsed.Ok(
                when (type) {
                    SettingType.TOGGLE -> false
                    SettingType.SELECT -> options.first().value
                    else -> null
                },
            )
        }
        val fits = when (type) {
            SettingType.TOGGLE -> raw is Boolean
            SettingType.SELECT -> raw is String && options.any { it.value == raw }
            else -> raw is String && validateValue(PluginSetting(key, key, type), raw) == null
        }
        return if (fits) Parsed.Ok(raw) else Parsed.Error("El valor por defecto del ajuste \"$key\" no sirve para su tipo")
    }

    /**
     * Null when [value] is acceptable for [setting]; otherwise the Spanish reason. Blank text is
     * "unset" (required-ness is checked by [missingRequired], not here).
     */
    fun validateValue(setting: PluginSetting, value: Any?): String? = when (setting.type) {
        SettingType.TOGGLE -> if (value is Boolean) null else "\"${setting.label}\" debe estar activado o desactivado"
        SettingType.SELECT -> if (value is String && setting.options.any { it.value == value }) null else "Elige una opción de \"${setting.label}\""
        else -> when {
            value !is String -> "\"${setting.label}\" no es un texto"
            value.length > setting.type.maxChars -> "\"${setting.label}\" pasa de ${setting.type.maxChars} caracteres"
            setting.type == SettingType.URL && value.isNotBlank() && PluginHosts.userHostOf(value) == null ->
                "\"${setting.label}\" no es una dirección válida (ejemplo: http://192.168.1.10:8096)"
            else -> null
        }
    }

    /** The required settings that have no usable value in [values] (defaults already applied). */
    fun missingRequired(settings: List<PluginSetting>, values: Map<String, Any?>): List<PluginSetting> =
        settings.filter { it.required && (values[it.key] as? String).isNullOrBlank() }
}

/**
 * The person's own servers: a value typed into a `url` setting becomes an allowed host for that
 * plugin, exactly as typed (scheme + host + port). Loopback, link-local, unspecified and
 * `localhost` are always refused: Kino's own local proxies listen on loopback, and a plugin must
 * not be able to talk a person into pointing it at them. Private LAN ranges are allowed — the
 * person's Jellyfin/NAS is the point, and typing the address is the consent.
 */
data class UserHost(val scheme: String, val host: String, val port: Int) {
    fun matches(url: okhttp3.HttpUrl): Boolean = url.scheme == scheme && url.host == host && url.port == port

    /** How it's shown in Ajustes ▸ Plugins ("Se conectará a: …"). */
    val label: String get() = "$scheme://" + (if (':' in host) "[$host]" else host) + ":$port"
}

/**
 * What a plugin may reach right now: the hosts the person approved at install ([declared], https
 * only, public names only) plus the servers typed in its `url` settings ([user]). Every component
 * that gates plugin traffic reads this one object (see [PluginHosts.effective]).
 */
data class EffectiveHosts(val declared: List<String>, val user: List<UserHost> = emptyList()) {
    fun userHostFor(url: okhttp3.HttpUrl): UserHost? = user.firstOrNull { it.matches(url) }

    /** A user host's NAME (not an IP literal): the DNS gate lets it resolve into the LAN. */
    val userHostNames: Set<String> get() = user.map { it.host }.filterNot { PluginHosts.isIpLiteral(it) }.toSet()

    /** "Se conectará a: …": declared hosts, then the configured servers. */
    val labels: List<String> get() = declared + user.map { it.label }
}

object PluginHosts {
    /**
     * [declared] from the installed record (what the person approved), plus a [UserHost] for every
     * `url` setting that has a usable value in [config].
     */
    fun effective(declared: List<String>, settings: List<PluginSetting>, config: Map<String, Any?>): EffectiveHosts =
        EffectiveHosts(
            declared,
            settings.filter { it.type == SettingType.URL }
                .mapNotNull { (config[it.key] as? String)?.let(::userHostOf) }
                .distinct(),
        )

    /** Parses a typed server address; null if it isn't one or points at the device itself. */
    fun userHostOf(value: String): UserHost? {
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty() || isForbiddenUserHost(url.host)) return null
        return UserHost(url.scheme, url.host, url.port)
    }

    /** Loopback, link-local, unspecified, `localhost` (and `*.localhost`): never a user host. */
    fun isForbiddenUserHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (!isIpLiteral(h)) return false
        val bytes = runCatching { java.net.InetAddress.getByName(h).address }.getOrNull() ?: return true
        val a = java.net.InetAddress.getByAddress(bytes)
        return a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || isV4MappedForbidden(bytes)
    }

    /** `::ffff:127.0.0.1` and friends: an IPv4-mapped address inherits the IPv4 rule. */
    private fun isV4MappedForbidden(b: ByteArray): Boolean {
        if (b.size != 16) return false
        for (i in 0 until 10) if (b[i].toInt() != 0) return false
        if (b[10].toInt() and 0xFF != 0xFF || b[11].toInt() and 0xFF != 0xFF) return false
        val v4 = java.net.InetAddress.getByAddress(b.copyOfRange(12, 16))
        return v4.isLoopbackAddress || v4.isLinkLocalAddress || v4.isAnyLocalAddress
    }

    /** OkHttp keeps IPv6 hosts without brackets; an IPv4 literal is four dotted numbers. */
    fun isIpLiteral(host: String): Boolean =
        ':' in host || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
}
