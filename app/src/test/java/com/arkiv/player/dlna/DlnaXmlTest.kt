package com.arkiv.player.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaXmlTest {

    private val envelopeStart =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>"""
    private val envelopeEnd = "</s:Body></s:Envelope>"

    // --- SOAP faults --------------------------------------------------------

    @Test
    fun `a UPnP fault gives its code and description`() {
        val body = envelopeStart + """<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode>
            <errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault>""" + envelopeEnd
        val fault = DlnaXml.fault(body)
        assertEquals(714, fault?.code)
        assertEquals("Illegal MIME-type", fault?.description)
    }

    @Test
    fun `a normal reply is not a fault`() {
        val ok = envelopeStart + """<u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>""" + envelopeEnd
        assertNull(DlnaXml.fault(ok))
        assertNull(DlnaXml.fault(""))
        assertNull(DlnaXml.fault(null))
    }

    @Test
    fun `a fault with no readable code still comes back, so it can be logged`() {
        val fault = DlnaXml.fault("<s:Fault><faultstring>Broken</faultstring></s:Fault>")
        assertNull(fault?.code)
        assertTrue(fault != null)
    }

    // --- transport info -----------------------------------------------------

    @Test
    fun `transport info reads state, status and speed`() {
        val body = envelopeStart + """<u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>
            <CurrentSpeed>1</CurrentSpeed></u:GetTransportInfoResponse>""" + envelopeEnd
        val info = DlnaXml.transportInfo(body)
        assertEquals("PLAYING", info?.state)
        assertEquals("OK", info?.status)
        assertEquals("1", info?.speed)
    }

    @Test
    fun `an error status is read even with namespace prefixes on the arguments`() {
        val body = "<u:R><u:CurrentTransportState>STOPPED</u:CurrentTransportState>" +
            "<u:CurrentTransportStatus>ERROR_OCCURRED</u:CurrentTransportStatus></u:R>"
        val info = DlnaXml.transportInfo(body)
        assertEquals("STOPPED", info?.state)
        assertEquals("ERROR_OCCURRED", info?.status)
    }

    @Test
    fun `an unreadable transport reply is null, not an exception`() {
        assertNull(DlnaXml.transportInfo("<html>404</html>"))
        assertNull(DlnaXml.transportInfo(null))
    }

    // --- position info ------------------------------------------------------

    @Test
    fun `position info converts the renderer's time formats to milliseconds`() {
        val body = "<RelTime>0:01:05</RelTime><TrackDuration>01:30:00.500</TrackDuration>" +
            "<TrackURI>http://192.168.1.5:8080/stream.mp4</TrackURI>"
        val info = DlnaXml.positionInfo(body)
        assertEquals(65_000L, info?.relTimeMs)
        assertEquals(5_400_500L, info?.durationMs)
        assertEquals("http://192.168.1.5:8080/stream.mp4", info?.trackUri)
    }

    @Test
    fun `NOT_IMPLEMENTED (a live stream) is unknown, never zero`() {
        // Zero would read as "the position isn't advancing" and flag every live channel as stalled.
        assertNull(DlnaXml.hmsToMs("NOT_IMPLEMENTED"))
        assertNull(DlnaXml.hmsToMs(""))
        assertNull(DlnaXml.hmsToMs("garbage"))
        assertEquals(0L, DlnaXml.hmsToMs("0:00:00"))
        assertEquals(12_000L, DlnaXml.hmsToMs("+0:00:12"))
    }

    // --- what the renderer can play ----------------------------------------

    @Test
    fun `the sink protocol info lists the MIME types the renderer plays`() {
        val body = "<Sink>http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC,http-get:*:video/mpeg:*," +
            "http-get:*:VIDEO/x-matroska:*,http-get:*:video/mp4:*,http-get:*:*:*</Sink>"
        assertEquals(listOf("video/mp4", "video/mpeg", "video/x-matroska"), DlnaXml.sinkMimes(body))
    }

    @Test
    fun `a renderer that says nothing is 'can't tell', not 'supports nothing'`() {
        assertEquals(emptyList<String>(), DlnaXml.sinkMimes("<Sink></Sink>"))
        assertTrue(DlnaXml.isSupported("video/mp4", emptyList()))
    }

    @Test
    fun `support is checked case-insensitively against the listed types`() {
        val supported = listOf("video/mp4", "video/mpeg")
        assertTrue(DlnaXml.isSupported("VIDEO/MP4", supported))
        assertFalse(DlnaXml.isSupported("application/vnd.apple.mpegurl", supported))
    }

    // --- safe URLs ----------------------------------------------------------

    @Test
    fun `a URL is logged without its query, where the tokens are`() {
        assertEquals(
            "http://192.168.1.10:8765/live.m3u8",
            DlnaXml.safeUrl("http://192.168.1.10:8765/live.m3u8?t=SECRETTOKEN&x=1"),
        )
    }

    @Test
    fun `credentials in a URL are dropped`() {
        assertEquals("https://cdn.example.com/vod/a.mp4", DlnaXml.safeUrl("https://user:pass@cdn.example.com/vod/a.mp4?sig=abc"))
    }

    @Test
    fun `a bare request path and empty input are handled`() {
        assertEquals("/stream.mp4", DlnaXml.safeUrl("/stream.mp4"))
        assertEquals("(none)", DlnaXml.safeUrl(null))
        assertEquals("(none)", DlnaXml.safeUrl("  "))
    }

    @Test
    fun `a long path is cut but keeps its end`() {
        val safe = DlnaXml.safeUrl("http://h/" + "a".repeat(100) + "/file.ts")
        assertTrue(safe, safe.endsWith("/file.ts"))
        assertTrue(safe, safe.length < 90)
    }

    // --- error codes --------------------------------------------------------

    @Test
    fun `the common UPnP error codes are explained`() {
        assertTrue(DlnaXml.describeError(714).contains("MIME"))
        assertTrue(DlnaXml.describeError(716).contains("URL"))
        assertTrue(DlnaXml.describeError(701).contains("transición"))
        assertEquals("error UPnP 999", DlnaXml.describeError(999))
        assertEquals("sin código", DlnaXml.describeError(null))
    }
}
