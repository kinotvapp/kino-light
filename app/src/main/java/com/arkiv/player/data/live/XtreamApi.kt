package com.arkiv.player.data.live

import com.arkiv.player.data.plugin.PluginLiveContract
import com.arkiv.player.data.plugin.PrivateAddressException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One Xtream Codes account: the server's base address (scheme, host, port, optional path prefix) plus the login. The password is a secret: it never reaches a log or a shown address ([XtreamUrl.mask]). */
class XtreamAccount(val base: HttpUrl, val username: String, val password: String) {
    override fun toString() = "XtreamAccount(${base.host}, user=***)"
}

/** A failure the person can act on: [message] is Spanish and never carries an address or the login. */
class XtreamException(message: String) : IOException(message)

/**
 * Xtream Codes addresses. A source of this type is stored like every other list, as its `player_api.php`
 * address in the url column (`http://host:port/player_api.php?username=U&password=P`): no new column or
 * migration, and it syncs to a linked device through the same row (and the same E2E channel) as a
 * `get.php` list, credentials included. Pure.
 */
object XtreamUrl {
    const val API = "player_api.php"
    private const val GET = "get.php"
    private const val XMLTV = "xmltv.php"
    const val MAX_CREDENTIAL = 120

    sealed interface Built {
        data class Ok(val url: String, val cleartext: Boolean) : Built
        data class Invalid(val errors: Map<XtreamField, String>) : Built
    }

    enum class XtreamField { SERVER, USERNAME, PASSWORD }

    /** A `player_api.php` or `get.php` address that carries both `username` and `password`. */
    fun parse(raw: String): XtreamAccount? {
        val u = raw.trim().toHttpUrlOrNull() ?: return null
        val last = u.pathSegments.lastOrNull().orEmpty().lowercase()
        if (last != API && last != GET && last != XMLTV) return null
        val user = u.queryParameter("username")?.takeIf { it.isNotEmpty() } ?: return null
        val pass = u.queryParameter("password")?.takeIf { it.isNotEmpty() } ?: return null
        val base = u.newBuilder().encodedPath("/" + u.pathSegments.dropLast(1).filter { it.isNotEmpty() }.joinToString("/") { it }).query(null).fragment(null).build()
        return XtreamAccount(base, user, pass)
    }

    /** Whether [url] is the source address of an Xtream list ([apiUrl]): `player_api.php` with a login and no `action`. */
    fun isApi(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.pathSegments.lastOrNull().equals(API, ignoreCase = true) && u.queryParameter("action") == null &&
            !u.queryParameter("username").isNullOrEmpty() && !u.queryParameter("password").isNullOrEmpty()
    }

    /** The account behind a stored [isApi] address. */
    fun accountOf(url: String): XtreamAccount? = if (isApi(url)) parse(url) else null

    private fun endpoint(a: XtreamAccount, file: String): HttpUrl.Builder = a.base.newBuilder().apply {
        val segs = a.base.pathSegments.filter { it.isNotEmpty() }
        encodedPath("/")
        segs.forEach { addPathSegment(it) }
        addPathSegment(file)
        addQueryParameter("username", a.username)
        addQueryParameter("password", a.password)
    }

