package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/** Test-only author signing with the JDK's Ed25519, written from the format in the guide, not from SignedEntry. */
object TestSigning {
    class AuthorKey(val publicRaw: ByteArray, val private: PrivateKey)

    fun newAuthorKey(): AuthorKey {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        // X.509 SubjectPublicKeyInfo for Ed25519: a 12-byte prefix, then the raw 32-byte key.
        return AuthorKey(kp.public.encoded.copyOfRange(12, 44), kp.private)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sign(script: ByteArray, binding: String, id: String, version: String, author: AuthorKey): ByteArray {
        val digest = hex(MessageDigest.getInstance("SHA-256").digest(script))
        return Signature.getInstance("Ed25519").run {
            initSign(author.private)
            update("kino-signed-entry:v1\n$binding\n$id\n$version\n$digest".toByteArray(Charsets.UTF_8))
            sign()
        }
    }

    /** The manifest's `signature` object. */
    fun field(script: ByteArray, binding: String, id: String, version: String, author: AuthorKey): JSONObject =
        JSONObject().put("authorKey", hex(author.publicRaw)).put("value", hex(sign(script, binding, id, version, author)))
}
