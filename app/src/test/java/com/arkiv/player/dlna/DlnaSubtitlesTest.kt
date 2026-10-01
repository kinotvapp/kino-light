package com.arkiv.player.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a DLNA renderer is told about subtitles: valid, escaped XML in every field the common TVs read. */
class DlnaSubtitlesTest {

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private val es = DlnaSubtitle("http://192.168.1.5:4000/s/tok/0/0.srt?a=1&b=2", "es", "Español")
    private val en = DlnaSubtitle("http://192.168.1.5:4000/s/tok/0/1.srt", "en", "Inglés")

    @Test
    fun `with the subtitles off nothing at all is added`() {
        val off = DlnaSidecar(null, listOf(es, en))
        assertEquals("", DlnaSubtitles.namespaces(off))
        assertEquals("", DlnaSubtitles.videoResAttributes(off, ::escape))
        assertEquals("", DlnaSubtitles.itemElements(off, ::escape))
        assertEquals("", DlnaSubtitles.captionHeader(off))
        assertEquals("", DlnaSubtitles.captionHeader(null))
    }

    @Test
    fun `the selected subtitle goes in the Samsung and PacketVideo fields, escaped`() {
        val sidecar = DlnaSidecar(es, listOf(en))
        assertEquals(" xmlns:sec=\"http://www.sec.co.kr/\" xmlns:pv=\"http://www.pv.com/pvns/\"", DlnaSubtitles.namespaces(sidecar))
        assertEquals(
            " pv:subtitleFileType=\"srt\" pv:subtitleFileUri=\"http://192.168.1.5:4000/s/tok/0/0.srt?a=1&amp;b=2\"",
            DlnaSubtitles.videoResAttributes(sidecar, ::escape),
        )
        val items = DlnaSubtitles.itemElements(sidecar, ::escape)
        assertTrue(items.startsWith("<sec:CaptionInfoEx sec:type=\"srt\">http://192.168.1.5:4000/s/tok/0/0.srt?a=1&amp;b=2</sec:CaptionInfoEx>"))
        assertTrue(items.contains("<sec:CaptionInfo sec:type=\"srt\">http://192.168.1.5:4000/s/tok/0/0.srt?a=1&amp;b=2</sec:CaptionInfo>"))
        // Selected first, then the rest, as text/srt resources.
        val res = Regex("<res protocolInfo=\"http-get:\\*:text/srt:\\*\">([^<]*)</res>").findAll(items).map { it.groupValues[1] }.toList()
        assertEquals(listOf("http://192.168.1.5:4000/s/tok/0/0.srt?a=1&amp;b=2", en.url), res)
        assertTrue(!items.contains("&b=") && !items.contains("\"&"))
    }

    @Test
    fun `at most MAX subtitle resources, the selected one included and never repeated`() {
        val many = (0 until 20).map { DlnaSubtitle("http://h/s/t/0/$it.srt", "es", "S$it") }
        val items = DlnaSubtitles.itemElements(DlnaSidecar(many[15], many), ::escape)
        val res = Regex("<res [^>]*>([^<]*)</res>").findAll(items).map { it.groupValues[1] }.toList()
        assertEquals(DlnaSubtitles.MAX, res.size)
        assertEquals("http://h/s/t/0/15.srt", res.first())
        assertEquals(res.size, res.toSet().size)
    }

    @Test
    fun `the Samsung header names the selected file and never carries a line break`() {
        assertEquals("CaptionInfo.sec: ${en.url}\r\n", DlnaSubtitles.captionHeader(DlnaSidecar(en, emptyList())))
        val evil = DlnaSubtitle("http://h/x.srt\r\nSet-Cookie: a=b", "es", "x")
        assertEquals("", DlnaSubtitles.captionHeader(DlnaSidecar(evil, emptyList())))
    }

    @Test
    fun `the whole DIDL-Lite stays well-formed XML with every namespace declared`() {
        val sidecar = DlnaSidecar(es, listOf(en))
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"" + DlnaSubtitles.namespaces(sidecar) + ">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>T &amp; T</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/mp4:*\"" + DlnaSubtitles.videoResAttributes(sidecar, ::escape) + ">" +
            "http://h/v.mp4</res>" + DlnaSubtitles.itemElements(sidecar, ::escape) + "</item></DIDL-Lite>"
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(didl)))
        val res = doc.getElementsByTagNameNS("urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/", "res")
        assertEquals(3, res.length)
        assertEquals("http://h/v.mp4", res.item(0).textContent)
        assertEquals(es.url, (res.item(0) as org.w3c.dom.Element).getAttributeNS(DlnaSubtitles.PV_NS, "subtitleFileUri"))
        assertEquals(es.url, doc.getElementsByTagNameNS(DlnaSubtitles.SEC_NS, "CaptionInfoEx").item(0).textContent)
    }
}
