package com.arkiv.player.playback

import android.content.Context
import android.net.wifi.WifiManager

/**
 * The device's IP on the LAN, for local HTTP servers that a renderer (TV/Chromecast/DLNA) needs
 * to reach (the live channel proxy, transcoded cast).
 *
 * Used to live as `TorrentEngine.lanIp()` (torrent was removed in this branch's pruning); it's a
 * generic helper that never depended on anything torrent-specific, so it carried over as-is.
 * Wi-Fi first (the interface that usually shares a LAN with the renderer); if there is none (e.g.
 * a wired Fire TV), it scans the interfaces for a site-local IPv4.
 */
object LanIp {
    fun current(context: Context): String? = wifiIp(context) ?: siteLocalIp()

    @Suppress("DEPRECATION")
    private fun wifiIp(context: Context): String? {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    }

    /** Private IPv4 of an active interface (fallback for [wifiIp]); discards loopback/virtual, VPN
     *  (tun) and mobile data (rmnet) interfaces so it doesn't announce an IP unreachable from the
     *  renderer. */
    private fun siteLocalIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { val n = it.name.orEmpty(); n.startsWith("tun") || n.startsWith("rmnet") || n.startsWith("ap") }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.address } }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
