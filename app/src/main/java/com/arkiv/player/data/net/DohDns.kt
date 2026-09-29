package com.arkiv.player.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * Resolves via DNS-over-HTTPS to the resolver [mode] names (Cloudflare unless the person picked another in Ajustes),
 * falling back to the device's own DNS only when that resolver itself is unreachable. Measured in Chile: an ISP's
 * resolver can sinkhole a host (Xuper's, among others) while the host itself is fine -- DoH asks the resolver directly
 * over HTTPS to its literal IP (`1.1.1.1`, `8.8.8.8`), so the ISP's resolver is never in the path to lie.
 *
 * [mode] is set from the saved setting when [com.arkiv.player.data.SettingsStore] is created, at app start.
 */
object DohDns : Dns {
    @Volatile
    var mode: DnsMode = DnsMode.CLOUDFLARE

    private fun overHttps(resolverIp: String): Dns = FallbackDns(
        primary = DnsOverHttps.Builder()
            .client(OkHttpClient())
            .url("https://$resolverIp/dns-query".toHttpUrl())
            .build(),
        fallback = Dns.SYSTEM,
    )

    private val delegate = ModalDns(
        cloudflare = overHttps("1.1.1.1"),
        google = overHttps("8.8.8.8"),
        system = Dns.SYSTEM,
    ) { mode }

    override fun lookup(hostname: String): List<java.net.InetAddress> = delegate.lookup(hostname)
}
