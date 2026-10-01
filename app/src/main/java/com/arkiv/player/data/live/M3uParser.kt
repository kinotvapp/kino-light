package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * One M3U channel entry, decoded from `#EXTINF` attributes plus its URL line. [number] is the
 * `tvg-chno` when it parses to `1..MAX_CHANNEL_NUMBER`, else 0. [headers] holds only the request
 * headers this parser keeps: `User-Agent`, `Referer`, `Origin`, `Cookie`.
 */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val logo: String = "",
    val number: Int = 0,
    val group: String = "",
    val language: String = "",
    val country: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** A `#KODIPROP:inputstream.adaptive.license_key` ClearKey pair (32 lower-case hex each), or ""
     *  for no DRM. `kid:key` hex and the `{"keys":[{"kid","k"}]}` JSON (base64url) are both read.
     *  Any other license type (Widevine, etc.) is unsupported here and left out entirely. */
    val drmKeyId: String = "",
    val drmKey: String = "",
)

/**
 * [total] = valid entries seen, [entries] the first [maxEntries] of them; [skipped] = broken ones;
 * [stoppedEarly] = time budget hit. [hidden] and [refused] = valid entries the caller's `hide` /
 * `allow` filters dropped during the parse: never in [total], never spending [maxEntries].
 */
data class M3uResult(
    val entries: List<M3uEntry>, val total: Int, val skipped: Int, val stoppedEarly: Boolean = false,
    val hidden: Int = 0, val refused: Int = 0,
    /** The list's own guides, from the `#EXTM3U` header's `url-tvg` / `x-tvg-url` (comma-separated):
     *  http(s) only, deduplicated, at most [M3uParser.MAX_LIST_EPGS]. Unchecked addresses from the
     *  list itself: whoever downloads them applies its own host rules. */
    val epgUrls: List<String> = emptyList(),
)

