package com.arkiv.player.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * Resolves via Cloudflare's DNS-over-HTTPS, falling back to the device's own DNS only when
 * Cloudflare itself is unreachable. Measured in Chile: an ISP's resolver can sinkhole a host
 * (Xuper's, among others) while the host itself is fine -- DoH asks Cloudflare directly over
 * HTTPS to the literal IP `1.1.1.1`, so that resolver is never in the path to lie.
 */
object DohDns : Dns by FallbackDns(
    primary = DnsOverHttps.Builder()
        .client(OkHttpClient())
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .build(),
    fallback = Dns.SYSTEM,
)
