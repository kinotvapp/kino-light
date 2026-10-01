package com.arkiv.player.data.plugin

import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealed plugin code ("código cerrado", apiVersion 5's `sealedEntry`): the entry script ships
 * encrypted to Kino's v1 public key (the SAME X25519 key pair as [SealedSecrets], domain-separated
 * by its own HKDF info and AAD prefix) and signed by its author (Ed25519). Only a Kino build with
 * the native private key opens it. Obfuscation, not secrecy: see the plugin guide's threat model.
 *
 * File format `KSC1` (every multi-byte number big-endian):
 * ```
 * 0    4   magic "KSC1"
 * 4    1   format version = 1
 * 5    1   alg = 1 (X25519 + HKDF-SHA256 + AES-256-GCM)
 * 6    1   compression: 1 = raw DEFLATE (RFC 1951), 0 = none
 * 7    1   flags = 1 (bit0: signed; required -- every sealed entry carries its author's signature)
 * 8    4   plaintext length in bytes (after inflating), 1..MAX_PLAIN_BYTES
 * 12   32  author's Ed25519 public key
 * 44   32  ephemeral X25519 public key
 * 76   12  nonce
 * 88   n   AES-256-GCM ciphertext of the (deflated) UTF-8 script
 * ..   16  GCM tag
 * ..   64  Ed25519 signature over "kino-sealed-code-sig:v1" || every byte before it
 * ```
 * `key = HKDF-SHA256(X25519(kino, eph), salt = eph || kinoPublic, info = "kino-sealed-code:v1")`,
 * `AAD = "kino-sealed-code:v1|" || bytes[0..44) || "|" || binding || "|" || pluginId`. The header in
 * the AAD authenticates the length, compression and the AUTHOR KEY: a third party cannot re-sign
 * someone else's ciphertext under their own key without the GCM tag failing.
 *
 * Opening: parse (cheap, refuses anything malformed), check the expected author key, ECDH in native
 * code ([X25519Agreement]), GCM in the platform JCA, inflate into exactly the declared length. The
 * plaintext only ever exists in memory; the caller never writes it anywhere.
 */
object SealedCode {
    const val EXTENSION = ".kjs"
    const val MAGIC = "KSC1"
    const val FORMAT_VERSION = 1
    const val ALG_X25519_AES_GCM = 1
    const val COMPRESSION_NONE = 0
    const val COMPRESSION_DEFLATE = 1
    const val FLAG_SIGNED = 1

    /** The hard cap on the opened script. */
    const val MAX_PLAIN_BYTES = 4 * 1024 * 1024
    /** What the guide and the kit recommend: QuickJS needs ~1 s to compile 2 MB on the slowest TV box. */
    const val RECOMMENDED_MAX_PLAIN_BYTES = 2 * 1024 * 1024

    const val HEADER_BYTES = 44
    private const val EPH = 32
    private const val NONCE = 12
    private const val TAG = 16
    const val SIGNATURE_BYTES = Ed25519.SIGNATURE_BYTES
    /** Smallest well-formed file: header + eph + nonce + 1 byte of ciphertext + tag + signature. */
    const val MIN_BYTES = HEADER_BYTES + EPH + NONCE + 1 + TAG + SIGNATURE_BYTES

    private val INFO = "kino-sealed-code:v1".toByteArray(Charsets.UTF_8)
    private val AAD_PREFIX = "kino-sealed-code:v1|".toByteArray(Charsets.UTF_8)
    private val SIG_PREFIX = "kino-sealed-code-sig:v1".toByteArray(Charsets.UTF_8)

    /** [SealException.message]s, fixed strings (never a byte of the code). */
    const val DAMAGED = "el código sellado está dañado"
    const val NOT_FOR_THIS_PLUGIN = "el código sellado no es para este plugin"
    const val BAD_SIGNATURE = "la firma del autor no es válida"
    const val OTHER_AUTHOR_KEY = "el código sellado está firmado con otra clave"

    class Header(val compression: Int, val plainLength: Int, val authorKey: ByteArray)