    fun apiUrl(a: XtreamAccount, action: String? = null, extra: Map<String, String> = emptyMap()): String =
        endpoint(a, API).apply {
            action?.let { addQueryParameter("action", it) }
            extra.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build().toString()

    fun xmltvUrl(a: XtreamAccount): String = endpoint(a, XMLTV).build().toString()

    /** `<base>/live/<user>/<pass>/<id>.<ext>`; each part is percent-encoded as one path segment. */
    fun streamUrl(a: XtreamAccount, streamId: String, ext: String): String =
        a.base.newBuilder().apply {
            val segs = a.base.pathSegments.filter { it.isNotEmpty() }
            encodedPath("/")
            segs.forEach { addPathSegment(it) }
            addPathSegment("live"); addPathSegment(a.username); addPathSegment(a.password); addPathSegment("$streamId.$ext")
        }.build().toString()

    /** The form's three fields -> the address to store, or the error of each field. [server] may be `host`, `host:port` or a full address. */
    fun build(server: String, username: String, password: String): Built {
        val errors = LinkedHashMap<XtreamField, String>()
        val text = server.trim()
        val withScheme = when {
            text.isEmpty() -> ""
            text.contains("://") -> text
            else -> "http://$text"
        }
        var base: HttpUrl? = null
        when {
            text.isEmpty() -> errors[XtreamField.SERVER] = "Escribe la dirección del servidor"
            text.any(Char::isWhitespace) -> errors[XtreamField.SERVER] = "La dirección del servidor no puede llevar espacios"
            else -> when (val r = OwnSourceValidator.checkUrl(withScheme)) {
                is OwnUrlCheck.Refused -> errors[XtreamField.SERVER] = r.message
                is OwnUrlCheck.Ok -> base = r.url.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()
            }
        }
        checkCredential(username, "usuario")?.let { errors[XtreamField.USERNAME] = it }
        checkCredential(password, "contraseña")?.let { errors[XtreamField.PASSWORD] = it }
        if (errors.isNotEmpty() || base == null) return Built.Invalid(errors)
        // A pasted .../get.php or .../player_api.php keeps only the folder in front of the file.
        val segs = base.pathSegments.filter { it.isNotEmpty() }.let { s ->
            if (s.lastOrNull()?.lowercase() in setOf(API, GET, XMLTV)) s.dropLast(1) else s
        }
        val clean = base.newBuilder().encodedPath("/").apply { segs.forEach { addPathSegment(it) } }.build()
        return Built.Ok(apiUrl(XtreamAccount(clean, username, password)), clean.scheme == "http")
    }

    private fun checkCredential(v: String, what: String): String? = when {
        v.isEmpty() -> "Escribe el $what"
        v.length > MAX_CREDENTIAL -> "El $what es demasiado largo"
        v.any { Character.isISOControl(it) } -> "El $what tiene caracteres que no se pueden usar"
        else -> null
    }

    private val MASK_QUERY = Regex("(?i)(username|password)=[^&\\s\"']*")
    private val MASK_PATH = Regex("(?i)(/live/)[^/\\s?\"']+/[^/\\s?\"']+/")

    /** [text] with the login hidden (`username=`/`password=` values and `/live/<user>/<pass>/`), for anything that is shown or logged. */
    fun mask(text: String): String = MASK_PATH.replace(MASK_QUERY.replace(text, "$1=***"), "$1***/***/")

    /** Splits a pasted `player_api.php` / `get.php` address into the form's fields (server, user, password), or null. */
    fun split(raw: String): Triple<String, String, String>? {
        val a = parse(raw) ?: return null
        return Triple(a.base.toString().trimEnd('/'), a.username, a.password)
    }

    /** What the manager shows of a stored Xtream address: the server only. */
    fun hostOf(url: String): String? = if (isApi(url)) url.toHttpUrlOrNull()?.host else null
}

/** What `player_api.php` says about the login. */
sealed interface XtreamAuth {
    /** [expiresMs] null = no expiry; [formats] = the stream formats the account may use (`m3u8`, `ts`). */
    data class Ok(val expiresMs: Long?, val formats: Set<String>, val connections: Int?) : XtreamAuth
    data class Failed(val message: String) : XtreamAuth
}

/** One live channel of an Xtream server, before it is turned into a list entry. */
internal class XtreamStream(val id: String, val name: String, val icon: String, val epgId: String, val categoryId: String, val number: Int)

/** Answers of `player_api.php` as data. Tolerant of the many dialects of the panels (numbers as strings, nulls, missing fields); bounded by [LenientJson]. */
object XtreamParser {
    const val NOT_XTREAM = "Esa dirección no responde como un servidor Xtream. Revisa el servidor y el puerto."
    const val BAD_LOGIN = "Usuario o contraseña incorrectos"
    const val DISABLED = "La cuenta está desactivada o bloqueada por el proveedor"
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private fun root(text: String, maxNodes: Int = LenientJson.MAX_NODES): Any? =
        try { LenientJson.parse(text, maxNodes = maxNodes) } catch (e: LenientJson.Invalid) { null }

