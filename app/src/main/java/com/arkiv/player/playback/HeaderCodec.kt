package com.arkiv.player.playback

import org.json.JSONObject
import java.util.Base64

/**
 * Packs a map of headers into a URL.
 *
 * Needed because libVLC could only send `Referer` and `User-Agent`: any other header (magis's
 * `Content-Auth`/`Content-License`) had to travel to the local proxy through the only channel VLC
 * respected, the URL itself.
 *
 * URL-safe Base64 without padding: the value travels as a query param and must not carry `+`,
 * `/` or `=`. Uses `java.util.Base64` (API 26+, and minSdk is 26) and not `android.util.Base64`
 * because the latter is a stub in JVM tests and returns null.
 */
object HeaderCodec {

    fun encode(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        val json = JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } }.toString()
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** Never throws: a tampered or old URL simply contributes no headers. */
    fun decode(blob: String): Map<String, String> {
        if (blob.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(blob), Charsets.UTF_8))
            json.keys().asSequence().associateWith { json.optString(it) }
        }.getOrDefault(emptyMap())
    }
}
