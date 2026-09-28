package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
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
)

/** [total] = valid entries seen, [entries] the first [maxEntries] of them; [skipped] = broken ones; [stoppedEarly] = time budget hit. */
data class M3uResult(val entries: List<M3uEntry>, val total: Int, val skipped: Int, val stoppedEarly: Boolean = false)

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

    fun parse(text: String, maxEntries: Int = PluginLiveContract.MAX_CHANNELS_PER_PROVIDER, deadline: () -> Boolean = { false }): M3uResult {
        val out = ArrayList<M3uEntry>()
        var total = 0
        var skipped = 0
        var info: Info? = null
        var extgrp = ""
        val headers = LinkedHashMap<String, String>()
        var n = 0
        for (raw in text.lineSequence()) {
            if (++n % 1000 == 0 && deadline()) return M3uResult(out, total, skipped + (if (info != null) 1 else 0), stoppedEarly = true)
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    if (info != null) skipped++
                    info = extinf(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extgrp = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOpt(line.substringAfter(':'), headers)
                line.startsWith("#KODIPROP:", ignoreCase = true) -> kodiProp(line.substringAfter(':'), headers)
                line.startsWith("#") -> Unit
                else -> {
                    val entry = info?.let { entryOf(it, line, extgrp, headers) }
                    info = null
                    extgrp = ""
                    headers.clear()
                    if (entry == null) skipped++
                    else { total++; if (out.size < maxEntries) out += entry }
                }
            }
        }
        if (info != null) skipped++
        return M3uResult(out, total, skipped)
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

    private fun entryOf(info: Info, urlLine: String, extgrp: String, pending: Map<String, String>): M3uEntry? {
        val pipe = urlLine.indexOf('|')
        val url = (if (pipe >= 0) urlLine.substring(0, pipe) else urlLine).trim()
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val tvgName = info.attrs["tvg-name"].orEmpty()
        val name = info.title.ifBlank { tvgName }.take(200)
        if (name.isBlank()) return null
        val headers = LinkedHashMap(pending)
        if (pipe >= 0) pairs(urlLine.substring(pipe + 1), headers)
        return M3uEntry(
            name = name, url = url, tvgId = info.attrs["tvg-id"].orEmpty(), tvgName = tvgName,
            logo = info.attrs["tvg-logo"].orEmpty(),
            number = info.attrs["tvg-chno"]?.toIntOrNull()?.takeIf { it in 1..PluginLiveContract.MAX_CHANNEL_NUMBER } ?: 0,
            group = info.attrs["group-title"].orEmpty().ifBlank { extgrp },
            language = info.attrs["tvg-language"].orEmpty(), country = info.attrs["tvg-country"].orEmpty(),
            headers = headers,
        )
    }

    private fun vlcOpt(v: String, into: MutableMap<String, String>) {
        val name = when (v.substringBefore('=').trim().lowercase()) {
            "http-user-agent" -> "User-Agent"
            "http-referrer", "http-referer" -> "Referer"
            "http-origin" -> "Origin"
            else -> return
        }
        v.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }?.let { into[name] = it }
    }

    private fun kodiProp(v: String, into: MutableMap<String, String>) {
        val key = v.substringBefore('=').trim().lowercase()
        if (key == "inputstream.adaptive.stream_headers" || key == "inputstream.adaptive.common_headers") pairs(v.substringAfter('=', ""), into)
    }

    private fun pairs(s: String, into: MutableMap<String, String>) {
        s.split('&').forEach { pair ->
            val name = KEPT_HEADERS[pair.substringBefore('=').trim().lowercase()] ?: return@forEach
            val value = runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', "").trim().replace("+", "%2B"), "UTF-8") }.getOrNull()
            if (!value.isNullOrEmpty()) into[name] = value
        }
    }
}
