package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.HostRules
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface OwnUrlCheck {
    /** [url] is the canonical text to store (trimmed, host lower-cased); [cleartext] = it is `http://`. */
    data class Ok(val url: String, val cleartext: Boolean) : OwnUrlCheck
    data class Refused(val message: String) : OwnUrlCheck
}

/**
 * The rules for a source the person types themselves. Pure. The host rule is the one plugins use for
 * `liveStreamHosts: "any"` ([HostRules]): a public name or a public IPv4 literal, never the home
 * network, this device or a reserved range. The connect-time twin (a public NAME that resolves into
 * the LAN) is `PluginDns`.
 */
object OwnSourceValidator {
    const val MAX_URL = 2048
    const val MAX_NAME = 80
    const val MAX_HEADER = 200

    fun checkUrl(raw: String): OwnUrlCheck {
        val text = raw.trim()
        if (text.isEmpty()) return OwnUrlCheck.Refused("Escribe la dirección")
        if (text.length > MAX_URL) return OwnUrlCheck.Refused("La dirección es demasiado larga")
        val url = text.toHttpUrlOrNull()
            ?: return OwnUrlCheck.Refused("La dirección no es válida: debe empezar por http:// o https://")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return OwnUrlCheck.Refused("La dirección no puede llevar usuario y clave incrustados antes del servidor")
        }
        val host = url.host
        if (!HostRules.isPublicIpv4Literal(host) && HostRules.isLocalAddress(host)) {
            return OwnUrlCheck.Refused("Esa dirección apunta a tu red local o a este aparato, y no se puede usar")
        }
        return OwnUrlCheck.Ok(url.toString(), url.scheme == "http")
    }

    fun checkName(raw: String): String? = when {
        raw.isBlank() -> "Ponle un nombre"
        raw.trim().length > MAX_NAME -> "El nombre es demasiado largo (máximo $MAX_NAME letras)"
        else -> null
    }

    /** A value that becomes a request header: printable ASCII only, so it can't smuggle a second header. */
    fun checkHeaderValue(raw: String): String? = when {
        raw.isEmpty() -> null
        raw.length > MAX_HEADER -> "Es demasiado largo (máximo $MAX_HEADER letras)"
        raw.any { it.code < 0x20 || it.code > 0x7E } -> "Solo letras, números y símbolos comunes, en una sola línea"
        else -> null
    }

    /** A logo is display only, so http is fine (same rule as a plugin's images: `PluginOutput.imageUrl`). */
    fun checkLogo(raw: String): String? {
        if (raw.isBlank()) return null
        return (checkUrl(raw) as? OwnUrlCheck.Refused)?.message
    }

    fun isDuplicate(url: String, existing: Collection<String>): Boolean {
        val canonical = (checkUrl(url) as? OwnUrlCheck.Ok)?.url ?: return false
        return existing.any { (checkUrl(it) as? OwnUrlCheck.Ok)?.url == canonical }
    }
}
