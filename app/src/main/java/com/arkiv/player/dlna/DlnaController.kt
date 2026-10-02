package com.arkiv.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.arkiv.player.crash.Crash
import com.arkiv.player.cast.CastStrategy
import com.arkiv.player.crash.DlnaFailure
import com.arkiv.player.playback.RemuxCastStart
import com.arkiv.player.playback.RemuxPolicy
import com.arkiv.player.playback.TsRemuxer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
class DlnaController(
    context: Context,
    /** The SAME remux engine/cache Chromecast uses (see [com.arkiv.player.playback.RemuxPolicy]):
     *  a title already remuxed for the TV is reused here without redoing the work. */
    private val tsRemuxer: com.arkiv.player.playback.TsRemuxer,
    /** Its OWN server, not [com.arkiv.player.AppGraph.localFileServer]: that one is single-file and
     *  restarts its socket on every change, so sharing it with Chromecast would steal the port out
     *  from under whichever of the two casts second. */
    private val localFileServer: com.arkiv.player.playback.LocalFileServer,
    /**
     * The SAME server the Chromecast casts its growing remux from (`AppGraph.remuxHlsServer`), so
     * DLNA gets its pacing (`RemuxPacing`, wired into [tsRemuxer]), the reuse of an earlier cast's
     * remux (`RemuxLeftover`) and its fixes for free. One title at a time, like the TV.
     */
    private val remuxHls: com.arkiv.player.playback.RemuxHlsServer,
) {

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

    /** Where a cast waits on its remux; the export itself runs in [tsRemuxer]'s own scope. */
    private val remuxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped by every new cast and by [stop]: a remux wait that finds it changed has been superseded. */
    @Volatile
    private var attempt = 0

    /**
     * The remux a cast is waiting on (staged on [remuxHls], not on the TV yet), so [stop] can
     * retire it: stopped mid-wait, its export was left running (or paced forever) with nobody to cast it.
     */
    @Volatile
    private var waitingRemuxKey: String? = null

    /** The TV's requests to the remux server and to the finished remux's file server, counted and logged. */
    private val remuxRequests = DlnaLanRequests("remux-hls", timings = false)

    /**
     * The title's subtitles for the TV (see [DlnaSubtitles]): read on every send, so the one on the
     * phone when the video is sent is the one the TV gets. Set by AppGraph; none by default.
     */
    @Volatile internal var subtitleSidecar: (offsetMs: Long) -> DlnaSidecar? = { null }

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

        /** The container sniff's read deadline: long enough for a slow CDN to open, short enough to still be a start. */
        const val SNIFF_READ_MS = 30_000L
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
        /** The SOAP rejection that ended this cast, for [DirectPlayFallback]: the step, the UPnP code and the HTTP status. */
        @Volatile var soapRejection: Triple<String, Int?, Int>? = null
        /** While a fallback may still recover this cast, its failure report waits here instead of being sent. */
        @Volatile var deferReport = false
        @Volatile var pendingReport: (() -> Unit)? = null
        /** Where the TV should be once it plays (see [DlnaSeek]); 0 = the top, nothing to do. */
        @Volatile var startMs = 0L
        @Volatile var seekAttempts = 0
        @Volatile var seekSettled = false
        /**
         * A `Seek` was accepted: the next position read checks whether the TV really went there,
         * and corrects it when it did not ([DlnaSeek.correction]).
         */
        @Volatile var seekVerify = false
        /** What the last accepted `Seek` asked for (the TV's clock), and the corrections sent after the first. */
        @Volatile var seekSentMs = 0L
        @Volatile var seekCorrections = 0
        /** The remux this cast plays while it grows (the HLS route): stopped with the cast. */
        @Volatile var remuxKey: String? = null
        /**
         * Where what the TV plays begins in the title (a remux started mid-title, `TsStart`), 0
         * from the top. The TV counts from there: [startMs] is on its clock, the phone's and the
         * subtitles' are the title's.
         */
        @Volatile var offsetMs = 0L
        /** The remux's start point on the grid ([RemuxPolicy.fromInKey]): another audio starts on the same one. */
        @Volatile var startGrid = 0L
        /** What the TV was sent ([startPlayback]), for [resendForSubtitles] to send it again. */
        @Volatile var url: String? = null
        @Volatile var title: String = ""
        @Volatile var source: String = ""
        /** The subtitle the TV was sent with ([DlnaSidecar.selected]'s URL); null = none. */
        @Volatile var subtitleUrl: String? = null
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
        val resolved = DlnaXml.resolveUrl(base, ctrl)
        if (resolved == null) {
            DlnaLog.w("parseDevice: could not resolve controlURL '$ctrl' against '${DlnaXml.safeUrl(base)}'")
            return null
        }
        val cmResolved = connectionManagerUrl?.let { DlnaXml.resolveUrl(base, it) }
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
    suspend fun setUrlAndPlay(
        device: DlnaDevice,
        archiveUrl: String,
        title: String,
        /** The audio the phone has on, for the remux to carry; null = the file's default. */
        audio: com.arkiv.player.cast.CastAudioChoice? = null,
        /** Where the person is ([com.arkiv.player.cast.CastStart]): the TV is put there once it plays. */
        startMs: Long = 0L,
        /**
         * What the remux is filed under: the CDN url for Magis, stable across token refreshes and
         * the same key the Chromecast uses -- so a title remuxed (or half-remuxed) for one is
         * reused by the other. [archiveUrl] when there is nothing steadier.
         */
        remuxKeyUrl: String = archiveUrl,
        /**
         * Another audio for the title the TV already plays ([DlnaAudioSwitch]): where the TV should
         * start, asked again whenever it is about to be decided. Non-null keeps the TV on what it
         * plays while the new remux gets ready (staged on [remuxHls]) and hands it over only once
         * the new one covers where the TV is BY THEN -- the Chromecast's rule. Null: a fresh cast.
         */
        followTv: (() -> Long)? = null,
    ): Boolean {
        val mine = ++attempt
        // A file:// (a download) can't be proxied: OkHttp only speaks http(s), so the TV would get a
        // connection that closes with nothing, silently. Say so instead.
        if (!archiveUrl.startsWith("http", ignoreCase = true)) {
            val c = beginCast(device, kind = "vod-proxy", mime = "video/mp4", title = title, source = archiveUrl)
            return failPreflight(
                c, "source_not_http", "Este contenido no se puede enviar a la TV por DLNA (es un archivo local)",
                "source=${DlnaXml.safeUrl(archiveUrl)}",
            )
        }
        // The container comes from the BYTES, not from a guess: the source's own Content-Type is
        // often generic (`application/octet-stream`), and most of this catalog is really MPEG-TS.
        // A renderer that trusts a wrong "video/mp4" label seeks around forever looking for box
        // structure that isn't there -- see VideoContainer's KDoc.
        val container = com.arkiv.player.playback.VideoContainer.of(sniffHeader(archiveUrl), archiveUrl)
        val format = CastStrategy.formatOf(archiveUrl, container.mime)

        // The route is the Chromecast's own decision (CastStrategy), with what THIS renderer lists
        // as its input. A bare TS is what stalled the Samsung TVs that DO claim mp4 support; but some
        // renderers -- often an OLDER TV with no mp4 in its list at all, like a 2011 Philips -- list
        // MPEG-TS as playable, and asking first keeps them from paying for a remux they never
        // needed. Only asked for a TS: nothing else depends on it.
        val sinkMimes = if (format == CastStrategy.Format.MPEG_TS) fetchSinkMimes(device) else emptyList()
        val receiver = DlnaRenderer.receiverOf(sinkMimes)
        // Never DIRECT: a renderer gets plain http from the phone (many take no https or redirects).
        val route = CastStrategy.choose(format, needsHeaders = true, directAllowed = false, remuxAvailable = true, receiver = receiver)
        DlnaLog.diag("route ${route.name} for ${format.name} · renderer ${DlnaRenderer.summary(sinkMimes)} · phone at ${startMs / 1000}s")

        if (route != CastStrategy.Route.REMUX && route != CastStrategy.Route.REMUX_FILE) {
            if (format != CastStrategy.Format.MPEG_TS) return playViaProxy(device, archiveUrl, container, title, startMs)
            DlnaLog.i("cast: renderer already lists ${container.mime} (${sinkMimes.size} types): skipping the remux")
            // A TV can list a container and still refuse the file (an LG webOS answered Play with 501 Action Failed): its
            // rejection is held back, and if it is the kind a real MP4 fixes, the remux is tried before giving up.
            if (playViaProxy(device, archiveUrl, container, title, startMs, deferReport = true)) return true
            val direct = cast
            val why = direct?.soapRejection
            val next = if (why != null && DirectPlayFallback.shouldRemux(why.first, why.second, why.third)) {
                CastStrategy.afterRejection(format, route, receiver, remuxAvailable = true)
            } else {
                CastStrategy.Route.NONE
            }
            if (next == CastStrategy.Route.NONE) {
                direct?.pendingReport?.invoke()
                return false
            }
            DlnaLog.diagW("cast: the TV rejected the file as it is (${why?.first} upnp=${why?.second}): retrying as ${next.name}")
            return castRemux(device, archiveUrl, remuxKeyUrl, title, audio, next, startMs, mine, followTv)
        }
        return castRemux(device, archiveUrl, remuxKeyUrl, title, audio, route, startMs, mine, followTv)
    }

    /**
     * The TS remuxed into a real MP4 by the SAME engine, cache and audio choice the Chromecast uses
     * (TsRemuxer, keyed by [RemuxPolicy.keyFrom]), with its patient CDN reads. [route]:
     * - [CastStrategy.Route.REMUX]: the remux goes out WHILE it is written, as the growing HLS
     *   playlist of [remuxHls], from the phone's position once the remux covers it -- the same
     *   wait as the Chromecast's ([RemuxCastStart]). A renderer that refuses it falls back to:
     * - [CastStrategy.Route.REMUX_FILE]: the whole MP4 once finished, served with ranges. What
     *   every DLNA cast of a TS did before; still the one for renderers that list no HLS.
     * A remux already finished on disk is always sent whole: nothing to wait for, and a file with
     * a length and ranges is what every renderer seeks best in.
     *
     * With [followTv] (another audio while the TV plays this title) the TV keeps the cast it has
     * until the new remux is ready to take over: the growing one is only STAGED on [remuxHls]
     * meanwhile and waited for where the TV is by then (never "the nearest point" minutes back),
     * the whole file is waited for in full. The TV is paused, the old remux retired and the new
     * one sent at that moment, not before. Stopped meanwhile ([stop]), the new remux stops too.
     */
    private suspend fun castRemux(
        device: DlnaDevice,
        archiveUrl: String,
        remuxKeyUrl: String,
        title: String,
        audio: com.arkiv.player.cast.CastAudioChoice?,
        route: CastStrategy.Route,
        startMs: Long,
        mine: Int,
        followTv: (() -> Long)? = null,
    ): Boolean {
        // The cast the TV is on while another audio gets ready ([followTv]); null for a fresh cast.
        val onTv = cast?.takeIf { followTv != null }
        // Where the remux starts: a keyframe on the grid before the phone's position, as the
        // Chromecast's (TsRemuxer.startPoint, TsStart) -- another audio keeps the TV's, so the TV
        // goes on with the same clock. 0:00 when switched off, near the top or not found.
        val grid = onTv?.startGrid ?: tsRemuxer.startPoint(archiveUrl, remuxKeyUrl, audio?.ordinal, startMs)
        // Keyed and remuxed with the phone's audio: a remux carries ONE audio track, and
        // without this it was Transformer's pick, whatever the phone's menu said.
        val key = RemuxPolicy.keyFrom(remuxKeyUrl, grid, audio?.ordinal)
        // The TV counts from where the remux begins; [startMs] and [followTv] are the title's.
        val offset = tsRemuxer.startMsOf(key)
        val tvMs = { titleMs: Long -> (titleMs - offset).coerceAtLeast(0L) }
        if (offset > 0L) DlnaLog.diag("remux starts at ${offset / 1000}s of the title (phone at ${startMs / 1000}s)")
        // A fresh cast over a remux one: the remux of the one the TV had stops (it covers the whole
        // title, and nothing will play it any more). What it wrote is kept, so switching back is quick.
        // Following the TV, it keeps playing that one until the hand-over ([handOver]).
        if (onTv == null) {
            cast?.takeIf { it.remuxKey != null && it.remuxKey != key }?.let {
                DlnaLog.diag("cast: another remux → stopping the previous one")
                releaseRemux(it)
            }
        }
        tsRemuxer.alreadyDone(key)?.let { file ->
            DlnaLog.diag("remux already whole on disk: sending the MP4 file")
            val at = tvMs(handOver(onTv, key, followTv, startMs))
            val c = beginCast(device, kind = "vod-remux", mime = com.arkiv.player.playback.Container.MP4.mime, title = title, source = archiveUrl, startMs = at)
            c.offsetMs = offset
            c.startGrid = grid
            return playRemuxFile(c, file, title)
        }
        val export = remuxScope.async { tsRemuxer.remux(archiveUrl, key, audio) }
        // Where the whole file starts if it comes to that (the TV's clock): moved to the HLS
        // hand-over's point once there was one.
        var resumeAt = tvMs(startMs)
        // Following the TV until it is handed the new remux; from then on it is this cast's own.
        var following = onTv != null
        // Still wanted: not stopped or superseded, and -- following the TV -- the TV still on its cast.
        val wanted = { attempt == mine && (!following || cast === onTv) }

        if (route == CastStrategy.Route.REMUX) {
            var start: RemuxCastStart.Start? = null
            waitingRemuxKey = key
            RemuxCastStart.await(
                remuxHls, key, tvMs(startMs),
                inProgress = tsRemuxer::inProgress,
                // A finished remux ends the wait too: then the whole file goes, below. A wait
                // given up (the cast stopped or superseded meanwhile) retires the remux with it.
                stillWanted = { wanted() && !export.isCompleted },
                lan = remuxRequests,
                diagPrefix = "dlna ",
                // The TV goes on with the old audio meanwhile: it waits as long as it takes to
                // cover where the TV is by then, instead of jumping it back minutes.
                maxWaitSec = if (followTv != null) Int.MAX_VALUE else com.arkiv.player.playback.RemuxHls.RESUME_WAIT_SEC,
                wantedNow = followTv?.let { f -> { tvMs(f()) } } ?: { tvMs(startMs) },
            ) { s ->
                // The TV stops on the old audio right where the new one takes over.
                if (onTv != null) pause(onTv.device)
                start = s
                true
            }
            if (waitingRemuxKey == key) waitingRemuxKey = null
            if (!wanted()) return gaveUp(key)
            val ready = start
            if (ready != null) {
                val url = ready.url
                if (url == null) {
                    if (remuxHls.isServing(key)) remuxHls.stop()
                    tsRemuxer.stop(key)
                    val c = beginCast(device, kind = "vod-remux-hls", mime = CastStrategy.MIME_HLS, title = title, source = archiveUrl)
                    return failPreflight(c, "no_wifi_ip", "No se detectó la red WiFi del teléfono", "wifiEnabled=${wifi.isWifiEnabled}")
                }
                // Following the TV, the old remux was retired by the hand-over ([RemuxHlsServer.serve]).
                onTv?.remuxKey = null
                following = false
                resumeAt = ready.fromMs
                // The playlist's #EXT-X-START says where to begin; the Seek after Play backs it up.
                remuxHls.planStart(key, ready.fromMs)
                val c = beginCast(device, kind = "vod-remux-hls", mime = CastStrategy.MIME_HLS, title = title, source = archiveUrl, startMs = ready.fromMs)
                c.deferReport = true
                c.remuxKey = key
                c.offsetMs = offset
                c.startGrid = grid
                DlnaLog.diag(
                    "remux has ${ready.readySec.toInt()}s ready → sending it as HLS from ${ready.fromMs / 1000}s " +
                        if (onTv != null) "(where the TV was)" else "(phone at ${startMs / 1000}s)",
                )
                if (startPlayback(c, url, title)) {
                    watchRemux(c, export)
                    return true
                }
                c.remuxKey = null
                if (remuxHls.isServing(key)) remuxHls.stop()
                val why = c.soapRejection
                if (why == null || !DirectPlayFallback.wholeFileAfterHls(why.second, why.third)) {
                    // Nothing is going to play it: stop converting the rest of the title (what it wrote stays).
                    tsRemuxer.stop(key)
                    c.pendingReport?.invoke()
                    return false
                }
                DlnaLog.diagW("cast: the TV refused the remux as HLS (${why.first} upnp=${why.second}): waiting for the whole MP4 instead")
            } else {
                // The remux finished (or failed) before it covered the start point: the file decides.
                if (remuxHls.isServing(key)) remuxHls.stop() else runCatching { remuxHls.unstage(key) }
            }
        }

        if (following) {
            // The whole file, while the TV plays on: checked every second, so a stop meanwhile stops it.
            while (!export.isCompleted && wanted()) kotlinx.coroutines.delay(1_000L)
            if (!wanted()) return gaveUp(key)
        }
        val result = export.await()
        if (following && result is TsRemuxer.RemuxResult.Failed) {
            // Nothing to hand over: the TV stays on the audio it has, its cast untouched.
            DlnaLog.diagW("cast: the new audio's remux failed (${result.reason}): the TV keeps the one it had")
            lastError = "No se pudo preparar el nuevo audio: la TV sigue con el anterior"
            return false
        }
        val at = if (following) tvMs(handOver(onTv, key, followTv, startMs)) else resumeAt
        val c = beginCast(device, kind = "vod-remux", mime = com.arkiv.player.playback.Container.MP4.mime, title = title, source = archiveUrl, startMs = at)
        c.offsetMs = offset
        c.startGrid = grid
        return when (result) {
            is TsRemuxer.RemuxResult.Failed ->
                failPreflight(c, "remux_failed", "No se pudo preparar el video para la TV", "reason=${result.reason}")
            is TsRemuxer.RemuxResult.Done -> {
                if (attempt != mine) return false
                playRemuxFile(c, result.file, title)
            }
        }
    }

    /**
     * The TV leaves the cast it had ([onTv], following it) for the whole remux of [key]: paused
     * where it is, that cast's remux retired, and the position read again ([followTv]). Without
     * [onTv] (a fresh cast) only [startMs].
     */
    private fun handOver(onTv: Cast?, key: String, followTv: (() -> Long)?, startMs: Long): Long {
        if (onTv == null) return startMs
        val at = followTv?.invoke() ?: startMs
        pause(onTv.device)
        if (onTv.remuxKey != key) releaseRemux(onTv)
        return at
    }

    /**
     * A remux wait given up: stopped ([stop] leaves no cast), superseded, or the TV left the cast
     * it was following. Without a cast nothing will play this remux, which no cast owns yet for
     * [releaseRemux] to find: stopped, and unstaged. A newer cast keeps it.
     */
    private fun gaveUp(key: String): Boolean {
        DlnaLog.i("cast: superseded while the remux was getting ready")
        if (cast == null) {
            tsRemuxer.stop(key)
            runCatching { remuxHls.unstage(key) }
            if (remuxHls.isServing(key)) remuxHls.stop()
        }
        return false
    }

    /** The finished remux [file], served whole with ranges by the DLNA's own [localFileServer]. */
    private fun playRemuxFile(c: Cast, file: java.io.File, title: String): Boolean {
        val localUrl = localFileServer.serve(file)
            ?: return failPreflight(c, "no_wifi_ip", "No se detectó la red WiFi del teléfono", "wifiEnabled=${wifi.isWifiEnabled}")
        DlnaLog.i("cast: remuxed ${file.name} (${file.length()}B) -> serving as $localUrl")
        return startPlayback(c, localUrl, title)
    }

    /** Says in the trail how the remux behind a playing HLS cast ends: a failure there is a TV about to stall. */
    private fun watchRemux(c: Cast, export: Deferred<TsRemuxer.RemuxResult>) {
        remuxScope.launch {
            val result = runCatching { export.await() }.getOrNull() ?: return@launch
            if (cast !== c) return@launch
            when (result) {
                is TsRemuxer.RemuxResult.Done -> DlnaLog.diag("remux finished: the TV's playlist just gets its end")
                is TsRemuxer.RemuxResult.Failed -> DlnaLog.diagW("remux FAILED mid-cast (${result.reason}): the TV will stop where it ends")
            }
        }
    }

    /** Proxies [archiveUrl] to the TV as-is, honestly labeled as [container] -- no remux involved. */
    private fun playViaProxy(
        device: DlnaDevice,
        archiveUrl: String,
        container: com.arkiv.player.playback.Container,
        title: String,
        startMs: Long,
        deferReport: Boolean = false,
    ): Boolean {
        val c = beginCast(device, kind = "vod-proxy", mime = container.mime, title = title, source = archiveUrl, startMs = startMs)
        c.deferReport = deferReport
        val ip = wifiIp() ?: return failPreflight(c, "no_wifi_ip", "No se detectó la red WiFi del teléfono", "wifiEnabled=${wifi.isWifiEnabled}")
        val token = proxy.setTarget(archiveUrl, container.mime)
        val port = proxy.ensureStarted()
        val ext = com.arkiv.player.playback.VideoContainer.extensionFor(container)
        // The token goes in the path: renderers can't send headers, and it keeps the `.ext` last for name-sniffers.
        val localUrl = "http://$ip:$port/t/$token/stream.$ext"
        DlnaLog.i("cast: proxy on $ip:$port serving ${DlnaXml.safeUrl(archiveUrl)} as ${DlnaXml.safeUrl(localUrl)} (declared ${c.mime})")
        return startPlayback(c, localUrl, title)
    }

    /** First [com.arkiv.player.playback.VideoContainer.SIGNATURE_BYTES] of [url], or empty if the
     *  source didn't answer in time -- the container then falls back to the URL's extension (see
     *  [com.arkiv.player.playback.VideoContainer.of]), same as when there are no bytes at all. */
    private fun sniffHeader(url: String): ByteArray {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Arkiv")
            .header("Range", "bytes=0-${com.arkiv.player.playback.VideoContainer.SIGNATURE_BYTES - 1}")
        // The remux's patience (RemuxPolicy), not the SOAP calls' 8 s: a CDN that takes 7 s to open
        // (measured 2026-10-01) made this come back empty, and an empty answer is a TS cast as "mp4".
        // The loopback proxy is asked for its patient deadlines too, as the remux asks; never a remote host.
        if (url.startsWith("http://127.0.0.1:")) builder.header(com.arkiv.player.playback.ArchiveCacheProxy.REMUX_HEADER, "1")
        val patient = client.newBuilder()
            .connectTimeout(RemuxPolicy.INPUT_CONNECT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(SNIFF_READ_MS, TimeUnit.MILLISECONDS)
            .build()
        val req = builder.build()
        return runCatching {
            patient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) ByteArray(0) else resp.body?.bytes() ?: ByteArray(0)
            }
        }.getOrDefault(ByteArray(0))
    }

    /** Plays a raw URL already reachable over LAN (today, the live channel's HLS proxy). */
    fun playRawUrl(device: DlnaDevice, url: String, title: String, mime: String = "video/mp4", startMs: Long = 0L): Boolean {
        ++attempt
        val kind = if (mime.contains("mpegurl", ignoreCase = true)) "live-hls" else "raw-url"
        val c = beginCast(device, kind = kind, mime = mime, title = title, source = url, startMs = startMs)
        DlnaLog.diag("route ${kind} (sent as it is) · phone at ${startMs / 1000}s")
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

    private fun beginCast(device: DlnaDevice, kind: String, mime: String, title: String, source: String, startMs: Long = 0L): Cast {
        monitorTask?.cancel(false)
        val c = Cast(DlnaLog.newSession(), device, kind, mime)
        c.startMs = startMs
        lastTvMs = null
        c.source = source
        cast = c
        lastError = null
        DlnaLog.i(
            "cast start · kind=$kind device='${device.friendlyName}' (${device.manufacturer} ${device.model}) " +
                "title='${title.take(60)}' source=${DlnaXml.safeUrl(source)} start=${startMs / 1000}s",
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

        // The title's, if it has any (a live channel never offers any: see castSubtitleSources).
        val subs = runCatching { subtitleSidecar(c.offsetMs) }.getOrNull()
        DlnaSubtitles.captionHeader(subs).let { proxy.extraHeaders = it; localFileServer.extraHeaders = it }
        subs?.selected?.let { DlnaLog.i("cast: with subtitles · ${it.language} + ${subs.others.size} more") }
        c.url = url
        c.title = title
        c.subtitleUrl = subs?.selected?.url
        val didl = xmlEscape(didlLiteFor(url, title, c.mime, subs))
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
        if (!play.ok) {
            if (!DlnaDiagnosis.playStillLoading(noHttpAnswer = play.error != null, tvRequests = DlnaLog.lanHits.get())) return failSoap(c, "play", play)
            DlnaLog.w("cast: Play has not answered yet but the TV already made ${DlnaLog.lanHits.get()} request(s): keeping the cast, the monitor will judge it")
        }

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

    /** What the cast in progress is ([beginCast]'s kind: `vod-remux-hls`, `vod-proxy`...), null with none. */
    fun activeKind(): String? = cast?.kind

    /** The cast in progress (a new one per send), null with none: tells whether a re-send replaced it. */
    fun activeCastId(): String? = cast?.id

    /** The subtitle the cast in progress was sent with (its URL on the phone); null = none or no cast. */
    fun subtitleOnTv(): String? = cast?.subtitleUrl

    /** The subtitle a send would carry now: the phone's choice ([subtitleSidecar]); null = off or none offered. */
    fun subtitleWanted(): String? = runCatching { subtitleSidecar(cast?.offsetMs ?: 0L) }.getOrNull()?.selected?.url

    /**
     * Sends the cast in progress again, the same URL with the subtitles the phone has on now in its
     * DIDL-Lite and headers ([startPlayback]), and puts the TV at [atMs] once it plays (the start
     * `Seek`, [DlnaSeek]). A renderer has no action to switch subtitles mid-play: re-sending is the
     * only way, so the TV is paused where it is first. A growing remux goes on as it was (its
     * playlist now starts at [atMs]). Blocking; false with no cast or when the TV refused it
     * ([lastError] says why).
     */
    fun resendForSubtitles(atMs: Long): Boolean {
        val old = cast ?: return false
        val url = old.url ?: return false
        pause(old.device)
        // [atMs] is the title's; the TV's clock starts where its remux does.
        val tvMs = (atMs - old.offsetMs).coerceAtLeast(0L)
        val c = beginCast(old.device, kind = old.kind, mime = old.mime, title = old.title, source = old.source, startMs = tvMs)
        c.offsetMs = old.offsetMs
        c.startGrid = old.startGrid
        // The remux goes on with the new cast: nothing must release it with the old one.
        c.remuxKey = old.remuxKey
        old.remuxKey = null
        c.remuxKey?.let { remuxHls.planStart(it, tvMs) }
        DlnaLog.diag("subtitles: re-sending the cast ${if (subtitleWanted() != null) "with" else "without"} subtitles from ${atMs / 1000}s")
        return startPlayback(c, url, old.title)
    }

    /**
     * Where the TV is right now, for a re-send to continue there ([DlnaAudioSwitch.tvPositionMs]):
     * asks it (`GetPositionInfo`), falling back on what the monitor last read. On the TITLE's
     * clock: a remux started mid-title counts from [Cast.offsetMs]. Blocking; null with no cast.
     */
    fun tvPositionMs(): Long? {
        val c = cast ?: return null
        val p = soap(c.device.controlUrl, "GetPositionInfo", "<u:GetPositionInfo xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:GetPositionInfo>")
        val reported = DlnaXml.positionInfo(p.body)?.relTimeMs
        val at = DlnaAudioSwitch.tvPositionMs(reported, c.lastPositionMs, c.startMs, settled(c))?.plus(c.offsetMs)
        if (at != null) lastTvMs = at
        DlnaLog.diag(
            "position: TV says ${reported?.div(1000)}s, last read ${c.lastPositionMs?.div(1000)}s, sent at ${c.startMs / 1000}s" +
                (if (c.offsetMs > 0L) " + its remux's start ${c.offsetMs / 1000}s" else "") + " → ${at?.div(1000)}s",
        )
        return at
    }

    /** The start point is where the TV is: no Seek pending, and none waiting to be checked or corrected. */
    private fun settled(c: Cast): Boolean = c.seekSettled && !c.seekVerify

    private fun knownPositionOf(c: Cast): Long? =
        DlnaAudioSwitch.tvPositionMs(null, c.lastPositionMs, c.startMs, settled(c))?.plus(c.offsetMs)

    /**
     * Where the TV was at the last read, on the title's clock, without asking it (main thread safe):
     * the cast in progress', else the last one's as it ended ([stop] reads it once more first). What
     * the phone saves as progress during a DLNA cast and resumes from when it ends: the phone's own
     * player is paused the whole time, so its position says nothing. Null before any cast.
     */
    fun knownTvPositionMs(): Long? = cast?.let(::knownPositionOf) ?: lastTvMs

    @Volatile private var lastTvMs: Long? = null

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
        attempt++
        monitorTask?.cancel(false)
        // Where the TV got to, asked BEFORE the Stop (after it a renderer reports 0): the phone resumes there.
        if (cast != null) runCatching { tvPositionMs() }
        soap(device.controlUrl, "Stop", "<u:Stop xmlns:u=\"$AVT\"><InstanceID>0</InstanceID></u:Stop>")
        DlnaLog.diag("cast: stopped · TV made ${DlnaLog.lanHits.get()} request(s) to our servers, ${DlnaLog.lanBytes.get()} bytes")
        val ended = cast
        cast = null
        proxy.stop()
        releaseRemux(ended)
        waitingRemuxKey?.let { key ->
            waitingRemuxKey = null
            runCatching { remuxHls.unstage(key) }
        }
        DlnaCastService.stop(appContext)
    }

    /**
     * Stops the growing remux [c] was playing and stops handing it out, as the Chromecast does when
     * its cast ends: the remux covers the whole title, so a cast that ends after ten minutes would
     * otherwise keep pulling the rest of it down the person's connection. What it wrote is kept
     * (`RemuxLeftover`): casting the title again starts on it at once.
     */
    private fun releaseRemux(c: Cast?) {
        val key = c?.remuxKey ?: return
        c.remuxKey = null
        runCatching { tsRemuxer.stop(key) }
        if (remuxHls.isServing(key)) runCatching { remuxHls.stop() }
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
        c.soapRejection = Triple(stage, r.fault?.code, r.http)
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
        val send = {
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
        if (c.deferReport) c.pendingReport = send else send()
    }

    // ------------------------------------------------------------------ monitor

    /** What the renderer says it can play; one line, and a warning when the MIME we declared isn't in it. */
    private fun logSinkProtocols(c: Cast) {
        if (c.device.connectionManagerUrl == null) {
            DlnaLog.i("renderer formats: unknown (no ConnectionManager service)")
            return
        }
        val mimes = fetchSinkMimes(c.device)
        c.sinkMimes = mimes
        DlnaLog.diag("renderer formats: ${DlnaRenderer.summary(mimes)}")
        if (mimes.isEmpty()) {
            DlnaLog.i("renderer formats: none listed (can't tell)")
        } else if (DlnaXml.isSupported(c.mime, mimes)) {
            DlnaLog.i("renderer formats: ${mimes.size} types, declared ${c.mime} IS listed: $mimes")
        } else {
            DlnaLog.w("renderer formats: declared ${c.mime} is NOT in what the TV lists: $mimes")
        }
    }

    /** What [device] can play (`GetProtocolInfo`), or empty if it has no ConnectionManager service
     *  or didn't answer -- callers then fall back to whatever they'd do with no information at all. */
    private fun fetchSinkMimes(device: DlnaDevice): List<String> {
        val url = device.connectionManagerUrl ?: return emptyList()
        val r = soap(url, "GetProtocolInfo", "<u:GetProtocolInfo xmlns:u=\"$CM\"/>", CM)
        return DlnaXml.sinkMimes(r.body)
    }

    /**
     * [fetchSinkMimes] for a caller that has to decide BEFORE sending (an HLS playlist to a
     * renderer that may list none, [DlnaRenderer.takesHls]). A network call: off the main thread.
     */
    fun sinkMimesOf(device: DlnaDevice): List<String> = fetchSinkMimes(device).also {
        DlnaLog.diag("renderer formats (asked before sending): ${DlnaRenderer.summary(it)}")
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
        // A Seek waiting to be checked is read on the next poll whatever the pace: the TV keeps playing meanwhile.
        if (sincePlay > 60_000L && c.polls % 5 != 0 && !c.seekVerify) return

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

        positionTv(c, info.state, position, sincePlay)
        lastTvMs = knownPositionOf(c)

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
     * Puts the TV at [Cast.startMs] once it plays: an AVTransport `Seek` (see [DlnaSeek]), retried
     * once if refused, then left playing where it is. Runs on the monitor's thread, from [pollOnce].
     */
    private fun positionTv(c: Cast, state: String?, positionMs: Long?, sincePlayMs: Long) {
        if (c.seekVerify && positionMs != null && state.equals("PLAYING", true)) {
            c.seekVerify = false
            DlnaLog.diag("start: after the Seek the TV reports ${positionMs / 1000}s (wanted ${c.startMs / 1000}s)")
            DlnaSeek.correction(c.startMs, c.seekSentMs, positionMs, c.seekCorrections)?.let { next -> correctSeek(c, positionMs, next) }
        }
        if (c.seekSettled) return
        when (DlnaSeek.next(c.startMs, state, positionMs, sincePlayMs, c.seekAttempts)) {
            DlnaSeek.Step.NONE, DlnaSeek.Step.WAIT -> Unit
            DlnaSeek.Step.ALREADY_THERE -> {
                c.seekSettled = true
                DlnaLog.diag("start: the TV is already at ${positionMs?.div(1000)}s (wanted ${c.startMs / 1000}s): no Seek")
            }
            DlnaSeek.Step.GIVE_UP -> {
                c.seekSettled = true
                DlnaLog.diagW("start: the TV never played within ${DlnaSeek.GIVE_UP_AFTER_MS / 1000}s: no Seek to ${c.startMs / 1000}s")
            }
            DlnaSeek.Step.SEEK -> {
                c.seekAttempts++
                val target = DlnaSeek.relTime(c.startMs)
                val r = soap(
                    c.device.controlUrl, "Seek",
                    "<u:Seek xmlns:u=\"$AVT\"><InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target></u:Seek>",
                    readTimeoutSec = 20,
                )
                if (r.ok) {
                    c.seekSettled = true
                    c.seekVerify = true
                    c.seekSentMs = c.startMs
                    DlnaLog.diag("start: Seek to $target accepted (attempt ${c.seekAttempts}, TV was ${state} at ${positionMs?.div(1000)}s)")
                } else {
                    val last = c.seekAttempts >= DlnaSeek.MAX_ATTEMPTS
                    if (last) c.seekSettled = true
                    DlnaLog.diagW(
                        "start: Seek to $target REFUSED (attempt ${c.seekAttempts}, http=${r.http} upnp=${r.fault?.code} " +
                            "'${r.fault?.description ?: DlnaXml.describeError(r.fault?.code)}' error=${r.error})" +
                            if (last) ": it keeps playing from where it is" else ": retrying once",
                    )
                }
            }
        }
    }

    /**
     * A `Seek` that landed [reportedMs] instead of the start point: asks for [nextMs] instead (see
     * [DlnaSeek.correction]) and checks again on the next poll. A refusal leaves the TV where it is.
     */
    private fun correctSeek(c: Cast, reportedMs: Long, nextMs: Long) {
        c.seekCorrections++
        val target = DlnaSeek.relTime(nextMs)
        val missed = (c.startMs - reportedMs) / 1000
        val r = soap(
            c.device.controlUrl, "Seek",
            "<u:Seek xmlns:u=\"$AVT\"><InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target></u:Seek>",
            readTimeoutSec = 20,
        )
        if (r.ok) {
            c.seekSentMs = nextMs
            c.seekVerify = true
            DlnaLog.diag(
                "start: the TV landed ${kotlin.math.abs(missed)}s ${if (missed > 0) "early" else "late"} → corrective Seek to $target " +
                    "(correction ${c.seekCorrections}/${DlnaSeek.MAX_CORRECTIONS}, wanted ${c.startMs / 1000}s)",
            )
        } else {
            DlnaLog.diagW(
                "start: corrective Seek to $target REFUSED (http=${r.http} upnp=${r.fault?.code} error=${r.error}): " +
                    "it keeps playing from ${reportedMs / 1000}s",
            )
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
        releaseRemux(c)
        DlnaCastService.stop(appContext)
    }

    private fun didlLiteFor(url: String, title: String, mime: String, subs: DlnaSidecar? = null): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"" + DlnaSubtitles.namespaces(subs) + ">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:" +
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\"" +
            DlnaSubtitles.videoResAttributes(subs, ::xmlEscape) + ">" +
            "${xmlEscape(url)}</res>" +
            DlnaSubtitles.itemElements(subs, ::xmlEscape) +
            "</item></DIDL-Lite>"

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
