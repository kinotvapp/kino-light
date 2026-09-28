package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** One programme slot. [description] defaults to empty: not every guide fills it in. */
data class XmltvProgramme(val channelId: String, val title: String, val startMs: Long, val endMs: Long, val description: String = "")

/**
 * A parsed EPG window. [displayNames] holds only the wanted channels' names, in document order.
 * [programmes] is keyed the same way, each list sorted by start. [truncated] is true when a byte
 * or time cap cut the read short: what was read up to that point is kept.
 */
data class XmltvGuide(val displayNames: Map<String, List<String>>, val programmes: Map<String, List<XmltvProgramme>>, val truncated: Boolean = false)

/**
 * XMLTV guides as IPTV providers publish them (plain or gzip), parsed natively with SAX. Bounded
 * and tolerant by design: a real guide is megabytes, sometimes gzip-bombed, sometimes cut off
 * mid-tag by a flaky download. What can't be trusted is dropped, never a crash or a hang -- a
 * document declaring `<!ENTITY` is refused outright (entity expansion buys a guide nothing, and
 * costs an XXE or a billion-laughs). The kit's `sdk` mirrors these same rules, both pinned to
 * `docs/plugins/fixtures/live`.
 */
object XmltvParser {
    private val TIME = Regex("""^(\d{14}|\d{12})\s*([+-]\d{4})?""")
    private const val HEAD_SCAN_BYTES = 8192
    private const val MAX_TEXT_CHARS = 2000
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_DESC_CHARS = 2000

    /** Gzip is detected by its magic bytes (`1F 8B`); anything else is read as plain XML. */
    fun open(bytes: ByteArray): InputStream {
        val raw = ByteArrayInputStream(bytes)
        return if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) GZIPInputStream(raw) else raw
    }

    /** `"yyyyMMddHHmmss[ ±HHMM]"`, 12-digit (no seconds) too. No offset means UTC. Null on anything else. */
    fun parseTime(value: String): Long? {
        val m = TIME.find(value.trim()) ?: return null
        val digits = m.groupValues[1].padEnd(14, '0')
        val offset = m.groupValues[2].ifEmpty { "+0000" }
        return runCatching {
            LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .toInstant(ZoneOffset.of(offset.substring(0, 3) + ":" + offset.substring(3)))
                .toEpochMilli()
        }.getOrNull()
    }

    /** NFD, strip combining marks, lowercase, keep only `[a-z0-9]` -- so accents and case never split a match. */
    fun normaliseName(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }

    fun parse(
        input: InputStream,
        fromMs: Long,
        toMs: Long,
        wantedIds: Set<String>? = null,
        wantedNames: Set<String> = emptySet(),
        maxBytes: Long = PluginLiveContract.MAX_EPG_BYTES,
        maxPerChannel: Int = PluginLiveContract.MAX_GUIDE_ENTRIES_PER_CHANNEL,
        deadline: () -> Boolean = { false },
    ): XmltvGuide {
        val buffered = BufferedInputStream(CappedInput(input, maxBytes), 64 * 1024)

        // Entities are never needed for a guide: refuse the whole document rather than let a
        // parser resolve one (XXE) or expand one into an OOM (billion laughs).
        if (declaresEntity(buffered)) return XmltvGuide(emptyMap(), emptyMap())

        val names = LinkedHashMap<String, List<String>>()
        val wanted = HashMap<String, Boolean>()
        val progs = LinkedHashMap<String, MutableList<XmltvProgramme>>()
        var truncated = false

        val handler = object : DefaultHandler() {
            var channel: String? = null
            val displayNames = ArrayList<String>()
            var pStart: Long? = null
            var pEnd: Long? = null
            var pChannel = ""
            var title: String? = null
            var desc: String? = null
            val text = StringBuilder()
            var inText = false
            var count = 0

            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                when (qName) {
                    "channel" -> { channel = attrs.getValue("id"); displayNames.clear() }
                    "programme" -> {
                        pStart = attrs.getValue("start")?.let(::parseTime)
                        pEnd = attrs.getValue("stop")?.let(::parseTime)
                        pChannel = attrs.getValue("channel").orEmpty()
                        title = null
                        desc = null
                    }
                    "display-name", "title", "desc" -> { text.setLength(0); inText = true }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText && text.length < MAX_TEXT_CHARS) text.append(ch, start, minOf(length, MAX_TEXT_CHARS - text.length))
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (qName) {
                    "display-name" -> {
                        inText = false
                        if (channel != null) displayNames += text.toString().trim()
                    }
                    "title" -> { inText = false; if (title == null) title = text.toString().trim() }
                    "desc" -> { inText = false; if (desc == null) desc = text.toString().trim() }
                    "channel" -> channel?.let { id ->
                        val ok = wantedIds == null || id in wantedIds || displayNames.any { normaliseName(it) in wantedNames }
                        wanted[id] = ok
                        if (ok) names[id] = displayNames.toList()
                        channel = null
                    }
                    "programme" -> {
                        if (++count % 500 == 0 && deadline()) { truncated = true; throw Stop() }
                        val s = pStart
                        val e = pEnd
                        val t = title
                        val ok = wanted[pChannel] ?: (wantedIds == null || pChannel in wantedIds)
                        if (ok && s != null && e != null && e > s && e > fromMs && s < toMs && !t.isNullOrBlank()) {
                            val list = progs.getOrPut(pChannel) { ArrayList() }
                            if (list.size < maxPerChannel && list.none { it.startMs == s }) {
                                list += XmltvProgramme(pChannel, t.take(MAX_TITLE_CHARS), s, e, desc.orEmpty().take(MAX_DESC_CHARS))
                            }
                        }
                    }
                }
            }
        }

        try {
            val factory = javax.xml.parsers.SAXParserFactory.newInstance()
            listOf(
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
            factory.newSAXParser().parse(InputSource(buffered), handler)
        } catch (e: Stop) {
            truncated = true
        } catch (e: SAXException) {
            // A Stop raised from inside the handler can reach here re-wrapped by the parser.
            if (e.exception is Stop || e.cause is Stop) truncated = true
            // Otherwise: a broken tail (a cut-off download) keeps whatever parsed before it.
        } catch (e: IOException) {
            // Same idea, one layer down (e.g. a corrupt gzip stream): keep what was read.
        }

        return XmltvGuide(names, progs.mapValues { (_, list) -> list.sortedBy { it.startMs } }, truncated)
    }

    /** Reads up to [HEAD_SCAN_BYTES] under `mark`/`reset` so the parse proper starts from byte 0. */
    private fun declaresEntity(buffered: BufferedInputStream): Boolean {
        buffered.mark(HEAD_SCAN_BYTES)
        val head = ByteArray(HEAD_SCAN_BYTES)
        var read = 0
        while (read < head.size) {
            val n = buffered.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        // ASCII bytes (this literal) decode the same in UTF-8 and ISO-8859-1, so this is safe
        // ahead of knowing the document's real declared encoding.
        return "<!ENTITY" in String(head, 0, read, Charsets.ISO_8859_1)
    }

    /** Counts bytes read after decompression; throws [Stop] once past [max], gzip bombs included. */
    private class CappedInput(private val inner: InputStream, private val max: Long) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) { count++; if (count > max) throw Stop() }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) { count += n; if (count > max) throw Stop() }
            return n
        }
    }

    /** A silent, stackless signal to unwind out of the SAX parse early; never a real error. */
    private class Stop : RuntimeException(null, null, false, false)
}
