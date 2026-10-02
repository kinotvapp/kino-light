package com.arkiv.player.data.live

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a pasted, opened or downloaded list turns out to be. Told by the content, never by the extension. */
enum class ListFormat { M3U, W3U, XSPF, PLAIN, XTREAM_JSON, XMLTV, WEB_PAGE, UNKNOWN }

/**
 * The formats "Mis canales" reads besides M3U and W3U: XSPF playlists (VLC, Perfect Player) and plain
 * text lists of addresses, both turned into the extended M3U every other format is saved as, so the
 * parse, grouping, codes and caps after the download stay one path. Pure and bounded; XSPF is read with
 * plain pattern matching, never an XML parser, so a hostile document has no entities or doctype to abuse.
 */
object ListFormats {
    private const val MAX_TRACKS = 20_000
    private val URL_LINE = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
    private val NAMED_LINE = Regex("""^(.{1,200}?)\s*[,;\t]\s*(https?://\S+)$""", RegexOption.IGNORE_CASE)
    private val TRACK = Regex("""<track\b[^>]*>(.*?)</track\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val VLC_OPTION = Regex("""<vlc:option>\s*([^<]*?)\s*</vlc:option>""", RegexOption.IGNORE_CASE)

    fun detect(text: String): ListFormat {
        val t = text.trimStart('﻿', ' ', '\t', '\r', '\n')
        if (t.isEmpty()) return ListFormat.UNKNOWN
        val head = t.take(4096).lowercase()
        if (head.startsWith("#extm3u") || "#extinf" in t.take(65_536).lowercase()) return ListFormat.M3U
        if (LenientJson.looksLikeJson(t)) return if (looksXtream(t)) ListFormat.XTREAM_JSON else ListFormat.W3U
        if (head.startsWith("<")) return when {
            "<playlist" in head && "<tracklist" in t.take(65_536).lowercase() -> ListFormat.XSPF
            "<tv" in head && ("<channel" in t.take(65_536).lowercase() || "<programme" in t.take(65_536).lowercase()) -> ListFormat.XMLTV
            else -> ListFormat.WEB_PAGE
        }
        if (plain(t, 1).isNotEmpty() && plainOnly(t)) return ListFormat.PLAIN
        return ListFormat.UNKNOWN
    }

    /** `player_api.php` without an action answers `{"user_info":{...},"server_info":{...}}`. */
    private fun looksXtream(text: String): Boolean {
        val m = try { LenientJson.parse(text, maxNodes = 5_000) as? Map<*, *> } catch (e: LenientJson.Invalid) { null } ?: return false
        return m["user_info"] is Map<*, *>
    }

    /** Every non-blank, non-comment line is an address (or `Name,address`). */
    private fun plainOnly(text: String): Boolean {
        var any = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            if (!URL_LINE.matches(line) && !NAMED_LINE.matches(line)) return false
            any = true
        }
        return any
    }

    /** Up to [max] entries of a plain text list: `address` or `Name,address` per line. A bare address is named after its server and last path piece. */
    fun plain(text: String, max: Int = MAX_TRACKS): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        for (raw in text.lineSequence()) {
            if (out.size >= max) break
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val named = NAMED_LINE.matchEntire(line)
            val (name, url) = when {
                named != null -> named.groupValues[1].trim() to named.groupValues[2]
                URL_LINE.matches(line) -> nameOf(line) to line
                else -> continue
            }
            out += M3uEntry(name = name.take(200), url = url)
        }
        return out
    }

    private fun nameOf(url: String): String {
        val noQuery = url.substringBefore('|').substringBefore('?').substringAfter("://")
        val host = noQuery.substringBefore('/').substringBefore(':')
        val last = noQuery.substringAfter('/', "").trimEnd('/').substringAfterLast('/').substringBeforeLast('.')
        return if (last.isNotEmpty()) "$host · $last" else host
    }

    /** The tracks of an XSPF document as entries: `location` (http/https only), `title` (or `creator`), `image`, `album` as group, and the VLC `http-user-agent` / `http-referrer` options. */
    fun xspf(text: String, max: Int = MAX_TRACKS): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        for (m in TRACK.findAll(text)) {
            if (out.size >= max) break
            val body = m.groupValues[1]
            val url = tag(body, "location").takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: continue
            val name = tag(body, "title").ifEmpty { tag(body, "creator") }.ifEmpty { nameOf(url) }
            val headers = LinkedHashMap<String, String>()
            VLC_OPTION.findAll(body).forEach { o ->
                val v = unescape(o.groupValues[1])
                M3uParser.keepHeader(
                    headers,
                    when (v.substringBefore('=').trim().lowercase()) { "http-user-agent" -> "user-agent"; "http-referrer", "http-referer" -> "referer"; else -> "" },
                    v.substringAfter('=', "").trim(),
                )
            }
            out += M3uEntry(
                name = name.take(200), url = url, logo = tag(body, "image").takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty(),
                group = tag(body, "album").take(80), headers = headers,
            )
        }
        return out
    }

    private fun tag(body: String, name: String): String {
        val m = Regex("""<$name\b[^>]*>(.*?)</$name\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(body) ?: return ""
        return unescape(m.groupValues[1]).map { if (Character.isISOControl(it)) ' ' else it }.joinToString("").trim()
    }