    private fun int(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        is Boolean -> if (v) 1 else 0
        else -> null
    }

    private fun str(v: Any?): String = when (v) {
        is String -> v
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> ""
    }

    fun auth(text: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): XtreamAuth {
        val info = ((root(text) as? Map<*, *>)?.get("user_info")) as? Map<*, *> ?: return XtreamAuth.Failed(NOT_XTREAM)
        // `auth` 0 is the usual "no". Some panels answer an empty user_info for a bad login: no `auth` at all.
        val auth = int(info["auth"])
        if (auth == null || auth == 0) return XtreamAuth.Failed(BAD_LOGIN)
        val status = str(info["status"]).trim().lowercase()
        val expiry = str(info["exp_date"]).trim().toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
        if (status == "expired" || (expiry != null && expiry <= nowMs)) {
            val on = expiry?.let { " el ${DATE.format(Instant.ofEpochMilli(it).atZone(zone))}" }.orEmpty()
            return XtreamAuth.Failed("La cuenta venció$on. Renuévala con tu proveedor.")
        }
        if (status == "disabled" || status == "banned") return XtreamAuth.Failed(DISABLED)
        val formats = (info["allowed_output_formats"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.lowercase() }.toSet()
        return XtreamAuth.Ok(expiry, formats, int(info["max_connections"]))
    }

    /** A short Spanish line about a good account, for "Probar". */
    fun describe(ok: XtreamAuth.Ok, zone: ZoneId = ZoneId.systemDefault()): String {
        val until = ok.expiresMs?.let { " Vence el ${DATE.format(Instant.ofEpochMilli(it).atZone(zone))}." }.orEmpty()
        return "Servidor Xtream: usuario y contraseña correctos.$until"
    }

    fun categories(text: String): Map<String, String> {
        val list = root(text) as? List<*> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (c in list) {
            val m = c as? Map<*, *> ?: continue
            val id = str(m["category_id"]).takeIf { it.isNotEmpty() } ?: continue
            out[id] = str(m["category_name"]).trim().take(80)
        }
        return out
    }

    internal fun streams(text: String): List<XtreamStream> {
        val list = root(text, maxNodes = STREAM_NODES) as? List<*> ?: return emptyList()
        val out = ArrayList<XtreamStream>(minOf(list.size, 20_000))
        for (s in list) {
            val m = s as? Map<*, *> ?: continue
            val id = str(m["stream_id"]).trim().takeIf { it.isNotEmpty() && it.length <= 20 && it.all(Char::isDigit) } ?: continue
            val name = str(m["name"]).map { if (Character.isISOControl(it)) ' ' else it }.joinToString("").trim().take(200)
            if (name.isEmpty()) continue
            out += XtreamStream(
                id = id, name = name, icon = str(m["stream_icon"]).trim(), epgId = str(m["epg_channel_id"]).trim().take(200),
                categoryId = str(m["category_id"]), number = int(m["num"])?.takeIf { it in 1..PluginLiveContract.MAX_CHANNEL_NUMBER } ?: 0,
            )
            if (out.size >= MAX_STREAMS) break
        }
        return out
    }

    const val MAX_STREAMS = 20_000
    private const val STREAM_NODES = 500_000

    /** `m3u8` when the account allows it (or says nothing), else `ts`. */
    fun extensionFor(formats: Set<String>): String = if (formats.isEmpty() || "m3u8" in formats) "m3u8" else if ("ts" in formats) "ts" else "m3u8"

    internal fun entries(account: XtreamAccount, streams: List<XtreamStream>, categories: Map<String, String>, ext: String): List<M3uEntry> =
        streams.map { s ->
            M3uEntry(
                name = s.name, url = XtreamUrl.streamUrl(account, s.id, ext), tvgId = s.epgId, tvgName = s.name,
                logo = s.icon.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty(),
                number = s.number, group = categories[s.categoryId].orEmpty(),
            )
        }
}

/**
 * The own provider's download path for an Xtream source ([XtreamUrl.isApi]): it asks the server for the login
 * state, the live categories and the live channels, and saves them as an extended M3U (guide = the server's
 * `xmltv.php`), so everything after the download -- the streamed parse, grouping, codes, caps, guides -- is the
 * M3U path unchanged, like [W3uPlaylistFetcher]. Every other address goes to [inner] untouched.
 *
 * The stream address is built on the server the person typed (never on the `server_info` the server reports),
 * `.m3u8` unless the account only allows `.ts`. Every request goes through [inner]: the gated own-hosts client.
 * Failures become [XtreamException]s with a Spanish message that never holds the address or the login.
 */
internal class XtreamPlaylistFetcher(
    private val inner: LivePlaylistFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : LivePlaylistFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray = inner.fetch(url, headers, maxBytes)

    override suspend fun fetchTo(url: String, headers: Map<String, String>, maxBytes: Long, into: File) {
        val account = XtreamUrl.accountOf(url) ?: return inner.fetchTo(url, headers, maxBytes, into)
        try {
            val auth = XtreamParser.auth(call(XtreamUrl.apiUrl(account), headers, SMALL_BYTES), clock())
            val ok = when (auth) {
                is XtreamAuth.Failed -> throw XtreamException(auth.message)
                is XtreamAuth.Ok -> auth
            }
            val categories = XtreamParser.categories(call(XtreamUrl.apiUrl(account, "get_live_categories"), headers, SMALL_BYTES * 4))
            val streams = XtreamParser.streams(call(XtreamUrl.apiUrl(account, "get_live_streams"), headers, STREAMS_BYTES))
            if (streams.isEmpty()) throw XtreamException("La cuenta es válida, pero el servidor no tiene canales en vivo")
            val entries = XtreamParser.entries(account, streams, categories, XtreamParser.extensionFor(ok.formats))
            withContext(Dispatchers.IO) {
                into.bufferedWriter().use { M3uWriter.write(entries, listOf(XtreamUrl.xmltvUrl(account).replace(",", "%2C")), it) }
            }
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }

    private suspend fun call(url: String, headers: Map<String, String>, max: Long): String = try {
        M3uParser.decode(inner.fetch(url, headers, max))
    } catch (e: CancellationException) {
        throw e
    } catch (e: XtreamException) {
        throw e
    } catch (e: Exception) {
        throw XtreamException(describe(e))
    }

    companion object {
        const val SMALL_BYTES = 256L * 1024
        const val STREAMS_BYTES = 12L * 1024 * 1024

        /** A transport failure as the person should read it: no address, no login. */
        fun describe(e: Exception): String = when {
            e is PlaylistTooLargeException -> "El servidor respondió con demasiados datos"
            e is PlaylistHttpException -> when (e.code) {
                401, 403, 458 -> "El servidor rechazó la cuenta (HTTP ${e.code}). Revisa el usuario y la contraseña."
                404 -> "El servidor no tiene la API Xtream en esa dirección. Revisa el servidor y el puerto."
                in 500..599 -> "El servidor falló (HTTP ${e.code}). Intenta más tarde."
                else -> "El servidor respondió con un error (HTTP ${e.code})"
            }
            e is PrivateAddressException -> "La dirección apunta a tu red local, que no se puede usar"
            e is UnknownHostException -> "No encontré ese servidor"
            e is SocketTimeoutException || e is java.io.InterruptedIOException -> "El servidor tardó demasiado en responder"
            else -> "No se pudo conectar con el servidor"
        }
    }
}
