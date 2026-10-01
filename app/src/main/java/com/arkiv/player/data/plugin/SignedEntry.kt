package com.arkiv.player.data.plugin

import java.security.MessageDigest

/**
 * Author-signed plugins (apiVersion 5's `signature`): the entry script stays plain, readable
 * JavaScript, and its author signs it with their own Ed25519 key (`node sdk/seal.mjs --sign`). Kino
 * checks the signature at install and update time only -- never when a runtime loads (the stored
 * file is sha256-checked on every load, as for any plugin) -- and pins the key the first time
 * (trust on first use): an update signed by another key, or no longer signed, is refused.
 *
 * The manifest carries `"signature": { "authorKey": <64 hex>, "value": <128 hex> }`, an Ed25519
 * signature over the UTF-8 bytes of
 * ```
 * kino-signed-entry:v1\n<binding>\n<plugin id>\n<version>\n<sha256 hex of the entry file's bytes>
 * ```
 * where `binding` is [SealedSecrets.bindingOf] (`owner/repo[/folder]`, lowercase, no ref). So a
 * signature covers those exact script bytes and cannot be replayed onto another repository, another
 * plugin id or another version.
 */
class EntrySignature(val authorKey: ByteArray, val value: ByteArray) {
    val fingerprint: String get() = SignedEntry.fingerprint(authorKey)

    override fun equals(other: Any?): Boolean =
        other is EntrySignature && authorKey.contentEquals(other.authorKey) && value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * authorKey.contentHashCode() + value.contentHashCode()
}

object SignedEntry {
    const val DOMAIN = "kino-signed-entry:v1"
    const val KEY_HEX_CHARS = Ed25519.PUBLIC_KEY_BYTES * 2
    const val SIGNATURE_HEX_CHARS = Ed25519.SIGNATURE_BYTES * 2

    /** The exact bytes an author signs (see the class doc). */
    fun message(binding: String, pluginId: String, version: String, script: ByteArray): ByteArray =
        "$DOMAIN\n$binding\n$pluginId\n$version\n${hex(MessageDigest.getInstance("SHA-256").digest(script))}".toByteArray(Charsets.UTF_8)

    /** True when [signature] signs [script] as plugin [pluginId] version [version] installed from [binding]. */
    fun verify(signature: EntrySignature, binding: String, pluginId: String, version: String, script: ByteArray): Boolean =
        Ed25519.verify(signature.authorKey, message(binding, pluginId, version, script), signature.value)

    /** What the person reads for an author key: the first 8 bytes of its SHA-256, `ABCD-EF01-2345-6789`. */
    fun fingerprint(authorKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(authorKey).copyOf(8)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Lowercase hex only (what the kit writes), or null. */
    fun hexBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * What a signed entry has verified at preview and carries from the consent sheet to the commit: the
 * downloaded script (installed byte for byte) and its author's key. [firstKey]: no key was pinned
 * for this plugin yet (a first install, or an update that signs a plugin that was unsigned), so the
 * consent sheet says it is the first time.
 */
class SignedEntryPreview(val script: ByteArray, val authorKey: ByteArray, val firstKey: Boolean) {
    val fingerprint: String get() = SignedEntry.fingerprint(authorKey)
}
