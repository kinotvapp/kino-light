package com.arkiv.player.data.plugin

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The X25519 step of opening a seal: production runs it natively (the private key never leaves native code). */
fun interface X25519Agreement {
    fun sharedSecret(peerPublic: ByteArray): ByteArray
}

class SealException(message: String) : Exception(message)

/**
 * The v1 sealed-secret format (spec: docs/superpowers/specs/2026-09-29-plugin-sealed-secrets-design.md §3).
 * Obfuscation-level: the private key ships inside the APK.
 */
object SealedSecrets {
    const val PREFIX_V1 = "kino-sealed:v1:"
    const val MAX_PLAINTEXT_BYTES = 4096
    const val MAX_SECRETS = 16
    val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$")

    /** Kino's v1 (production) public key; its private half lives only in the native library. */
    val KINO_PUBLIC_KEY_V1: ByteArray = hex("b13ecf6d231a75bf57ca21d977075c74f914b4416653cf89940897a29393e65c")

    /** Shown to the person when this device build has no native X25519 to open seals with (Task 2, Task 4). */
    const val NO_NATIVE_MESSAGE = "este Kino no puede abrir datos sellados"

    private const val EPH = 32
    private const val NONCE = 12
    private const val TAG = 16
    private const val MAX_RAW = EPH + NONCE + MAX_PLAINTEXT_BYTES + TAG
    private val INFO = "kino-sealed:v1".toByteArray(Charsets.UTF_8)

    fun bindingOf(address: PluginAddress): String =
        (address.owner + "/" + address.repo + if (address.path.isNotEmpty()) "/" + address.path else "").lowercase()

    fun isWellFormed(seal: String): Boolean = raw(seal) != null

    fun open(
        seal: String, binding: String, name: String,
        agreement: X25519Agreement, recipientPublic: ByteArray = KINO_PUBLIC_KEY_V1,
    ): String {
        val raw = raw(seal) ?: throw SealException("sello inválido")
        val ephPub = raw.copyOfRange(0, EPH)
        val nonce = raw.copyOfRange(EPH, EPH + NONCE)
        val ct = raw.copyOfRange(EPH + NONCE, raw.size)
        val shared = agreement.sharedSecret(ephPub)
        try {
            val key = hkdf(shared, ephPub + recipientPublic, INFO)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
            c.updateAAD("kino-sealed:v1|$binding|$name".toByteArray(Charsets.UTF_8))
            val plain = try { c.doFinal(ct) } catch (e: Exception) { throw SealException("sello no válido para este plugin") }
            key.fill(0)
            return plain.toString(Charsets.UTF_8).also { plain.fill(0) }
        } finally {
            shared.fill(0)
        }
    }

    private fun raw(seal: String): ByteArray? {
        if (!seal.startsWith(PREFIX_V1)) return null
        val body = seal.substring(PREFIX_V1.length)
        if (body.isEmpty() || body.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
        val raw = try { Base64.getUrlDecoder().decode(body) } catch (e: IllegalArgumentException) { return null }
        if (raw.size < EPH + NONCE + TAG + 1 || raw.size > MAX_RAW) return null
        return raw
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info + byteArrayOf(1)).copyOf(32).also { prk.fill(0) }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
