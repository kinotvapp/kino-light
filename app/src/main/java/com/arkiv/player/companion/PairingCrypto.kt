package com.arkiv.player.companion

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The end-to-end key two paired devices share, so a plugin password can cross the companion link
 * (plain `ws://` on the LAN) without anyone on the network reading it.
 *
 * Key agreement: X25519 (Tink's pure-Java implementation: it works on every Android version this app
 * supports, unlike the platform's XDH which needs API 33). Each side contributes a fresh key pair;
 * the shared secret goes through HKDF-SHA256 bound to both device ids and both public keys, so a key
 * agreed between two devices can never be passed off as one between other two. A key-confirmation MAC
 * proves the host derived the same key before the controller stores it.
 *
 * Protection: anyone who only listens on the LAN (at pairing or ever after) learns nothing; once the
 * key is agreed, a later attacker who even takes over the link cannot read a password. The residual
 * risk is an attacker actively relaying the link at the very moment the key is agreed (pairing, or the
 * first connection of an existing pairing after this update): a short code read on both screens would
 * be needed to rule that out.
 */
object PairingCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val SEALED_PREFIX = "k1."
    private val SALT = "kino-companion-kx-v1".toByteArray(Charsets.UTF_8)
    private val rng = SecureRandom()

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun newKeyPair(): KeyPair {
        val priv = X25519.generatePrivateKey()
        return KeyPair(priv, X25519.publicFromPrivate(priv))
    }

    /**
     * The shared key, from this side's [privateKey] and the other side's [peerPublic]. Both sides pass
     * the same [controllerId]/[hostId]/[controllerPublic]/[hostPublic], so both get the same key.
     * Throws on a malformed public key.
     */
    fun derive(privateKey: ByteArray, peerPublic: ByteArray, controllerId: String, hostId: String, controllerPublic: ByteArray, hostPublic: ByteArray): ByteArray {
        require(peerPublic.size == 32) { "bad public key" }
        val shared = X25519.computeSharedSecret(privateKey, peerPublic)
        require(shared.any { it != 0.toByte() }) { "low-order public key" }
        val info = transcript(controllerId, hostId, controllerPublic, hostPublic)
        return Hkdf.computeHkdf("HMACSHA256", shared, SALT, info, KEY_BYTES)
    }

    private fun transcript(controllerId: String, hostId: String, controllerPublic: ByteArray, hostPublic: ByteArray): ByteArray {
        val parts = listOf("kino-plugin-secrets-v1".toByteArray(), controllerId.toByteArray(), hostId.toByteArray(), controllerPublic, hostPublic)
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            out.write(p.size ushr 8 and 0xff); out.write(p.size and 0xff)
            out.write(p)
        }
        return out.toByteArray()
    }

    /** What the host sends to prove it derived [key]: never the key, never reusable as one. */
    fun confirmation(key: ByteArray): String = b64(hmac(key, "kino-kx-confirm-host"))

    fun confirms(key: ByteArray, confirmation: String): Boolean {
        val given = runCatching { unb64(confirmation) }.getOrNull() ?: return false
        return MessageDigest.isEqual(hmac(key, "kino-kx-confirm-host"), given)
    }

    /** A short public name for [key], so two devices can tell they hold the same one without showing it. */
    fun keyId(key: ByteArray): String = hmac(key, "kino-kx-id").copyOf(8).joinToString("") { "%02x".format(it) }

    /**
     * [plaintext] sealed with AES-256-GCM under [key], bound to [aad] (a plugin password: its plugin id
     * and setting key, so a value can never be replayed into another setting or plugin). A fresh random
     * nonce every time.
     */
    fun seal(key: ByteArray, plaintext: String, aad: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SEALED_PREFIX + b64(nonce) + "." + b64(ct)
    }

    /** [seal]'s inverse; null when the value was altered, sealed under another key, or bound to another [aad]. */
    fun open(key: ByteArray, sealed: String, aad: String): String? {
        if (!sealed.startsWith(SEALED_PREFIX)) return null
        val parts = sealed.removePrefix(SEALED_PREFIX).split('.')
        if (parts.size != 2) return null
        return try {
            val nonce = unb64(parts[0])
            if (nonce.size != NONCE_BYTES) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(c.doFinal(unb64(parts[1])), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    private fun hmac(key: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label.toByteArray(Charsets.UTF_8))

    fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