    /** The parsed header, or [SealException] ([DAMAGED]) for anything that is not a v1 sealed entry. */
    fun header(blob: ByteArray): Header {
        if (blob.size < MIN_BYTES) throw SealException(DAMAGED)
        for (i in 0 until 4) if (blob[i] != MAGIC[i].code.toByte()) throw SealException(DAMAGED)
        if (blob[4].toInt() != FORMAT_VERSION || blob[5].toInt() != ALG_X25519_AES_GCM) throw SealException(DAMAGED)
        val compression = blob[6].toInt()
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_DEFLATE) throw SealException(DAMAGED)
        if (blob[7].toInt() != FLAG_SIGNED) throw SealException(DAMAGED)
        val length = ((blob[8].toInt() and 0xff) shl 24) or ((blob[9].toInt() and 0xff) shl 16) or
            ((blob[10].toInt() and 0xff) shl 8) or (blob[11].toInt() and 0xff)
        if (length < 1 || length > MAX_PLAIN_BYTES) throw SealException(DAMAGED)
        val ctLength = blob.size - HEADER_BYTES - EPH - NONCE - TAG - SIGNATURE_BYTES
        // Stored: the ciphertext is the script itself. Deflated: never more than the script plus
        // DEFLATE's worst-case stored-block overhead.
        if (compression == COMPRESSION_NONE && ctLength != length) throw SealException(DAMAGED)
        if (compression == COMPRESSION_DEFLATE && ctLength > length + length / 1000 + 1024) throw SealException(DAMAGED)
        return Header(compression, length, blob.copyOfRange(12, HEADER_BYTES))
    }

    /**
     * The author's key when [blob]'s signature is valid; [SealException] ([BAD_SIGNATURE] or
     * [DAMAGED]) otherwise. Public data only: callable before anything is decrypted.
     */
    fun verifiedAuthorKey(blob: ByteArray): ByteArray {
        val h = header(blob)
        val signed = blob.size - SIGNATURE_BYTES
        val message = SIG_PREFIX + blob.copyOfRange(0, signed)
        if (!Ed25519.verify(h.authorKey, message, blob.copyOfRange(signed, blob.size))) throw SealException(BAD_SIGNATURE)
        return h.authorKey
    }

    /**
     * The script in [blob], for plugin [pluginId] installed from [binding] ([SealedSecrets.bindingOf]).
     * [expectedAuthorKey], when given, must be the blob's author key (a pinned key, checked before
     * any decryption). Does NOT verify the signature: the installer did ([verifiedAuthorKey]) before
     * this blob was stored, and the stored file is sha256-checked on every load.
     */
    fun open(
        blob: ByteArray, binding: String, pluginId: String, agreement: X25519Agreement,
        recipientPublic: ByteArray = SealedSecrets.KINO_PUBLIC_KEY_V1, expectedAuthorKey: ByteArray? = null,
    ): String {
        val h = header(blob)
        if (expectedAuthorKey != null && !MessageDigest.isEqual(expectedAuthorKey, h.authorKey)) throw SealException(OTHER_AUTHOR_KEY)
        val ephPub = blob.copyOfRange(HEADER_BYTES, HEADER_BYTES + EPH)
        val nonce = blob.copyOfRange(HEADER_BYTES + EPH, HEADER_BYTES + EPH + NONCE)
        val ctStart = HEADER_BYTES + EPH + NONCE
        val ctAndTag = blob.size - SIGNATURE_BYTES - ctStart
        val shared = agreement.sharedSecret(ephPub)
        val key = try { SealedSecrets.hkdf(shared, ephPub + recipientPublic, INFO) } finally { shared.fill(0) }
        // One byte more than the plaintext: a raw-DEFLATE Inflater may want a trailing dummy byte.
        val decrypted = ByteArray(ctAndTag - TAG + 1)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            c.updateAAD(AAD_PREFIX)
            c.updateAAD(blob, 0, HEADER_BYTES)
            c.updateAAD("|$binding|$pluginId".toByteArray(Charsets.UTF_8))
            try {
                c.doFinal(blob, ctStart, ctAndTag, decrypted, 0)
            } catch (e: Exception) {
                throw SealException(NOT_FOR_THIS_PLUGIN)
            }
        } finally {
            key.fill(0)
        }
        try {
            val plain = if (h.compression == COMPRESSION_NONE) decrypted.copyOf(h.plainLength) else inflate(decrypted, ctAndTag - TAG, h.plainLength)
            return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
        } finally {
            decrypted.fill(0)
        }
    }

    /** Exactly [length] bytes out of the first [inputLength] bytes of [input], or [DAMAGED]: never more (zip-bomb safe). */
    private fun inflate(input: ByteArray, inputLength: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        val inflater = Inflater(true)
        try {
            inflater.setInput(input, 0, inputLength + 1) // + the zero dummy byte past the plaintext
            var n = 0
            while (n < length) {
                val r = inflater.inflate(out, n, length - n)
                if (r == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            if (n != length) throw SealException(DAMAGED)
            if (!inflater.finished() && inflater.inflate(ByteArray(1)) != 0) throw SealException(DAMAGED)
            if (!inflater.finished()) throw SealException(DAMAGED)
            return out
        } catch (e: DataFormatException) {
            out.fill(0)
            throw SealException(DAMAGED)
        } catch (e: SealException) {
            out.fill(0)
            throw e
        } finally {
            inflater.end()
        }
    }

    /** What the person reads for an author key: the first 8 bytes of its SHA-256, `ABCD-EF01-2345-6789`. */
    fun fingerprint(authorKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(authorKey).copyOf(8)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
