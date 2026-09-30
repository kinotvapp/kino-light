package com.arkiv.player.data.plugin

import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * One runtime's sealed secrets (spec: docs/superpowers/specs/2026-09-29-plugin-sealed-secrets-design.md §5).
 *
 * The plugin only ever holds a [marker]: `__kinoSecret_<name>_<nonce>__`, URL-safe, so it survives
 * concatenation, `JSON.stringify`, `encodeURIComponent` and form encoding unchanged, and carries no
 * information about the value. [nonce] is chosen once per runtime, so a marker copied from another
 * runtime (or guessed from the docs) is just text here. [substitute] swaps a marker for the plain
 * value at the last moment, in Kotlin. [redact] swaps a value back for its marker in what returns
 * to the plugin. Each seal is opened at most once per runtime and kept in memory for it only: on the
 * first [substitute] that needs it, or -- all of them at once -- on the first [redact] or
 * [containsValue] of non-empty text. Redaction can't wait for a value's first use: a cookie an
 * earlier runtime's request set, or a server echoing the value to a request that never carried it,
 * reaches the plugin before this runtime ever substituted anything.
 *
 * A request [DefaultPluginHost] substituted a value into may reach only [sealedHosts] -- the hosts
 * the MANIFEST declares, never one approved reactively, a server the person typed or the broad
 * video permission -- on every hop ([PluginHttp.Request.sealedTo]).
 *
 * Nothing here logs; a plain value never leaves this class except through [substitute].
 */
