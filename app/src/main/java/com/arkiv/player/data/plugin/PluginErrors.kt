package com.arkiv.player.data.plugin

/**
 * Typed plugin errors: the codes a plugin may throw with `kino.error(code, message)` and what the
 * person reads for each (spec §3.6). The plugin's own message is only a plain-text detail.
 */
object PluginErrors {
    const val MAX_MESSAGE_CHARS = 200
    const val AUTH_REQUIRED = "auth_required"
    const val NOT_FOUND = "not_found"
    const val GEO_BLOCKED = "geo_blocked"
    const val RATE_LIMITED = "rate_limited"
    const val UNAVAILABLE = "unavailable"
    val CODES = listOf(AUTH_REQUIRED, NOT_FOUND, GEO_BLOCKED, RATE_LIMITED, UNAVAILABLE)

    /**
     * The engine formats an uncaught error as "<name>: <message>\n<stack>". The prelude names a
     * typed error `KinoError_<code>`, so the code survives even a throw before an async
     * function's first await, which never reaches `__kinoCall` (quickjs-kt alpha13). The name must
     * be a valid JNI class name: quickjs-kt passes it to `FindClass`, and CheckJNI (every debug
     * build) aborts the whole app on one with `[` or a space (measured on emulator-5554). Only the
     * first 2 KB of [message] are looked at: it can be as long as whatever the plugin threw.
     */
    private val ENGINE = Regex("^KinoError_([a-z_]{1,32}): ([^\\n]*)")

    fun fromEngineMessage(message: String?): PluginErrorException? {
        val head = message?.take(PluginRuntime.MAX_ERROR_CHARS + 64) ?: return null
        val m = ENGINE.find(head) ?: return null
        return PluginErrorException(m.groupValues[1], m.groupValues[2].take(MAX_MESSAGE_CHARS))
    }

    /** What the person reads for [code] from the plugin called [pluginName]; null = the generic handling. */
    fun userMessage(code: String, pluginName: String): String? = when (code) {
        AUTH_REQUIRED -> "Configura $pluginName en Ajustes ▸ Plugins"
        NOT_FOUND -> "No se encontró en $pluginName"
        GEO_BLOCKED -> "Este contenido no está disponible en tu región"
        RATE_LIMITED -> "$pluginName está limitando las peticiones; intenta en unos minutos"
        UNAVAILABLE -> "$pluginName no está disponible ahora"
        else -> null
    }
}
