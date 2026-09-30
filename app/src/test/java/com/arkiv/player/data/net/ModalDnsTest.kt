package com.arkiv.player.data.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class ModalDnsTest {
    private fun dnsOf(ip: String): Dns = object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName(ip))
    }

    private var mode = DnsMode.CLOUDFLARE
    private val dns = ModalDns(dnsOf("1.1.1.1"), dnsOf("8.8.8.8"), dnsOf("9.9.9.9")) { mode }

    private fun answer() = dns.lookup("cdn.example").single().hostAddress

    @Test
    fun `each mode asks its own resolver`() {
        mode = DnsMode.CLOUDFLARE
        assertEquals("1.1.1.1", answer())
        mode = DnsMode.GOOGLE
        assertEquals("8.8.8.8", answer())
        mode = DnsMode.SYSTEM
        assertEquals("9.9.9.9", answer())
    }

    @Test
    fun `the mode is read on every lookup, so a change applies without a restart`() {
        mode = DnsMode.GOOGLE
        assertEquals("8.8.8.8", answer())
        mode = DnsMode.CLOUDFLARE
        assertEquals("1.1.1.1", answer())
    }

    @Test
    fun `the modes cycle Cloudflare, Google, system and back`() {
        assertEquals(DnsMode.GOOGLE, DnsMode.CLOUDFLARE.next())
        assertEquals(DnsMode.SYSTEM, DnsMode.GOOGLE.next())
        assertEquals(DnsMode.CLOUDFLARE, DnsMode.SYSTEM.next())
    }

    @Test
    fun `an unknown or missing saved key falls back to Cloudflare`() {
        assertEquals(DnsMode.CLOUDFLARE, DnsMode.fromKey(null))
        assertEquals(DnsMode.CLOUDFLARE, DnsMode.fromKey("quad9"))
        assertEquals(DnsMode.GOOGLE, DnsMode.fromKey("google"))
        assertEquals(DnsMode.SYSTEM, DnsMode.fromKey("system"))
    }

    @Test
    fun `the option that applies no DNS is named Ninguno`() {
        assertEquals("Ninguno (el del dispositivo)", DnsMode.SYSTEM.label)
        assertEquals("system", DnsMode.SYSTEM.key)
    }
}
