package com.arkiv.player.data.live

import com.arkiv.player.data.db.OwnListPartEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class OwnPastedFormat { M3U, W3U }

sealed interface OwnPastedCheck {
    /** [text] is the normalised list to store, [digest] its sha256, [summary] what the person is told it holds. */
    data class Ok(val text: String, val digest: String, val format: OwnPastedFormat, val summary: String) : OwnPastedCheck
    data class Refused(val message: String) : OwnPastedCheck
}

/**
 * "Mis canales" lists with no address: the text the person PASTED or the file they OPENED (M3U or
 * Wiseplay W3U; the format is told by the content, never by the extension).
 *
 * - The same rules as a list downloaded from an address: at most [MAX_BYTES] once normalised (BOM
 *   gone, `\n` line ends, trimmed), channels and nested lists only on public http(s) hosts
 *   ([OwnSourceValidator.checkUrl]: what the list names never reaches the home network), headers
 *   through the parsers' allowlist, the provider's channel cap. Nested W3U lists are fetched by
 *   [W3uPlaylistFetcher] with its usual caps, exactly as for a downloaded W3U.
 * - The source row's url is [urlFor] (`kino-list:<id>`): it is no http address, so an older build
 *   refuses the synced row instead of storing a list it cannot read. [PastedListFetcher] answers
 *   that url with the stored text, so everything after the "download" is the URL-list path unchanged.
 * - The text travels between linked devices as [OwnListPartEntity] rows: gzip, base64url (nothing
 *   JSON escapes), [PART_CHARS] characters each, so a row fits the 64 KB companion message. A text
 *   is used only once all its parts are here and its sha256 matches ([join]).
 */
object OwnPastedList {
    const val SCHEME = "kino-list:"
    /** The cap of a pasted or opened list, once normalised: more is a list for an address. */
    const val MAX_BYTES = 2 * 1024 * 1024
    /** Characters of base64 per synced part: with its JSON fields, well under the 64 KB message. */
    const val PART_CHARS = 40_000
    /** Enough for [MAX_BYTES] that gzip cannot shrink (~70 parts); anything above is refused. */
    const val MAX_PARTS = 96
    val EXTENSIONS = setOf("m3u", "m3u8", "w3u", "json")

    const val EMPTY = "No hay nada para pegar: copia primero el texto de la lista (M3U o W3U) y vuelve a intentarlo"
    const val TOO_LARGE = "La lista pesa más de 2 MB y no se puede pegar ni abrir como archivo. Súbela a internet y agrégala con su dirección."
    const val WRONG_FILE = "Ese archivo no es una lista: elige uno .m3u, .m3u8, .w3u o .json"
    const val UNREADABLE_FILE = "No se pudo leer el archivo. Intenta con otro."

    private val DIGEST = Regex("[0-9a-f]{64}")
    private val BASE64URL = Regex("[A-Za-z0-9_-]+")

    fun urlFor(id: String) = SCHEME + id
    fun isPasted(url: String) = url.startsWith(SCHEME)
    fun isDigest(s: String?) = s != null && DIGEST.matches(s)

    fun normalize(raw: String): String = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()

    fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun allowed(url: String) = OwnSourceValidator.checkUrl(url) is OwnUrlCheck.Ok

