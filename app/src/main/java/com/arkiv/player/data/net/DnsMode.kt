package com.arkiv.player.data.net

/** Which resolver the app asks for a host's address (Ajustes): the person's choice, Cloudflare unless they change it. */
enum class DnsMode(val key: String, val label: String) {
    /** DNS-over-HTTPS to Cloudflare. What the app has always used. */
    CLOUDFLARE("cloudflare", "Cloudflare (predeterminado)"),
    GOOGLE("google", "Google"),

    /**
     * No secure DNS at all: hosts are resolved by the device's own DNS, with no DoH request. For someone who does not
     * want the app to apply any DNS of its own, or on a network where the DoH resolvers themselves are blocked.
     */
    SYSTEM("system", "Ninguno (el del dispositivo)"),
    ;

    /** The next option, for a single row that cycles through all of them. */
    fun next(): DnsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): DnsMode = entries.firstOrNull { it.key == key } ?: CLOUDFLARE
    }
}
