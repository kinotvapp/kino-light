package com.arkiv.player.data.plugin

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test-only v1 sealer and JCA X25519 (JDK 11+), written from the spec, not from SealedSecrets. */
object TestSealing {
    // RFC 7748 §6.1 test vectors, copied byte-for-byte from the RFC text.
    val TEST_PRIVATE: ByteArray = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    val TEST_PUBLIC: ByteArray = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    val ALICE_PRIVATE: ByteArray = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")

    private val PKCS8_PREFIX = hex("302e020100300506032b656e04220420")
    private val SPKI_PREFIX = hex("302a300506032b656e032100")

    fun x25519(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + privateKey))
        val pub = kf.generatePublic(X509EncodedKeySpec(SPKI_PREFIX + peerPublic))
        return KeyAgreement.getInstance("X25519").run { init(priv); doPhase(pub, true); generateSecret() }
    }

    fun publicOf(privateKey: ByteArray): ByteArray {
        // X25519(priv, basepoint 9)
        val base = ByteArray(32).also { it[0] = 9 }
        return x25519(privateKey, base)
    }

    val agreement = X25519Agreement { peer -> x25519(TEST_PRIVATE, peer) }

    fun seal(
        plain: String, binding: String, name: String,
        recipientPublic: ByteArray = TEST_PUBLIC,
        ephemeralPrivate: ByteArray? = null, nonce: ByteArray? = null,
    ): String {
        val ephPriv = ephemeralPrivate ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephPub = publicOf(ephPriv)
        val shared = x25519(ephPriv, recipientPublic)
        val key = hkdf(shared, ephPub + recipientPublic, "kino-sealed:v1".toByteArray(), 32)
        val iv = nonce ?: ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("kino-sealed:v1|$binding|$name".toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "kino-sealed:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ephPub + iv + ct)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(info + byteArrayOf(1)).copyOf(len)
    }

    fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
