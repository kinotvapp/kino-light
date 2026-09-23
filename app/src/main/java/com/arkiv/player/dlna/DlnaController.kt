package com.arkiv.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/** A DLNA device (MediaRenderer) discovered on the network. */
data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String,
)

/**
 * Minimal DLNA/UPnP client: discovers MediaRenderers over SSDP and controls them via
 * SOAP (AVTransport). Used to cast video to TVs without Chromecast (LG webOS,
 * etc.) that do speak DLNA.
 */
class DlnaController(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private val proxy = DlnaProxyServer()

    /** The phone's IP on the WiFi (so the TV can request the stream from the proxy). */
    @Suppress("DEPRECATION")
    private fun wifiIp(): String? {
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    }

    /** Discovers MediaRenderers over SSDP (blocks for ~timeoutMs). */
    fun discover(timeoutMs: Long = 3500): List<DlnaDevice> {
        val lock = wifi.createMulticastLock("arkiv-dlna").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val locations = LinkedHashSet<String>()
        try {
            val socket = DatagramSocket().apply { soTimeout = 900; broadcast = true }
            val group = InetAddress.getByName("239.255.255.250")
            // Searching for everything (ssdp:all) is more robust: some TVs don't respond to the
            // specific MediaRenderer ST. We filter by AVTransport afterwards.
            for (st in listOf("ssdp:all", "urn:schemas-upnp-org:service:AVTransport:1")) {
                val msearch = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n"
                    ).toByteArray()
                repeat(2) { runCatching { socket.send(DatagramPacket(msearch, msearch.size, group, 1900)) } }
            }

            var responses = 0
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    responses++
                    val resp = String(packet.data, 0, packet.length)
                    resp.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.let { line -> line.substring(line.indexOf(':') + 1).trim() }
                        ?.let { locations.add(it) }
                } catch (_: SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
            socket.close()
            Log.i("ArkivDlna", "SSDP: $responses responses, ${locations.size} locations: $locations")
        } catch (_: Exception) {
            // no network / socket error
        } finally {
            runCatching { lock.release() }
        }
        val devices = locations.mapNotNull { parseDevice(it) }.distinctBy { it.controlUrl }
        Log.i("ArkivDlna", "Devices with AVTransport: ${devices.map { it.friendlyName }}")
        return devices
    }

    /** Downloads and parses the device description; extracts name + AVTransport controlURL. */
    private fun parseDevice(location: String): DlnaDevice? {
        val xml = runCatching {
            client.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() }
        }.getOrNull()
        if (xml == null) {
            Log.w("ArkivDlna", "parseDevice: fetch failed for $location")
            return null
        }
        Log.i("ArkivDlna", "parseDevice: $location -> ${xml.length} bytes, AVT=${xml.contains("AVTransport")}")

        var friendlyName = "TV"
        var controlUrl: String? = null
        var urlBase: String? = null
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var curServiceType: String? = null
            var curControlUrl: String? = null
            var inService = false
            var tag = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag.equals("service", true)) {
                            inService = true; curServiceType = null; curControlUrl = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) when {
                            tag.equals("friendlyName", true) && friendlyName == "TV" -> friendlyName = text
                            tag.equals("URLBase", true) -> urlBase = text
                            inService && tag.equals("serviceType", true) -> curServiceType = text
                            inService && tag.equals("controlURL", true) -> curControlUrl = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("service", true)) {
                            if (curServiceType?.contains("AVTransport", true) == true && curControlUrl != null) {
                                controlUrl = curControlUrl
                            }
                            inService = false
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            return null
        }

        val ctrl = controlUrl
        if (ctrl == null) {
            Log.w("ArkivDlna", "parseDevice: no AVTransport controlURL on $location (name=$friendlyName)")
            return null
        }
        val base = urlBase ?: location
        val resolved = runCatching { URI(base).resolve(ctrl).toString() }.getOrNull() ?: return null
        Log.i("ArkivDlna", "parseDevice: OK $friendlyName -> $resolved")
        return DlnaDevice(friendlyName = friendlyName, controlUrl = resolved)
    }

    /**
     * Casts to the TV through the local proxy: the TV gets an http URL from the phone (compatible
     * with its renderer) and the phone downloads the actual media behind it -- today that's Magis
     * or Caracol; the parameter kept the `archiveUrl` name from when archive.org was the only
     * source that needed this detour (the renderer can't take its direct URL). Returns true if it
     * could start (there's a WiFi IP).
     */
    fun setUrlAndPlay(device: DlnaDevice, archiveUrl: String, title: String): Boolean {
        val ip = wifiIp() ?: return false
        proxy.setTarget(archiveUrl)
        val port = proxy.ensureStarted()
        val localUrl = "http://$ip:$port/stream.mp4"
        val didl = xmlEscape(didlLite(localUrl, title))
        soap(
            device.controlUrl, "SetAVTransportURI",
            "<u:SetAVTransportURI xmlns:u=\"$AVT\"><InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEscape(localUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>$didl</CurrentURIMetaData></u:SetAVTransportURI>",
        )
        play(device)
        return true
    }

    /** Plays a raw URL already reachable over LAN (today, the live channel's HLS proxy). */
    fun playRawUrl(device: DlnaDevice, url: String, title: String, mime: String = "video/mp4"): Boolean {
        val didl = xmlEscape(didlLiteFor(url, title, mime))
        soap(
            device.controlUrl, "SetAVTransportURI",
            "<u:SetAVTransportURI xmlns:u=\"$AVT\"><InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEscape(url)}</CurrentURI>" +
                "<CurrentURIMetaData>$didl</CurrentURIMetaData></u:SetAVTransportURI>",
        )
        play(device)
        return true
    }

    fun play(device: DlnaDevice) = soap(
        device.controlUrl, "Play",
        "<u:Play xmlns:u=\"$AVT\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>",
    )

    fun pause(device: DlnaDevice) = soap(
        device.controlUrl, "Pause",
        "<u:Pause xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Pause>",
    )

    fun stop(device: DlnaDevice) {
        soap(device.controlUrl, "Stop", "<u:Stop xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Stop>")
        proxy.stop()
    }

    private fun soap(controlUrl: String, action: String, innerBody: String) {
        val envelope =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>$innerBody</s:Body></s:Envelope>"
        val req = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPACTION", "\"$AVT#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        runCatching { client.newCall(req).execute().use { it.body?.string() } }
    }

    private fun didlLite(url: String, title: String): String = didlLiteFor(url, title, "video/mp4")

    private fun didlLiteFor(url: String, title: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:" +
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">" +
            "${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