    /** The five XML entities, numeric references and CDATA; nothing else is ever expanded. */
    internal fun unescape(raw: String): String {
        val noCdata = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL).replace(raw) { it.groupValues[1].replace("&", "&amp;").replace("<", "&lt;") }
        return Regex("""&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos);""").replace(noCdata) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && code in 1..0x10FFFF && !Character.isISOControl(code)) String(Character.toChars(code)) else ""
                }
            }
        }
    }

    /** [text] of an [XSPF][ListFormat.XSPF] or [PLAIN][ListFormat.PLAIN] list as an extended M3U; null for any other format. */
    fun toM3u(text: String, format: ListFormat = detect(text)): String? {
        val entries = when (format) {
            ListFormat.XSPF -> xspf(text)
            ListFormat.PLAIN -> plain(text)
            else -> return null
        }
        return StringBuilder().also { M3uWriter.write(entries, emptyList(), it) }.toString()
    }

    /** Gzip is told by its magic bytes (`1f 8b`), never by a `.gz` name. */
    fun isGzip(head: ByteArray) = head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
}

/**
 * The own provider's download path for the formats that are not M3U/W3U: a gzip answer (told by its magic
 * bytes) is inflated, within the caller's byte cap; an XSPF or a plain list of addresses is rewritten as
 * extended M3U. An M3U, a W3U (JSON) or a guide is saved as downloaded, after reading only its first bytes.
 */
internal class ListFormatFetcher(private val inner: LivePlaylistFetcher) : LivePlaylistFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray = inner.fetch(url, headers, maxBytes)

    override suspend fun fetchTo(url: String, headers: Map<String, String>, maxBytes: Long, into: File) {
        inner.fetchTo(url, headers, maxBytes, into)
        try {
            withContext(Dispatchers.IO) { convert(into, maxBytes) }
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }

    private fun convert(file: File, maxBytes: Long) {
        val head = file.inputStream().use { it.readNBytes(HEAD) }
        if (ListFormats.isGzip(head)) {
            val tmp = File(file.parentFile, "${file.name}.gunzip")
            inflate(file, tmp, maxBytes)
            if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("could not replace the saved copy") }
        }
        val h = M3uParser.decode(file.inputStream().use { it.readNBytes(HEAD) }).trimStart().lowercase()
        // Common cases are decided by the first bytes alone: no list is read whole to be told it is an M3U.
        if (h.startsWith("#extm3u") || h.startsWith("{") || h.startsWith("[") || "#extinf" in h) return
        if (file.length() > MAX_CONVERT_BYTES) return
        val text = M3uParser.decode(file.readBytes())
        val m3u = ListFormats.toM3u(text) ?: return
        file.writeText(m3u)
    }

    private fun inflate(from: File, to: File, max: Long) {
        try {
            GZIPInputStream(from.inputStream().buffered()).use { zin ->
                to.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > max) throw PlaylistTooLargeException(max / (1024 * 1024))
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Throwable) {
            to.delete()
            throw e
        }
    }

    private companion object {
        const val HEAD = 4096
        /** XSPF and plain lists are read whole: kept well under the M3U cap. */
        const val MAX_CONVERT_BYTES = 8L * 1024 * 1024
    }
}