    /** What the person pasted. */
    fun check(raw: String): OwnPastedCheck {
        // A clipboard far over the cap is refused before a normalised copy of it is made.
        if (raw.length > MAX_BYTES * 2) return OwnPastedCheck.Refused(TOO_LARGE)
        val text = normalize(raw)
        if (text.isEmpty()) return OwnPastedCheck.Refused(EMPTY)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return OwnPastedCheck.Refused(TOO_LARGE)
        val head = text.take(64).lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml")) {
            return OwnPastedCheck.Refused("Eso es una página web, no una lista M3U o W3U")
        }
        return if (LenientJson.looksLikeJson(text)) w3u(text) else m3u(text)
    }

    /**
     * A file the person opened. [name] = its display name (null when the picker gives none);
     * [truncated] = it had more than [MAX_BYTES] bytes (the reader stops there).
     */
    fun checkFile(name: String?, bytes: ByteArray, truncated: Boolean): OwnPastedCheck {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (name != null && '.' in name && ext !in EXTENSIONS) return OwnPastedCheck.Refused(WRONG_FILE)
        if (truncated || bytes.size > MAX_BYTES) return OwnPastedCheck.Refused(TOO_LARGE)
        return check(M3uParser.decode(bytes))
    }

    /** A list name from the file's name: "Fútbol.m3u8" -> "Fútbol". */
    fun nameFromFile(name: String?): String =
        name?.substringBeforeLast('.')?.trim()?.take(OwnSourceValidator.MAX_NAME).orEmpty()

    private fun m3u(text: String): OwnPastedCheck {
        val n = M3uParser.parse(text, allow = ::allowed).total
        if (n == 0) return OwnPastedCheck.Refused("No parece una lista M3U ni W3U: no encontré canales")
        return OwnPastedCheck.Ok(text, digest(text), OwnPastedFormat.M3U, "Encontré ${channels(n)}")
    }

    private fun w3u(text: String): OwnPastedCheck {
        val list = W3uParser.parse(text)
            ?: return OwnPastedCheck.Refused("Es un texto JSON, pero no una lista Wiseplay (W3U) que se pueda leer")
        val n = list.entries.count { allowed(it.url) }
        val links = list.links.count { allowed(it.url) }
        if (n == 0 && links == 0) return OwnPastedCheck.Refused("La lista Wiseplay (W3U) no tiene canales que se puedan reproducir")
        val linked = when (links) {
            0 -> ""
            1 -> " y 1 lista enlazada, que se carga al guardar"
            else -> " y $links listas enlazadas, que se cargan al guardar"
        }
        return OwnPastedCheck.Ok(text, digest(text), OwnPastedFormat.W3U, "Lista Wiseplay (W3U): encontré ${channels(n)}$linked")
    }

    private fun channels(n: Int) = "$n ${if (n == 1) "canal" else "canales"}"

    /** [text] as the parts that carry it, all stamped [updatedAt]. */
    fun split(sourceId: String, text: String, digest: String, updatedAt: Long): List<OwnListPartEntity> {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(zipped)
        val chunks = encoded.chunked(PART_CHARS)
        return chunks.mapIndexed { i, data -> OwnListPartEntity(sourceId, i, chunks.size, digest, data, updatedAt) }
    }

    /** The text of [digest] from [parts], or null while one is missing, or when anything does not check out. */
    fun join(parts: List<OwnListPartEntity>, digest: String): String? {
        val mine = parts.filter { it.digest == digest }.sortedBy { it.part }
        val count = mine.firstOrNull()?.parts ?: return null
        if (count !in 1..MAX_PARTS || mine.size != count) return null
        if (mine.withIndex().any { (i, p) -> p.part != i || p.parts != count }) return null
        return try {
            val zipped = Base64.getUrlDecoder().decode(mine.joinToString("") { it.data })
            val bytes = GZIPInputStream(ByteArrayInputStream(zipped)).use { readCapped(it) } ?: return null
            val text = String(bytes, Charsets.UTF_8)
            text.takeIf { digest(it) == digest }
        } catch (e: Exception) {
            null
        }
    }

    /** Whether a synced part is well-formed (the rest is checked by [join]). */
    fun validPart(p: OwnListPartEntity): Boolean =
        p.sourceId.isNotEmpty() && p.sourceId.length <= 64 && ':' !in p.sourceId && !p.sourceId.startsWith("~") &&
            p.parts in 1..MAX_PARTS && p.part in 0 until p.parts && isDigest(p.digest) &&
            p.data.length in 1..PART_CHARS && BASE64URL.matches(p.data)

    /** Inflates at most [MAX_BYTES]: a part set that inflates past it (a gzip bomb) is null. */
    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
    }
}
