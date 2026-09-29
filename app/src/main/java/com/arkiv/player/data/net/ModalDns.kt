package com.arkiv.player.data.net

import okhttp3.Dns
import java.net.InetAddress

/** Answers with the resolver [mode] names, read on every lookup so a change in Ajustes applies without a restart. */
class ModalDns(
    private val cloudflare: Dns,
    private val google: Dns,
    private val system: Dns,
    private val mode: () -> DnsMode,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = when (mode()) {
        DnsMode.CLOUDFLARE -> cloudflare
        DnsMode.GOOGLE -> google
        DnsMode.SYSTEM -> system
    }.lookup(hostname)
}
