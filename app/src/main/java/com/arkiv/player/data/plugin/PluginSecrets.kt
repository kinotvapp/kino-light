package com.arkiv.player.data.plugin

import java.security.SecureRandom

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

    /** [text] with every marker of this runtime replaced by its plain value (opening the seal on first use). */
    fun substitute(text: String): String {
        var out = text
        for ((name, marker) in markers) if (marker in out) out = out.replace(marker, plainOf(name))
        return out
    }

    /** [text] with every opened plain value replaced by its marker. */
    fun redact(text: String): String {
        var out = text
        for ((name, plain) in synchronized(opened) { opened.toMap() }) {
            if (plain.isNotEmpty()) out = out.replace(plain, markers.getValue(name))
        }
        return out
    }

    private fun plainOf(name: String): String = synchronized(opened) {
        opened.getOrPut(name) { SealedSecrets.open(sealed.getValue(name), binding, name, agreement, recipient) }
    }

    companion object {
        private val random = SecureRandom()

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
 * manifest's own `hosts` only. Null when it declares none, or when its recorded address no longer
 * parses (then `kino.secret` answers "not declared" rather than opening against a wrong binding).
 */
internal fun pluginSecretsFor(
    plugin: InstalledPlugin,
    agreement: X25519Agreement,
    recipient: ByteArray = SealedSecrets.KINO_PUBLIC_KEY_V1,
): PluginSecrets? {
    if (plugin.manifest.secrets.isEmpty()) return null
    val address = PluginAddress.parse(plugin.record.address) ?: return null
    return PluginSecrets(plugin.manifest.secrets, SealedSecrets.bindingOf(address), agreement, plugin.manifest.hosts, recipient)
}
