package com.arkiv.player.data.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Tries [primary] first, falling back to [fallback] only when [primary] itself can't be reached
 * (its own [UnknownHostException]) -- never masks a genuinely nonexistent host, since both
 * delegates throw the same exception type for that.
 */
class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        try {
            primary.lookup(hostname)
        } catch (e: UnknownHostException) {
            fallback.lookup(hostname)
        }
}
