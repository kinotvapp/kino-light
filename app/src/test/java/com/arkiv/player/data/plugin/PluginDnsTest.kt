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
}
