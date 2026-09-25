package com.arkiv.player.data.plugin

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PluginDnsTest {
    private fun ip(vararg b: Int) = InetAddress.getByAddress("h", ByteArray(b.size) { b[it].toByte() })
    private fun dns(vararg answers: InetAddress, loopback: Boolean = false) =
        PluginDns(allowLoopback = loopback, delegate = object : Dns { override fun lookup(hostname: String) = answers.toList() })

    @Test fun `public addresses pass`() = assertEquals(listOf(ip(93, 184, 216, 34)), dns(ip(93, 184, 216, 34)).lookup("x"))

    @Test fun `private, loopback and link-local answers are refused`() {
        listOf(ip(192, 168, 1, 1), ip(10, 0, 0, 5), ip(172, 16, 0, 1), ip(127, 0, 0, 1), ip(169, 254, 1, 1), ip(0, 0, 0, 0))
            .forEach { a -> assertThrows(UnknownHostException::class.java) { dns(a).lookup("x") } }
    }

    @Test fun `mixed answers keep only the public ones`() =
        assertEquals(listOf(ip(8, 8, 8, 8)), dns(ip(192, 168, 1, 1), ip(8, 8, 8, 8)).lookup("x"))

    @Test fun `loopback only when the test flag is on`() =
        assertEquals(listOf(ip(127, 0, 0, 1)), dns(ip(127, 0, 0, 1), loopback = true).lookup("localhost"))

    @Test fun `CGNAT, this-network, multicast and NAT64 answers are refused`() {
        listOf(
            ip(100, 64, 0, 1), ip(100, 127, 255, 254), ip(0, 1, 2, 3), ip(224, 0, 0, 251), ip(239, 255, 255, 250),
            v6("ff02::1"), v6("64:ff9b::c0a8:101"), v6("fd00::1"), v6("fe80::1"), v6("::1"),
        ).forEach { a -> assertThrows(a.toString(), UnknownHostException::class.java) { dns(a).lookup("x") } }
    }

    @Test fun `reserved 240 slash 4, broadcast, 6to4 and Teredo answers are refused`() {
        listOf(ip(240, 0, 0, 1), ip(250, 1, 2, 3), ip(255, 255, 255, 255), v6("2002:c0a8:101::1"), v6("2001:0:4136:e378::1"))
            .forEach { a -> assertThrows(a.toString(), UnknownHostException::class.java) { dns(a).lookup("x") } }
    }

    @Test fun `the edges of 240 slash 4, 6to4 and Teredo stay public`() {
        listOf(ip(223, 255, 255, 254), v6("2003::1"), v6("2001:1::1"), v6("2001:db9::1"))
            .forEach { a -> assertEquals(a.toString(), listOf(a), dns(a).lookup("x")) }
    }

    @Test fun `the edges of CGNAT and NAT64 stay public`() {
        listOf(ip(100, 63, 255, 255), ip(100, 128, 0, 1), v6("64:ff9c::1"), v6("2606:4700::1111"))
            .forEach { a -> assertEquals(a.toString(), listOf(a), dns(a).lookup("x")) }
    }

    private fun v6(text: String) = InetAddress.getByName(text)
}