class PluginSecrets(
    private val sealed: Map<String, String>,
    private val binding: String,
    private val agreement: X25519Agreement,
    val sealedHosts: List<String>,
    private val recipient: ByteArray = SealedSecrets.KINO_PUBLIC_KEY_V1,
    private val nonce: String = randomNonce(),
) {
    private val markers: Map<String, String> = sealed.keys.associateWith { "__kinoSecret_${it}_${nonce}__" }
    private val opened = HashMap<String, String>()
    /** Seals [openAll] couldn't open (no native X25519 on this build): not tried again for redaction. Guarded by [opened]. */
    private val unopenable = HashSet<String>()
    @Volatile private var allOpened = false

    /** This runtime's marker for [name]; null when the manifest doesn't declare it. */
    fun marker(name: String): String? = markers[name]

    fun containsMarker(text: String): Boolean = markers.values.any { it in text }

    /** True when [text] is exactly one of this runtime's markers, nothing before or after it. */
    fun isMarker(text: String): Boolean = text in markers.values

    /** How a plain value is written where its marker was: its context's own encoding, so it stays ONE value there. */
    enum class Encoding {
        /** As is: a header value, a text body, a form field (FormBody encodes those itself). */
        RAW,
        /** A URL path segment or query component: every byte outside RFC 3986 unreserved percent-encoded (UTF-8, uppercase hex). */
        URL_COMPONENT,
        /** Inside a JSON string: JSON escaping, without the quotes. */
        JSON_STRING,
    }

    /** [text] with every marker of this runtime replaced by its plain value (opening the seal on first use). */
    fun substitute(text: String): String = substitute(text, Encoding.RAW)

    /** [substitute], each value written in [encoding]. [redact] knows every form written here. */
    fun substitute(text: String, encoding: Encoding): String {
        var out = text
        for ((name, marker) in markers) if (marker in out) out = out.replace(marker, encode(plainOf(name), encoding))
        return out
    }

    /**
     * [text] with every declared plain value, in every form [echoForms] lists, replaced by its marker.
     * One pass, longest form first at each position: a value that is a prefix of another leaves no
     * tail, and a marker just put in is never scanned again. Exact occurrences only: a server that
     * transforms the value any other way (case, hashing, another escaping) is not caught. [text]
     * itself comes back when it holds none: the regex runs only after a plain search found a form.
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        openAll()
        val r = redaction()
        if (!r.foundIn(text)) return text
        return r.pattern!!.replace(text) { r.forms.getValue(it.value) }
    }

    /** True when [text] holds a declared value in any form [redact] replaces. */
    fun containsValue(text: String): Boolean {
        if (text.isEmpty()) return false
        openAll()
        return redaction().foundIn(text)
    }

    /**
     * Opens every declared seal not opened yet, once per runtime (one X25519 each). A seal that
     * won't open is skipped: this build can't open it for [substitute] either, so its value can't
     * have been sent from here -- and [substitute] still reports that failure itself.
     */
    private fun openAll() {
        if (allOpened) return
        synchronized(opened) {
            if (allOpened) return
            for ((name, seal) in sealed) {
                if (name in opened || name in unopenable) continue
                try {
                    opened[name] = SealedSecrets.open(seal, binding, name, agreement, recipient)
                } catch (e: SealException) {
                    unopenable += name
                }
            }
            allOpened = true
        }
    }

    /** Every form of the values opened so far, each mapped to its marker, and one pattern for all of them. */
    private class Redaction(val openedCount: Int, val forms: Map<String, String>) {
        val pattern: Regex? = if (forms.isEmpty()) null else Regex(forms.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        fun foundIn(text: String): Boolean = forms.keys.any { it in text }
    }

    /** Built once per set of opened values: values are only ever added, so their count names the set. */
    @Volatile private var redaction = Redaction(0, emptyMap())

    private fun redaction(): Redaction {
        val current = redaction
        val snapshot = synchronized(opened) { if (opened.size == current.openedCount) return current else opened.toMap() }
        val forms = HashMap<String, String>()
        for ((name, plain) in snapshot) {
            if (plain.isEmpty()) continue
            for (form in echoForms(plain)) forms.putIfAbsent(form, markers.getValue(name))
        }
        return Redaction(snapshot.size, forms).also { redaction = it }
    }

    private fun plainOf(name: String): String = synchronized(opened) {
        opened.getOrPut(name) { SealedSecrets.open(sealed.getValue(name), binding, name, agreement, recipient) }
    }

    companion object {
        private val random = SecureRandom()
        private const val HEX = "0123456789ABCDEF"

        private const val HEX_LOWER = "0123456789abcdef"

        /**
         * Every form [redact] replaces: what [substitute] writes (each [Encoding]), and how a server
         * commonly echoes a value back -- `URLEncoder`'s form encoding (`+` for a space) and the same
         * with `%20`; the UTF-8 bytes in base64 and base64url, with and without padding; and inside a
         * JSON string with `/` escaped as `\/` (PHP's `json_encode`), with every non-ASCII character
         * as `\uXXXX` (PHP, Python's `json.dumps`, a surrogate pair as two), in lowercase and
         * uppercase hex, and each combination of those.
         */
        internal fun echoForms(plain: String): Set<String> {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            val form = URLEncoder.encode(plain, "UTF-8")
            val json = LinkedHashSet<String>()
            for (hex in listOf(HEX, HEX_LOWER)) for (slash in listOf(false, true)) for (nonAscii in listOf(false, true)) {
                json += jsonEscape(plain, slash, nonAscii, hex)
            }
            return Encoding.entries.mapTo(LinkedHashSet()) { encode(plain, it) } + json + listOf(
                form, form.replace("+", "%20"),
                Base64.getEncoder().encodeToString(bytes), Base64.getEncoder().withoutPadding().encodeToString(bytes),
                Base64.getUrlEncoder().encodeToString(bytes), Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            )
        }

        /**
         * [value] inside a JSON string, without the quotes: `"` and `\` escaped, the short escapes for
         * the usual control characters and `\u00XX` for the rest; `/` too when [slash], and every
         * character past ASCII as `\uXXXX` when [nonAscii]. [hex] picks the digits' case.
         */
        private fun jsonEscape(value: String, slash: Boolean, nonAscii: Boolean, hex: String): String = buildString {
            for (ch in value) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '/' && slash -> append("\\/")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch == '\b' -> append("\\b")
                    ch == '\u000C' -> append("\\f")
                    ch < ' ' || (nonAscii && ch.code > 0x7F) -> {
                        val c = ch.code
                        append("\\u").append(hex[c shr 12 and 0xF]).append(hex[c shr 8 and 0xF]).append(hex[c shr 4 and 0xF]).append(hex[c and 0xF])
                    }
                    else -> append(ch)
                }
            }
        }

        fun encode(value: String, encoding: Encoding): String = when (encoding) {
            Encoding.RAW -> value
            Encoding.URL_COMPONENT -> buildString {
                for (b in value.toByteArray(Charsets.UTF_8)) {
                    val c = b.toInt() and 0xFF
                    val ch = c.toChar()
                    if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                        append(ch)
                    } else {
                        append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                    }
                }
            }
            Encoding.JSON_STRING -> jsonEscape(value, slash = false, nonAscii = false, hex = HEX)
        }

        /** 16 lowercase hex characters (64 random bits). */
        fun randomNonce(): String {
            val bytes = ByteArray(8).also { random.nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

/**
 * The secrets a runtime of [plugin] opens with: its manifest's seals, bound to the INSTALLED
 * record's address (the same binding the installer verified them against), allowed toward the
 * manifest's own `hosts` only. Null when it declares none, when its recorded address no longer
 * parses (then `kino.secret` answers "not declared" rather than opening against a wrong binding),
 * or when that address is at a commit ref ([SealedSecrets.opensAt]; the installer refuses those
 * too, this is the runtime's own check).
 */
internal fun pluginSecretsFor(
    plugin: InstalledPlugin,
    agreement: X25519Agreement,
    recipient: ByteArray = SealedSecrets.KINO_PUBLIC_KEY_V1,
): PluginSecrets? {
    if (plugin.manifest.secrets.isEmpty()) return null
    val address = PluginAddress.parse(plugin.record.address) ?: return null
    if (!SealedSecrets.opensAt(address)) return null
    return PluginSecrets(plugin.manifest.secrets, SealedSecrets.bindingOf(address), agreement, plugin.manifest.hosts, recipient)
}
