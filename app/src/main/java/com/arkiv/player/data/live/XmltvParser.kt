package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler

/** One programme slot. [description] defaults to empty: not every guide fills it in. */
data class XmltvProgramme(val channelId: String, val title: String, val startMs: Long, val endMs: Long, val description: String = "")

/**
 * A parsed EPG window. [displayNames] holds only the wanted channels' names, in document order.
 * [programmes] is keyed the same way, each list sorted by start -- the earliest [maxPerChannel]
 * entries in the window survive the cap, never just the first ones a document happens to list
 * first. [truncated] is true when a byte cap, a time budget, or a broken/short/corrupt read cut
 * the parse short: what was read up to that point is kept. A document refused outright (one
 * declaring a DOCTYPE, however it's shaped) comes back as an empty, non-truncated guide instead:
 * refusing it is deliberate, not a partial read.
 */
data class XmltvGuide(val displayNames: Map<String, List<String>>, val programmes: Map<String, List<XmltvProgramme>>, val truncated: Boolean = false)

/**
 * XMLTV guides as IPTV providers publish them (plain or gzip), parsed natively with SAX. Bounded
 * and tolerant by design: a real guide is megabytes, sometimes gzip-bombed, sometimes cut off
 * mid-tag by a flaky download. What can't be trusted is dropped, never a crash or a hang.
 *
 * Any `<!DOCTYPE` is refused, full stop -- never just the ones an 8 KB head scan happens to see.
 * That scan stays only as a fast path (most guides never declare a doctype, and it skips the SAX
 * setup for them). The real defence is structural, so it holds regardless of encoding (a UTF-16
 * document defeats a byte-literal scan) or of where in the document the doctype sits (padded past
 * 8 KB with comments) or of whether the entity is internal, external or a parameter entity
 * (billion-laughs needs none of those to be external): a [LexicalHandler] whose `startDTD` fires,
 * and throws, the moment the parser itself reports a doctype -- before it can fetch an external
 * subset or expand anything. `resolveEntity` is also overridden to hand back nothing, and on the
 * JVM `disallow-doctype-decl` plus secure processing are set too. Every feature toggle is wrapped
 * in `runCatching`: Android's Expat-backed parser doesn't recognise all of them, but it does
 * dispatch to the [LexicalHandler], which is what actually holds there.
 *
 * The kit's `sdk` mirrors these same rules, both pinned to `docs/plugins/fixtures/live`.
 *
 * One more encoding wrinkle, Android-specific: Expat itself only speaks UTF-8, UTF-16,
 * ISO-8859-1 and US-ASCII natively. A guide declaring anything else (`windows-1252` is common in
 * Latin-American XMLTV) is sniffed (BOM first, then the `encoding="..."` in the XML declaration,
 * both read from the same head bytes) and, when it names something Expat can't read itself,
 * transcoded up front with a `java.nio.charset.Charset` into a fresh UTF-8 stream with the
 * declaration rewritten to match -- re-capped at the same [PluginLiveContract.MAX_EPG_BYTES], so
 * the byte cap and the doctype refusal both still apply to what the parser actually reads. An
 * unrecognised charset name falls back to ISO-8859-1 (never a failure) rather than handing Expat
 * a declaration it can't honour.
 */
object XmltvParser {
    private val TIME = Regex("""^(\d{14}|\d{12})\s*([+-]\d{4})?""")
    private val ENCODING_DECL = Regex("""encoding\s*=\s*["']([^"'?>]+)["']""", RegexOption.IGNORE_CASE)
    private val NATIVE_ENCODINGS = setOf("utf-8", "utf8", "utf-16", "utf-16be", "utf-16le", "iso-8859-1", "iso8859-1", "latin1", "us-ascii", "ascii")
    private const val HEAD_SCAN_BYTES = 8192
    private const val MAX_TEXT_CHARS = 2000
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_DESC_CHARS = 2000
    private const val CHECK_EVERY = 500
    private const val LEXICAL_HANDLER_PROPERTY = "http://xml.org/sax/properties/lexical-handler"