/**
 * M3U/M3U8 channel lists as IPTV providers publish them, parsed natively (spec "Lists OR
 * individual channels"). Tolerant by design: a real list mixes broken lines, other protocols
 * and odd quoting. What can't become a playable http(s) channel is skipped and counted
 * ([M3uResult.skipped]), never a failure. Pure: download, caps in bytes and grouping are the
 * caller's (`PlaylistSource`). The kit's `sdk/live-playlist.mjs` implements the same rules;
 * both are pinned to `docs/plugins/fixtures/live`.
 */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)=(?:"([^"]*)"|(\S+))""")
    /** How many guides a list's header may name. */
    const val MAX_LIST_EPGS = 3
    private const val MAX_HEADER_VALUE = 1024
    private val HEX_16 = Regex("[0-9a-fA-F]{32}")
    private val KEPT_HEADERS = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer", "origin" to "Origin", "cookie" to "Cookie")

    /** A UTF-8 BOM is dropped. Strict UTF-8 is tried; on malformed input the whole file is read as ISO-8859-1. */
    fun decode(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, start, bytes.size - start, Charsets.ISO_8859_1)
        }
    }

    private class Info(val attrs: Map<String, String>, val title: String)

    /**
     * [file] as [decode] reads it (BOM dropped, strict UTF-8 else ISO-8859-1), streamed line by line:
     * a 20 MB list never becomes one 40 MB string on a low-end TV. UTF-8 is checked in a first pass.
     */
    fun parse(
        file: File, maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER, deadline: () -> Boolean = { false },
        hide: (M3uEntry) -> Boolean = { false }, allow: (String) -> Boolean = { true },
        /** Test seam: runs between the encoding sniff and the read. */
        beforeRead: (File) -> Unit = {},
    ): M3uResult {
        val charset = if (isStrictUtf8(file)) Charsets.UTF_8 else Charsets.ISO_8859_1
        beforeRead(file)
        val bom = if (charset == Charsets.UTF_8) "\uFEFF" else "\u00EF\u00BB\u00BF"
        return file.bufferedReader(charset).useLines { lines ->
            var first = true
            parseLines(lines.map { if (first) { first = false; it.removePrefix(bom) } else it }, maxEntries, deadline, hide, allow)
        }
    }

    private fun isStrictUtf8(file: File): Boolean {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            InputStreamReader(file.inputStream(), decoder).use { r -> val buf = CharArray(64 * 1024); while (r.read(buf) >= 0) Unit }
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /**
     * An entry [hide] matches, or whose URL [allow] refuses, is counted in [M3uResult.hidden] /
     * [M3uResult.refused] and dropped right here, so [maxEntries] only ever holds entries the
     * caller keeps (a list opening with thousands of adult or foreign-host lines loses nothing).
     */
    fun parse(
        text: String, maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER, deadline: () -> Boolean = { false },
        hide: (M3uEntry) -> Boolean = { false }, allow: (String) -> Boolean = { true },
    ): M3uResult = parseLines(text.lineSequence(), maxEntries, deadline, hide, allow)

    private fun parseLines(
        lines: Sequence<String>, maxEntries: Int, deadline: () -> Boolean,
        hide: (M3uEntry) -> Boolean, allow: (String) -> Boolean,
    ): M3uResult {
        val out = ArrayList<M3uEntry>()
        var total = 0
        var skipped = 0
        var hidden = 0
        var refused = 0
        var info: Info? = null
        var extgrp = ""
        val headers = LinkedHashMap<String, String>()
        var licenseType = ""
        var licenseKey = ""
        var epgUrls = emptyList<String>()
        var n = 0
        var seenContent = false
        for (raw in lines) {
            if (++n % 1000 == 0 && deadline()) return M3uResult(out, total, skipped + (if (info != null) 1 else 0), stoppedEarly = true, hidden, refused, epgUrls)
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> if (!seenContent) epgUrls = headerEpgs(line)
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    seenContent = true
                    if (info != null) skipped++
                    info = extinf(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extgrp = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOpt(line.substringAfter(':'), headers)
                line.startsWith("#EXTHTTP:", ignoreCase = true) -> extHttp(line.substringAfter(':'), headers)
                line.startsWith("#KODIPROP:", ignoreCase = true) -> kodiProp(line.substringAfter(':'), headers)
                    ?.let { (k, v) -> if (k == "type") licenseType = v else licenseKey = v }
                line.startsWith("#") -> Unit
                else -> {
                    seenContent = true
                    val entry = info?.let { entryOf(it, line, extgrp, headers, licenseType, licenseKey) }
                    info = null
                    extgrp = ""
                    headers.clear()
                    licenseType = ""
                    licenseKey = ""
                    when {
                        entry == null -> skipped++
                        hide(entry) -> hidden++
                        !allow(entry.url) -> refused++
                        else -> { total++; if (out.size < maxEntries) out += entry }
                    }
                }
            }
        }
        if (info != null) skipped++
        return M3uResult(out, total, skipped, hidden = hidden, refused = refused, epgUrls = epgUrls)
    }

    private fun extinf(line: String): Info {
        val body = line.substringAfter(':', "")
        var quoted = false
        var comma = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') quoted = !quoted else if (c == ',' && !quoted) { comma = i; break }
        }
        val head = if (comma >= 0) body.substring(0, comma) else body
        val attrs = HashMap<String, String>()
        ATTR.findAll(head).forEach { m -> attrs[m.groupValues[1].lowercase()] = (m.groups[2]?.value ?: m.groups[3]?.value.orEmpty()).trim() }
        return Info(attrs, if (comma >= 0) body.substring(comma + 1).trim() else "")
    }

    private fun entryOf(
        info: Info, urlLine: String, extgrp: String, pending: Map<String, String>,
        licenseType: String = "", licenseKey: String = "",
    ): M3uEntry? {
        val pipe = urlLine.indexOf('|')
        val url = (if (pipe >= 0) urlLine.substring(0, pipe) else urlLine).trim()
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val tvgName = info.attrs["tvg-name"].orEmpty()
        val name = info.title.ifBlank { tvgName }.take(200)
        if (name.isBlank()) return null
        val headers = LinkedHashMap(pending)
        if (pipe >= 0) pairs(urlLine.substring(pipe + 1), headers)
        // Only ClearKey is supported: any other license type (Widevine, PlayReady...) is left out
        // entirely rather than half-carried as an unusable DRM hint.
        val clearKey = if (licenseType == "clearkey" || licenseType == "org.w3.clearkey") clearKeyPair(licenseKey) else null
        return M3uEntry(
            name = name, url = url, tvgId = info.attrs["tvg-id"].orEmpty(), tvgName = tvgName,
            logo = info.attrs["tvg-logo"].orEmpty(),
            number = info.attrs["tvg-chno"]?.toIntOrNull()?.takeIf { it in 1..PluginLiveContract.MAX_CHANNEL_NUMBER } ?: 0,
            group = info.attrs["group-title"].orEmpty().ifBlank { extgrp },
            language = info.attrs["tvg-language"].orEmpty(), country = info.attrs["tvg-country"].orEmpty(),
            headers = headers,
            drmKeyId = clearKey?.first.orEmpty(), drmKey = clearKey?.second.orEmpty(),
        )
    }

    private fun vlcOpt(v: String, into: MutableMap<String, String>) {
        val name = when (v.substringBefore('=').trim().lowercase()) {
            "http-user-agent" -> "User-Agent"
            "http-referrer", "http-referer" -> "Referer"
            "http-origin" -> "Origin"
            else -> return
        }
        v.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }?.let { put(into, name, it) }
    }

    /** `#EXTHTTP:{"User-Agent":"…","Referer":"…"}` (OTT Navigator, TiviMate): string values of the kept headers only. */
    private fun extHttp(v: String, into: MutableMap<String, String>) {
        val json = runCatching { org.json.JSONObject(v.trim()) }.getOrNull() ?: return
        json.keys().forEach { key ->
            val name = KEPT_HEADERS[key.trim().lowercase()] ?: return@forEach
            (json.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { put(into, name, it) }
        }
    }

    /** [rawName] -> [value] into [into] when the header is one this parser keeps, by the same rules as a list line (W3U stations use it). */
    internal fun keepHeader(into: MutableMap<String, String>, rawName: String, value: String) {
        val name = KEPT_HEADERS[rawName.trim().lowercase()] ?: return
        if (value.isNotEmpty()) put(into, name, value)
    }

    /** A header value with a control character (a smuggled CR/LF) or past [MAX_HEADER_VALUE] is dropped, never sent. */
    private fun put(into: MutableMap<String, String>, name: String, value: String) {
        if (value.length <= MAX_HEADER_VALUE && value.none { Character.isISOControl(it) }) into[name] = value
    }

    private fun headerEpgs(line: String): List<String> {
        val attrs = HashMap<String, String>()
        ATTR.findAll(line.substringAfter(' ', "")).forEach { m -> attrs[m.groupValues[1].lowercase()] = m.groups[2]?.value ?: m.groups[3]?.value.orEmpty() }
        return listOf("url-tvg", "x-tvg-url").flatMap { attrs[it].orEmpty().split(',') }
            .map { it.trim() }
            .filter { val scheme = it.substringBefore("://", "").lowercase(); scheme == "http" || scheme == "https" }
            .distinct()
            .take(MAX_LIST_EPGS)
    }

    /** `kid:key` in hex, or the ClearKey JSON `{"keys":[{"kid":"<b64url>","k":"<b64url>"}]}` (its first key). Null unless both are 16 bytes. */
    private fun clearKeyPair(raw: String): Pair<String, String>? {
        val text = raw.trim()
        if (text.startsWith("{")) {
            val key = runCatching { org.json.JSONObject(text).getJSONArray("keys").getJSONObject(0) }.getOrNull() ?: return null
            val kid = b64urlHex(key.optString("kid")) ?: return null
            val k = b64urlHex(key.optString("k")) ?: return null
            return kid to k
        }
        val parts = text.split(':', limit = 2)
        if (parts.size != 2 || !HEX_16.matches(parts[0].trim()) || !HEX_16.matches(parts[1].trim())) return null
        return parts[0].trim().lowercase() to parts[1].trim().lowercase()
    }

    private fun b64urlHex(s: String): String? {
        val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(s.trim().trimEnd('=')) }.getOrNull() ?: return null
        if (bytes.size != 16) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Headers are written straight into [into]; a license type/key line is returned instead (the
     *  caller holds licenseType/licenseKey as locals, reset per entry like everything else here). */
    private fun kodiProp(v: String, into: MutableMap<String, String>): Pair<String, String>? {
        val key = v.substringBefore('=').trim().lowercase()
        return when (key) {
            "inputstream.adaptive.stream_headers", "inputstream.adaptive.common_headers" -> {
                pairs(v.substringAfter('=', ""), into)
                null
            }
            "inputstream.adaptive.license_type" -> "type" to v.substringAfter('=', "").trim().lowercase()
            "inputstream.adaptive.license_key" -> "key" to v.substringAfter('=', "").trim()
            else -> null
        }
    }

    private fun pairs(s: String, into: MutableMap<String, String>) {
        s.split('&').forEach { pair ->
            val name = KEPT_HEADERS[pair.substringBefore('=').trim().lowercase()] ?: return@forEach
            val value = runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', "").trim().replace("+", "%2B"), "UTF-8") }.getOrNull()
            if (!value.isNullOrEmpty()) put(into, name, value)
        }
    }
}
