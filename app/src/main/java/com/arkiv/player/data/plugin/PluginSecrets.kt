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
 * value at the last moment, in Kotlin; each seal is opened at most once, on first use, and kept in
 * memory for this runtime only. [redact] swaps an opened value back for its marker in what returns
 * to the plugin.
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
     * [text] with every opened plain value, in every form [echoForms] lists, replaced by its marker.
     * One pass, longest form first at each position: a value that is a prefix of another leaves no
     * tail, and a marker just put in is never scanned again. Exact occurrences only: a server that
     * transforms the value any other way (case, hashing, another escaping) is not caught. [text]
     * itself comes back when it holds none: the regex runs only after a plain search found a form.
     */
    fun redact(text: String): String {
        val r = redaction()
        if (!r.foundIn(text)) return text
        return r.pattern!!.replace(text) { r.forms.getValue(it.value) }
    }

    /** True when [text] holds an opened value in any form [redact] replaces. */
    fun containsValue(text: String): Boolean = redaction().foundIn(text)

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

        /**
         * Every form [redact] replaces: what [substitute] writes (each [Encoding]), and how a server
         * commonly echoes a value back -- `URLEncoder`'s form encoding (`+` for a space) and the same
         * with `%20`, and the UTF-8 bytes in base64 and base64url, with and without padding.
         */
        private fun echoForms(plain: String): Set<String> {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            val form = URLEncoder.encode(plain, "UTF-8")
            return Encoding.entries.mapTo(LinkedHashSet()) { encode(plain, it) } + listOf(
                form, form.replace("+", "%20"),
                Base64.getEncoder().encodeToString(bytes), Base64.getEncoder().withoutPadding().encodeToString(bytes),
                Base64.getUrlEncoder().encodeToString(bytes), Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            )
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
            Encoding.JSON_STRING -> buildString {
                for (ch in value) {
                    when {
                        ch == '"' -> append("\\\"")
                        ch == '\\' -> append("\\\\")
                        ch == '\n' -> append("\\n")
                        ch == '\r' -> append("\\r")
                        ch == '\t' -> append("\\t")
                        ch == '\b' -> append("\\b")
                        ch == '\u000C' -> append("\\f")
                        ch < ' ' -> append("\\u00").append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF])
                        else -> append(ch)
                    }
                }
            }
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