    /**
     * Gzip is detected by its magic bytes (`1F 8B`); anything else is read as plain XML. A
     * corrupt gzip header (a bad method byte, or fewer bytes than a header needs) never throws
     * here: [GZIPInputStream]'s own header check is caught and this hands back an empty stream
     * instead, so the failure surfaces inside [parse]'s try, already bounded and reported as
     * truncated, rather than crashing the caller before parsing even starts.
     */
    fun open(bytes: ByteArray): InputStream {
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return runCatching { GZIPInputStream(ByteArrayInputStream(bytes)) }
                .getOrElse { ByteArrayInputStream(ByteArray(0)) }
        }
        return ByteArrayInputStream(bytes)
    }

    /** [open]'s rules for a saved guide, streamed from disk instead of held in memory. The caller closes it. */
    fun open(file: java.io.File): InputStream {
        val head = ByteArray(2)
        val n = file.inputStream().use { it.read(head) }
        val raw = java.io.BufferedInputStream(file.inputStream(), 64 * 1024)
        if (n == 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
            return runCatching { GZIPInputStream(raw) as InputStream }
                .getOrElse { raw.close(); ByteArrayInputStream(ByteArray(0)) }
        }
        return raw
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
        var buffered = BufferedInputStream(CappedInput(input, maxBytes), 64 * 1024)

        val names = LinkedHashMap<String, List<String>>()
        val wanted = HashMap<String, Boolean>()
        // Sorted by start per channel, and capped AS IT FILLS: past the cap, a new, earlier
        // start evicts the current latest one, so the survivors are always the earliest in the
        // window, never just the first ones a document happened to list first.
        val progs = LinkedHashMap<String, TreeMap<Long, XmltvProgramme>>()
        val knownChannels = HashSet<String>()
        var truncated = false
        var refused = false

        // Only meaningful when wantedIds == null (accept-everything mode): bounds the distinct
        // channel ids a hostile guide can make this hold onto. When wantedIds is given, the
        // caller already bounds the possible ids.
        fun admitNewChannel(id: String): Boolean {
            if (id in knownChannels) return true
            if (knownChannels.size >= PluginLiveContract.MAX_CHANNELS_PER_PROVIDER) return false
            knownChannels += id
            return true
        }

        val handler = object : DefaultHandler(), LexicalHandler {
            var channel: String? = null
            val displayNames = ArrayList<String>()
            var pStart: Long? = null
            var pEnd: Long? = null
            var pChannel = ""
            var title: String? = null
            var desc: String? = null
            val text = StringBuilder()
            var inText = false
            var elementCount = 0
            var programmeCount = 0
            var channelCount = 0

            fun checkDeadline() {
                if (deadline()) { truncated = true; throw Stop() }
            }

            // Never resolve an entity's content: even if a doctype somehow got past startDTD,
            // this hands back nothing rather than following a SYSTEM/PUBLIC id anywhere.
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))

            // The actual defence: fires the instant the parser recognises a doctype, before it
            // can read an internal subset (billion-laughs needs no external fetch) or an
            // external one (XXE) -- regardless of the document's encoding or how it's padded.
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw Refused() }
            override fun endDTD() {}
            override fun startEntity(name: String?) {}
            override fun endEntity(name: String?) {}
            override fun startCDATA() {}
            override fun endCDATA() {}
            override fun comment(ch: CharArray?, start: Int, length: Int) {}

            override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                if (++elementCount % CHECK_EVERY == 0) checkDeadline()
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
                        channel = null
                        if (++channelCount % CHECK_EVERY == 0) checkDeadline()
                        if (wantedIds == null && !admitNewChannel(id)) return@let
                        val ok = wantedIds == null || id in wantedIds || displayNames.any { normaliseName(it) in wantedNames }
                        wanted[id] = ok
                        if (ok) names[id] = displayNames.toList()
                    }
                    "programme" -> {
                        if (++programmeCount % CHECK_EVERY == 0) checkDeadline()
                        val s = pStart
                        val e = pEnd
                        val t = title
                        val ok = when {
                            wanted.containsKey(pChannel) -> wanted.getValue(pChannel)
                            wantedIds != null -> pChannel in wantedIds
                            else -> admitNewChannel(pChannel)
                        }
                        if (ok && s != null && e != null && e > s && e > fromMs && s < toMs && !t.isNullOrBlank()) {
                            val perChannel = progs.getOrPut(pChannel) { TreeMap() }
                            if (!perChannel.containsKey(s)) {
                                val entry = XmltvProgramme(pChannel, t.take(MAX_TITLE_CHARS), s, e, desc.orEmpty().take(MAX_DESC_CHARS))
                                if (perChannel.size < maxPerChannel) {
                                    perChannel[s] = entry
                                } else {
                                    val latestKey = perChannel.lastKey()
                                    if (s < latestKey) { perChannel.remove(latestKey); perChannel[s] = entry }
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            // Expat (Android) only reads UTF-8/UTF-16/ISO-8859-1/US-ASCII itself; anything else
            // declared is transcoded up front into a fresh, re-capped UTF-8 stream (see the class
            // KDoc). A no-op on the common case (no declaration, or one of those four already).
            buffered = prepareEncoding(buffered, maxBytes)

            // A fast path only: most guides never declare a doctype, and this skips building
            // the SAX parser for them. It is not the defence (see the class KDoc) -- moved
            // inside this try (a truncated read here is as valid a "keep what was read" case as
            // one hit mid-parse) rather than left ahead of it where it used to throw uncaught.
            if (declaresEntity(buffered)) throw Refused()

            val parser = javax.xml.parsers.SAXParserFactory.newInstance().let { factory ->
                listOf(
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                    "http://apache.org/xml/features/disallow-doctype-decl" to true,
                ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
                runCatching { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                factory.newSAXParser()
            }
            runCatching { parser.xmlReader.setProperty(LEXICAL_HANDLER_PROPERTY, handler) }
            parser.parse(InputSource(buffered), handler)
        } catch (e: Refused) {
            refused = true
        } catch (e: Stop) {
            truncated = true
        } catch (e: SAXException) {
            when {
                e.exception is Refused || e.cause is Refused -> refused = true
                // Some parsers (Xerces included, with disallow-doctype-decl) refuse a doctype
                // with their own fatal error instead of ever reaching startDTD.
                e.message?.contains("DOCTYPE", ignoreCase = true) == true -> refused = true
                e.exception is Stop || e.cause is Stop -> truncated = true
                else -> truncated = true // a broken tail: keep what parsed, but say so
            }
        } catch (e: IOException) {
            truncated = true // a truncated or corrupt (gzip) read: same idea, one layer down
        }

        if (refused) return XmltvGuide(emptyMap(), emptyMap())
        return XmltvGuide(names, progs.mapValues { (_, m) -> m.values.toList() }, truncated)
    }

    /**
     * Transcodes to UTF-8, re-capped, when the declared encoding is one Expat can't read itself.
     * A no-op (the same [buffered], untouched) whenever nothing was declared, or a BOM or the XML
     * declaration names UTF-8, UTF-16 (either byte order) or ISO-8859-1/US-ASCII already.
     */
    private fun prepareEncoding(buffered: BufferedInputStream, maxBytes: Long): BufferedInputStream {
        buffered.mark(HEAD_SCAN_BYTES)
        val head = ByteArray(HEAD_SCAN_BYTES)
        var read = 0
        while (read < head.size) {
            val n = buffered.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()

        val declared = sniffEncoding(head, read) ?: return buffered
        if (declared.lowercase() in NATIVE_ENCODINGS) return buffered

        val charset = runCatching { java.nio.charset.Charset.forName(declared) }.getOrElse { Charsets.ISO_8859_1 }
        val text = String(buffered.readBytes(), charset) // bounded already: buffered wraps a CappedInput
        val rewritten = ENCODING_DECL.replace(text) { "encoding=\"UTF-8\"" }
        val utf8Bytes = rewritten.toByteArray(Charsets.UTF_8)
        return BufferedInputStream(CappedInput(ByteArrayInputStream(utf8Bytes), maxBytes), 64 * 1024)
    }

    /** A BOM first (authoritative when present), else the `encoding="..."` the XML declaration names. */
    private fun sniffEncoding(head: ByteArray, len: Int): String? {
        if (len >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return "UTF-8"
        if (len >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return "UTF-16BE"
        if (len >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return "UTF-16LE"
        // ASCII bytes (the declaration itself) decode the same in UTF-8, ISO-8859-1 and every
        // single-byte encoding a guide is likely to name, so this is safe ahead of knowing which
        // one it actually is.
        val text = String(head, 0, len, Charsets.ISO_8859_1)
        return ENCODING_DECL.find(text)?.groupValues?.get(1)
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
        // ASCII bytes (this literal) decode the same in UTF-8 and ISO-8859-1, so this is a safe
        // guess ahead of knowing the document's real declared encoding -- though not for UTF-16,
        // which is exactly why this is a fast path and not the defence.
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

    /** A silent, stackless signal that a cap was hit: unwinds the SAX parse, keeping what was read. */
    private class Stop : RuntimeException(null, null, false, false)

    /** A silent, stackless signal that the document declared a doctype and is refused outright. */
    private class Refused : RuntimeException(null, null, false, false)
}
