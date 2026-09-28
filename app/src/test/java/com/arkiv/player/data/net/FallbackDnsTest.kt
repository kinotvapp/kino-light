package com.arkiv.player.data.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class FallbackDnsTest {
    private fun dnsOf(vararg ips: String): Dns = object : Dns {
        override fun lookup(hostname: String) = ips.map { InetAddress.getByName(it) }
    }

    private fun failingDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
    }

    @Test
    fun `returns primary's answer when primary resolves`() {
        val primary = dnsOf("1.1.1.1")
        val fallback = dnsOf("9.9.9.9")

        val result = FallbackDns(primary, fallback).lookup("example.com")

        assertEquals(listOf(InetAddress.getByName("1.1.1.1")), result)
    }

    @Test
    fun `falls back when primary can't resolve`() {
        val fallback = dnsOf("9.9.9.9")

        val result = FallbackDns(failingDns(), fallback).lookup("example.com")

        assertEquals(listOf(InetAddress.getByName("9.9.9.9")), result)
    }

    @Test
    fun `propagates the failure when both primary and fallback can't resolve`() {
        assertThrows(UnknownHostException::class.java) {
            FallbackDns(failingDns(), failingDns()).lookup("example.com")
        }
    }
}
