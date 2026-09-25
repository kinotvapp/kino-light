package com.arkiv.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.arkiv.player.crash.Crash
import com.arkiv.player.crash.DlnaFailure
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A DLNA device (MediaRenderer) discovered on the network. */
data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String,
    /** From the device description; kept for diagnostics, since "the TV rejects it" depends on the TV. */
    val manufacturer: String = "",
    val model: String = "",
    /** ConnectionManager's control URL, to ask what the renderer can play (`GetProtocolInfo`). */
    val connectionManagerUrl: String? = null,
)

/**
 * Minimal DLNA/UPnP client: discovers MediaRenderers over SSDP and controls them via
 * SOAP (AVTransport). Used to cast video to TVs without Chromecast (LG webOS,
 * etc.) that do speak DLNA.
 *
 * INSTRUMENTED end to end for debugging real TVs (`adb logcat -s ArkivDlna`, see [DlnaLog]): what
 * discovery heard, what each device declares, every SOAP call with its HTTP code and UPnP fault, what the
 * TV then requests from our servers, and, since a renderer can accept `Play` and reject the media
 * afterwards, its transport state polled until the cast ends. A failure is reported once per cast as
 * [DlnaFailure] with the stage that broke.
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
    private val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    private val TRANSPORT_INFO_BODY = "<u:GetTransportInfo xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:GetTransportInfo>"
    private val proxy = DlnaProxyServer()

    private companion object {
        /** UPnP AVTransport error 701: the renderer can't make that transition right now. */
        const val TRANSITION_NOT_AVAILABLE = 701
        const val RETRIES_ON_701 = 2
        val IDLE_STATES = setOf("STOPPED", "NO_MEDIA_PRESENT")

        /** A silent TV ends the cast after this many polls (every 10 s once past the first minute ≈ 2 min). */
        const val UNREACHABLE_POLLS_TO_END = 12
        const val IDLE_TO_END_MS = 30_000L

        /** Nothing legitimate is casting for longer than this; a leaked cast must not hold the foreground service forever. */
        const val MAX_CAST_MS = 8L * 60 * 60 * 1000
    }

    /**
     * Why the last cast failed, in Spanish, for the UI to show instead of a generic "check your WiFi".
     * Null when the last attempt went fine.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** Everything the monitor and the failure report need to know about the cast in progress. */
    private class Cast(val id: String, val device: DlnaDevice, val kind: String, val mime: String) {
        @Volatile var playAtMs = 0L
        @Volatile var userPaused = false
        @Volatile var reported = false
        @Volatile var sinkMimes: List<String> = emptyList()
        @Volatile var lastState: String? = null
        @Volatile var lastStatus: String? = null
        @Volatile var lastPositionMs: Long? = null
        @Volatile var positionSince = 0L
        @Volatile var polls = 0
        @Volatile var unreachablePolls = 0
        @Volatile var wasPlaying = false
        @Volatile var idleSince = 0L
    }

    @Volatile
    private var cast: Cast? = null
    private val monitorExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dlna-monitor").apply { isDaemon = true }
    }
    private var monitorTask: ScheduledFuture<*>? = null

    /** The phone's IP on the WiFi (so the TV can request the stream from the proxy). */
    @Suppress("DEPRECATION")
    private fun wifiIp(): String? {
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
    }

    // ---------------------------------------------------------------- discovery

    /** Discovers MediaRenderers over SSDP (blocks for ~timeoutMs). */
    @Suppress("DEPRECATION")
    fun discover(timeoutMs: Long = 3500): List<DlnaDevice> {
        val startedAt = SystemClock.elapsedRealtime()
        DlnaLog.i(
            "discover: start · wifiEnabled=${wifi.isWifiEnabled} phoneIp=${wifiIp() ?: "NONE (not on WiFi?)"} timeout=${timeoutMs}ms",
        )
        val lock = wifi.createMulticastLock("arkiv-dlna").apply {
            setReferenceCounted(false)
            runCatching { acquire() }.onFailure { DlnaLog.w("discover: multicast lock NOT acquired: ${it.message}") }
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
                repeat(2) {
                    runCatching { socket.send(DatagramPacket(msearch, msearch.size, group, 1900)) }
                        .onFailure { DlnaLog.w("discover: M-SEARCH send failed (ST=$st): ${it.javaClass.simpleName}: ${it.message}") }
                }
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
                    val location = resp.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.let { line -> line.substring(line.indexOf(':') + 1).trim() }
                    // The first reply of each device description, so "the TV didn't show up" can be told from "it
                    // answered but we dropped it". A TV answers the same LOCATION dozens of times (one per service
                    // and per M-SEARCH: 109 replies for 6 devices on a real LG), so only new ones are logged.
                    val isNew = location == null || locations.add(location)
                    if (isNew) {
                        DlnaLog.i(
                            "discover: SSDP reply from ${packet.address?.hostAddress} " +
                                "ST=${ssdpHeader(resp, "ST")} SERVER=${ssdpHeader(resp, "SERVER")?.take(60)} " +
                                "LOCATION=${location ?: "(none)"}",
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
            socket.close()
            DlnaLog.i("discover: SSDP done · $responses replies, ${locations.size} unique locations")
            if (responses == 0) {
                DlnaLog.w(
                    "discover: ZERO replies. Multicast is blocked (router 'AP/client isolation'), the phone isn't on the " +
                        "TV's network, or the TV has DLNA off",
                )
            }
        } catch (e: Exception) {
            DlnaLog.w("discover: socket error: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            runCatching { lock.release() }
        }
        val devices = locations.mapNotNull { parseDevice(it) }.distinctBy { it.controlUrl }
        DlnaLog.i(
            "discover: ${devices.size} renderer(s) with AVTransport ${devices.map { "'${it.friendlyName}'" }} " +
                "in ${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        return devices
    }

    private fun ssdpHeader(response: String, name: String): String? =
        response.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    /** Downloads and parses the device description; extracts name + AVTransport controlURL. */
    private fun parseDevice(location: String): DlnaDevice? {
        val startedAt = SystemClock.elapsedRealtime()
        val xml = try {
            client.newCall(Request.Builder().url(location).build()).execute().use { resp ->
                if (!resp.isSuccessful) DlnaLog.w("parseDevice: HTTP ${resp.code} for ${DlnaXml.safeUrl(location)}")
                resp.body?.string()
            }
        } catch (e: Exception) {
            DlnaLog.w("parseDevice: fetch failed for ${DlnaXml.safeUrl(location)}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (xml == null) return null
        DlnaLog.i(
            "parseDevice: ${DlnaXml.safeUrl(location)} -> ${xml.length} bytes in ${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "AVT=${xml.contains("AVTransport")}",
        )

        var friendlyName = "TV"
        var manufacturer = ""
        var model = ""
        var deviceType = ""
        var controlUrl: String? = null
        var connectionManagerUrl: String? = null
        var urlBase: String? = null
        val services = mutableListOf<String>()
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
                            tag.equals("manufacturer", true) && manufacturer.isEmpty() -> manufacturer = text
                            tag.equals("modelName", true) && model.isEmpty() -> model = text
                            tag.equals("deviceType", true) && deviceType.isEmpty() -> deviceType = text
                            tag.equals("URLBase", true) -> urlBase = text
                            inService && tag.equals("serviceType", true) -> curServiceType = text
                            inService && tag.equals("controlURL", true) -> curControlUrl = text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("service", true)) {
                            curServiceType?.let { services += it.substringAfter("service:").substringBefore(':') }
                            if (curServiceType?.contains("AVTransport", true) == true && curControlUrl != null) {
                                controlUrl = curControlUrl
                            }
                            if (curServiceType?.contains("ConnectionManager", true) == true && curControlUrl != null) {
                                connectionManagerUrl = curControlUrl
                            }
                            inService = false
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            DlnaLog.w("parseDevice: XML parse failed for ${DlnaXml.safeUrl(location)}: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }

        DlnaLog.i(
            "parseDevice: '$friendlyName' manufacturer='$manufacturer' model='$model' type=$deviceType services=$services",
        )
        val ctrl = controlUrl
        if (ctrl == null) {
            DlnaLog.w("parseDevice: no AVTransport controlURL on ${DlnaXml.safeUrl(location)} (name=$friendlyName): not a renderer")
            return null
        }
        val base = urlBase ?: location
        val resolved = runCatching { URI(base).resolve(ctrl).toString() }.getOrNull()
        if (resolved == null) {
            DlnaLog.w("parseDevice: could not resolve controlURL '$ctrl' against '${DlnaXml.safeUrl(base)}'")
            return null
        }
        val cmResolved = connectionManagerUrl?.let { runCatching { URI(base).resolve(it).toString() }.getOrNull() }
        DlnaLog.i("parseDevice: OK '$friendlyName' -> ${DlnaXml.safeUrl(resolved)}")
        return DlnaDevice(
            friendlyName = friendlyName,
            controlUrl = resolved,
            manufacturer = manufacturer,
            model = model,
            connectionManagerUrl = cmResolved,
        )
    }

    // ------------------------------------------------------------------- casting

    /**
     * Casts to the TV through the local proxy: the TV gets an http URL from the phone (compatible
     * with its renderer) and the phone downloads the actual media behind it -- today that's Magis
     * or Caracol; the parameter kept the `archiveUrl` name from when archive.org was the only
     * source that needed this detour (the renderer can't take its direct URL). Returns true if it
     * could start; on false, [lastError] says why.
     */
    fun setUrlAndPlay(device: DlnaDevice, archiveUrl: String, title: String): Boolean {
        val c = beginCast(device, kind = "vod-proxy", mime = "video/mp4", title = title, source = archiveUrl)
        // A file:// (a download) can't be proxied: OkHttp only speaks http(s), so the TV would get a
        // connection that closes with nothing, silently. Say so instead.
        if (!archiveUrl.startsWith("http", ignoreCase = true)) {
            return failPreflight(
                c, "source_not_http", "Este contenido no se puede enviar a la TV por DLNA (es un archivo local)",
                "source=${DlnaXml.safeUrl(archiveUrl)}",
            )
        }
        val ip = wifiIp() ?: return failPreflight(c, "no_wifi_ip", "No se detectó la red WiFi del teléfono", "wifiEnabled=${wifi.isWifiEnabled}")
        proxy.setTarget(archiveUrl)
        val port = proxy.ensureStarted()
        val localUrl = "http://$ip:$port/stream.mp4"
        DlnaLog.i("cast: proxy on $ip:$port serving ${DlnaXml.safeUrl(archiveUrl)} as $localUrl (declared ${c.mime})")
        return startPlayback(c, localUrl, title)
    }

    /** Plays a raw URL already reachable over LAN (today, the live channel's HLS proxy). */
    fun playRawUrl(device: DlnaDevice, url: String, title: String, mime: String = "video/mp4"): Boolean {
        val kind = if (mime.contains("mpegurl", ignoreCase = true)) "live-hls" else "raw-url"
        val c = beginCast(device, kind = kind, mime = mime, title = title, source = url)
        return startPlayback(c, url, title)
    }

    /**
     * For callers that find out BEFORE reaching the renderer that they can't send anything (no LAN URL
     * for the live proxy): the same log, [lastError] and report as any other failed cast.
     */
    fun failedBeforeSending(device: DlnaDevice, kind: String, stage: String, userMessage: String, detail: String) {
        val c = beginCast(device, kind = kind, mime = "-", title = "-", source = "")
        failPreflight(c, stage, userMessage, detail)
    }

    private fun beginCast(device: DlnaDevice, kind: String, mime: String, title: String, source: String): Cast {
        monitorTask?.cancel(false)
        val c = Cast(DlnaLog.newSession(), device, kind, mime)
        cast = c
        lastError = null
        DlnaLog.i(
            "cast start · kind=$kind device='${device.friendlyName}' (${device.manufacturer} ${device.model}) " +
                "title='${title.take(60)}' source=${DlnaXml.safeUrl(source)}",
        )
        return c
    }

    private fun startPlayback(c: Cast, url: String, title: String): Boolean {
        // What the renderer can play, asked in the background so it doesn't delay the cast: it answers
        // the question "did we declare a MIME it doesn't list?" long before the failure would.
        monitorExecutor.execute { logSinkProtocols(c) }

        // Keeps the phone's proxy and WiFi alive if the person leaves Kino. Without it Android cuts the app's
        // network ~5 s after it goes to the background (measured on a real Samsung: paused 21:43:52, the
        // TV's connection aborted 21:43:57, the TV unreachable from 21:44:04), the TV runs out of video
        // and reports it can't connect. Started before anything is sent: the TV pulls the media right away.
        DlnaCastService.start(appContext, c.device.friendlyName)

        // A TV left mid-transition by an earlier cast (a failed one, or one still playing) rejects a new
        // SetAVTransportURI with 701 "Transition not available": that was the whole reason a second attempt
        // failed on a real LG. Put it in a known state first.
        prepareRenderer(c)

        val didl = xmlEscape(didlLiteFor(url, title, c.mime))
        DlnaLog.i("cast: SetAVTransportURI · url=${DlnaXml.safeUrl(url)} mime=${c.mime}")
        val setUriBody = "<u:SetAVTransportURI xmlns:u=\"$AVT\"><InstanceID>0</InstanceID>" +
            "<CurrentURI>${xmlEscape(url)}</CurrentURI>" +
            "<CurrentURIMetaData>$didl</CurrentURIMetaData></u:SetAVTransportURI>"
        var setUri = soap(c.device.controlUrl, "SetAVTransportURI", setUriBody, readTimeoutSec = 20)
        var attempt = 0
        while (!setUri.ok && setUri.fault?.code == TRANSITION_NOT_AVAILABLE && attempt < RETRIES_ON_701) {
            attempt++
            DlnaLog.w("cast: SetAVTransportURI got 701 (transition not available): Stop, wait and retry $attempt/$RETRIES_ON_701")
            soap(c.device.controlUrl, "Stop", "<u:Stop xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Stop>")
            Thread.sleep(700L + 800L * attempt)
            setUri = soap(c.device.controlUrl, "SetAVTransportURI", setUriBody, readTimeoutSec = 20)
        }
        if (!setUri.ok) return failSoap(c, "set_uri", setUri)

        var play = playSoap(c.device)
        attempt = 0
        // Right after SetAVTransportURI some renderers still answer 701 to Play while they prepare the media.
        while (!play.ok && play.fault?.code == TRANSITION_NOT_AVAILABLE && attempt < RETRIES_ON_701) {
            attempt++
            DlnaLog.w("cast: Play got 701 (not ready yet): waiting and retrying $attempt/$RETRIES_ON_701")
            Thread.sleep(600L + 600L * attempt)
            play = playSoap(c.device)
        }
        if (!play.ok) return failSoap(c, "play", play)

        c.playAtMs = SystemClock.elapsedRealtime()
        DlnaLog.i("cast: Play accepted, watching the renderer's transport state")
        monitorTask = monitorExecutor.scheduleWithFixedDelay({ pollOnce(c) }, 1500, 2000, TimeUnit.MILLISECONDS)
        return true
    }

    /**
     * Leaves the renderer STOPPED before a new SetAVTransportURI. Reads its transport state; if it is doing
     * anything (a previous cast still playing, loading or half-failed) sends Stop and waits, up to ~3 s, for
     * it to settle. A renderer that doesn't answer is left alone: the cast itself will say what is wrong.
     */
    private fun prepareRenderer(c: Cast) {
        val before = DlnaXml.transportInfo(soap(c.device.controlUrl, "GetTransportInfo", TRANSPORT_INFO_BODY).body)?.state?.uppercase()
        DlnaLog.i("cast: renderer state before sending = ${before ?: "unknown"}")
        if (before == null || before in IDLE_STATES) return
        DlnaLog.i("cast: renderer is $before from an earlier cast: sending Stop first")
        soap(c.device.controlUrl, "Stop", "<u:Stop xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Stop>")
        repeat(10) {
            Thread.sleep(300)
            val now = DlnaXml.transportInfo(soap(c.device.controlUrl, "GetTransportInfo", TRANSPORT_INFO_BODY).body)?.state?.uppercase()
            if (now == null || now in IDLE_STATES) {
                DlnaLog.i("cast: renderer settled as ${now ?: "unknown"}")
                return
            }
        }
        DlnaLog.w("cast: renderer still not idle after Stop; sending anyway")
    }

    fun play(device: DlnaDevice): Boolean {
        cast?.userPaused = false
        return playSoap(device).ok
    }

    fun pause(device: DlnaDevice): Boolean {
        cast?.userPaused = true
        return soap(
            device.controlUrl, "Pause",
            "<u:Pause xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Pause>",
        ).ok
    }

    fun stop(device: DlnaDevice) {
        monitorTask?.cancel(false)
        soap(device.controlUrl, "Stop", "<u:Stop xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Stop>")
        DlnaLog.i("cast: stopped · TV made ${DlnaLog.lanHits.get()} request(s) to our servers, ${DlnaLog.lanBytes.get()} bytes")
        cast = null
        proxy.stop()
        DlnaCastService.stop(appContext)
    }

    /**
     * Stops the cast in progress, whoever asks (the notification's "Detener" runs on the main thread, where
     * a network call isn't allowed, so this hands it to the monitor's own thread).
     */
    fun stopActive() {
        val active = cast ?: return
        monitorExecutor.execute { stop(active.device) }
    }

    // An LG webOS answers `Play` only after it has fetched enough of the media to start; with the default 8 s
    // read timeout a slow-to-start stream was reported as "the TV didn't respond" while the TV was busy loading.
    private fun playSoap(device: DlnaDevice): SoapResult = soap(
        device.controlUrl, "Play",
        "<u:Play xmlns:u=\"$AVT\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>",
        readTimeoutSec = 25,
    )

    // -------------------------------------------------------------- SOAP + failure

    private class SoapResult(
        val ok: Boolean,
        val http: Int,
        val body: String?,
        val fault: UpnpFault?,
        /** The exception, when the request never got an HTTP answer (timeout, refused, DNS). */
        val error: String?,
    )

    /**
     * One SOAP call. Nothing is swallowed any more: the HTTP code, the UPnP fault and any exception are
     * logged and returned, because `runCatching { ... }` here used to make a rejected `SetAVTransportURI`
     * look exactly like a successful one.
     */
    private fun soap(
        controlUrl: String,
        action: String,
        innerBody: String,
        service: String = AVT,
        /** Longer read timeout for the calls a renderer answers only once it has prepared the media (`Play`). */
        readTimeoutSec: Long = 0L,
    ): SoapResult {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val envelope =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>$innerBody</s:Body></s:Envelope>"
            val req = Request.Builder()
                .url(controlUrl)
                .addHeader("SOAPACTION", "\"$service#$action\"")
                .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            val http = if (readTimeoutSec > 0) client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build() else client
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                val ms = SystemClock.elapsedRealtime() - startedAt
                val fault = if (resp.isSuccessful) null else DlnaXml.fault(body)
                if (resp.isSuccessful) {
                    DlnaLog.i("soap $action → ${resp.code} in ${ms}ms")
                } else {
                    DlnaLog.w(
                        "soap $action → HTTP ${resp.code} in ${ms}ms · upnp=${fault?.code} " +
                            "'${fault?.description ?: DlnaXml.describeError(fault?.code)}' · body=${body?.replace("\n", " ")?.take(300)}",
                    )
                }
                SoapResult(resp.isSuccessful, resp.code, body, fault, null)
            }
        } catch (e: Exception) {
            val ms = SystemClock.elapsedRealtime() - startedAt
            DlnaLog.w("soap $action → EXCEPTION ${e.javaClass.simpleName}: ${e.message} in ${ms}ms · ${DlnaXml.safeUrl(controlUrl)}")
            SoapResult(false, 0, null, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A SOAP step failed: log it, tell the person why, report once, and stop. Always returns false. */
    private fun failSoap(c: Cast, stage: String, r: SoapResult): Boolean {
        // Nothing is playing: the service that kept the proxy alive has nothing left to do.
        DlnaCastService.stop(appContext)
        val why = when {
            // The TV DID reach us (it asked our server for the media) and then went quiet: it isn't unreachable, it
            // is failing to play what it got. "Not responding" here sent the debugging in the wrong direction.
            r.error != null && DlnaLog.lanHits.get() > 0 ->
                "La TV pidió el video pero no logró reproducirlo (tardó demasiado en responder)"
            r.error != null -> "La TV no respondió (¿está encendida y en la misma red?)"
            r.fault?.code != null -> "La TV rechazó el video: ${DlnaXml.describeError(r.fault.code)} (código ${r.fault.code})"
            else -> "La TV rechazó el video (HTTP ${r.http})"
        }
        report(
            c, "dlna cast failed: $stage", why,
            "http_code" to r.http.toString(),
            "upnp_code" to (r.fault?.code?.toString() ?: ""),
            "upnp_desc" to (r.fault?.description ?: ""),
            "error" to (r.error ?: ""),
        )
        return false
    }

    private fun failPreflight(c: Cast, stage: String, userMessage: String, detail: String): Boolean {
        DlnaLog.e("cast: cannot start ($stage): $detail")
        report(c, "dlna cast failed: $stage", userMessage, "detail" to detail)
        return false
    }

    /** Sets [lastError], logs, and sends ONE [DlnaFailure] for this cast (a stalled TV would otherwise report every poll). */
    private fun report(c: Cast, message: String, userMessage: String, vararg extras: Pair<String, String>) {
        lastError = userMessage
        DlnaLog.e("cast FAILED · $message · $userMessage")
        if (c.reported) return
        c.reported = true
        val since = if (c.playAtMs > 0) SystemClock.elapsedRealtime() - c.playAtMs else 0L
        Crash.report(
            DlnaFailure(message),
            "dlna-failure",
            extras = buildMap {
                put("kind", c.kind)
                put("mime", c.mime)
                put("manufacturer", c.device.manufacturer)
                put("model", c.device.model)
                put("transport_state", c.lastState ?: "")
                put("transport_status", c.lastStatus ?: "")
                put("tv_requests", DlnaLog.lanHits.get().toString())
                put("bytes_served", DlnaLog.lanBytes.get().toString())
                put("since_play_ms", since.toString())
                put("sink_mimes", c.sinkMimes.take(12).joinToString(","))
                put("mime_listed", DlnaXml.isSupported(c.mime, c.sinkMimes).toString())
                extras.forEach { (k, v) -> put(k, v) }
            },
        )
    }

    // ------------------------------------------------------------------ monitor

    /** What the renderer says it can play; one line, and a warning when the MIME we declared isn't in it. */
    private fun logSinkProtocols(c: Cast) {
        val url = c.device.connectionManagerUrl
        if (url == null) {
            DlnaLog.i("renderer formats: unknown (no ConnectionManager service)")
            return
        }
        val r = soap(url, "GetProtocolInfo", "<u:GetProtocolInfo xmlns:u=\"$CM\"/>", CM)
        val mimes = DlnaXml.sinkMimes(r.body)
        c.sinkMimes = mimes
        if (mimes.isEmpty()) {
            DlnaLog.i("renderer formats: none listed (can't tell)")
        } else if (DlnaXml.isSupported(c.mime, mimes)) {
            DlnaLog.i("renderer formats: ${mimes.size} types, declared ${c.mime} IS listed: $mimes")
        } else {
            DlnaLog.w("renderer formats: declared ${c.mime} is NOT in what the TV lists: $mimes")
        }
    }

    /**
     * Asks the renderer how it's doing and logs every change. A renderer can answer 200 to `Play` and then
     * give up on the media, so this is where those failures are actually seen. Runs every 2 s for the first
     * minute and then every 10 s, up to ten minutes or until the cast is stopped.
     */
    private fun pollOnce(c: Cast) {
        if (cast !== c) {
            monitorTask?.cancel(false)
            return
        }
        c.polls++
        val sincePlay = SystemClock.elapsedRealtime() - c.playAtMs
        if (sincePlay > MAX_CAST_MS) {
            endCast(c, "safety limit of ${MAX_CAST_MS / 3_600_000L} h")
            return
        }
        // After the first minute, one poll in five: enough to catch a late freeze without hammering the TV.
        if (sincePlay > 60_000L && c.polls % 5 != 0) return

        val t = soap(c.device.controlUrl, "GetTransportInfo", TRANSPORT_INFO_BODY)
        val info = DlnaXml.transportInfo(t.body)
        val p = soap(c.device.controlUrl, "GetPositionInfo", "<u:GetPositionInfo xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:GetPositionInfo>")
        val pos = DlnaXml.positionInfo(p.body)

        if (info == null) {
            DlnaLog.w("monitor: no readable transport info (http=${t.http} error=${t.error})")
            // A TV that stays silent for ~2 minutes (turned off, left the network) ends the cast: without this the
            // foreground service that keeps the proxy alive would stay on, with its notification, for good.
            if (++c.unreachablePolls >= UNREACHABLE_POLLS_TO_END) endCast(c, "the TV stopped answering")
            return
        }
        c.unreachablePolls = 0
        if (info.state != c.lastState || info.status != c.lastStatus) {
            DlnaLog.i("transport ${c.lastState ?: "-"} → ${info.state} status=${info.status} (+${sincePlay / 1000}s, TV requests=${DlnaLog.lanHits.get()})")
        }
        c.lastState = info.state
        c.lastStatus = info.status

        // Position stall: only meaningful while PLAYING and only when the renderer reports a position at all.
        val now = SystemClock.elapsedRealtime()
        val position = pos?.relTimeMs
        if (position != null && position != c.lastPositionMs) c.positionSince = now
        if (c.positionSince == 0L) c.positionSince = now
        c.lastPositionMs = position
        val stalledMs = if (info.state.equals("PLAYING", true) && position != null) now - c.positionSince else 0L

        if (c.polls % 5 == 1) {
            DlnaLog.i("monitor: state=${info.state} position=${position?.div(1000)}s duration=${pos?.durationMs?.div(1000)}s stalled=${stalledMs}ms requests=${DlnaLog.lanHits.get()} bytes=${DlnaLog.lanBytes.get()}")
        }

        val stage = DlnaDiagnosis.failure(
            DlnaDiagnosis.Snapshot(
                sincePlayMs = sincePlay,
                state = info.state,
                status = info.status,
                lanHits = DlnaLog.lanHits.get(),
                userPaused = c.userPaused,
                stalledMs = stalledMs,
            ),
        )
        if (stage != null && !c.reported) {
            report(c, "dlna cast failed: $stage", DlnaDiagnosis.userMessage(stage), "position_ms" to (position?.toString() ?: ""))
        }

        // The video reached its end (or the person stopped it on the TV's own remote): idle for a while after
        // having played means there's nothing left to keep alive.
        if (info.state.equals("PLAYING", true)) c.wasPlaying = true
        if (info.state?.uppercase() in IDLE_STATES && c.wasPlaying) {
            if (c.idleSince == 0L) c.idleSince = now
            if (now - c.idleSince >= IDLE_TO_END_MS) endCast(c, "the renderer went idle after playing")
        } else {
            c.idleSince = 0L
        }
    }

    /**
     * The cast is over without the person pressing Stop (the video ended, the TV left, a safety limit): stop
     * watching, close the proxy and release the foreground service. Sends nothing to the TV.
     */
    private fun endCast(c: Cast, reason: String) {
        DlnaLog.i("cast: ended ($reason) · TV made ${DlnaLog.lanHits.get()} request(s), ${DlnaLog.lanBytes.get()} bytes")
        monitorTask?.cancel(false)
        if (cast === c) cast = null
        proxy.stop()
        DlnaCastService.stop(appContext)
    }

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
