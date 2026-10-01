package com.arkiv.player.data.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.random.Random

class Ed25519Test {
    private fun hex(s: String) = TestSealing.hex(s)

    // RFC 8032 §7.1, TEST 1..3 (public key, message, signature).
    private val vectors = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test fun `RFC 8032 test vectors verify`() {
        vectors.forEach { (pub, msg, sig) -> assertTrue(pub, Ed25519.verify(hex(pub), hex(msg), hex(sig))) }
    }

    @Test fun `a flipped bit anywhere -- key, message or signature -- fails`() {
        val (pub, msg, sig) = vectors[2]
        for (i in 0 until 64) {
            val s = hex(sig).also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("sig byte $i", Ed25519.verify(hex(pub), hex(msg), s))
        }
        assertFalse(Ed25519.verify(hex(pub).also { it[5] = (it[5].toInt() xor 4).toByte() }, hex(msg), hex(sig)))
        assertFalse(Ed25519.verify(hex(pub), hex("af83"), hex(sig)))
        assertFalse(Ed25519.verify(hex(pub), hex(msg), hex(sig).copyOf(63)))
    }

    @Test fun `S at or above the group order is refused (no malleability)`() {
        val (pub, msg, sig) = vectors[0]
        // S + L: the same point equation holds, but a strict verifier must refuse it.
        val l = java.math.BigInteger.ONE.shiftLeft(252).add(java.math.BigInteger("27742317777372353535851937790883648493"))
        val s = java.math.BigInteger(1, hex(sig).copyOfRange(32, 64).reversedArray()).add(l)
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        assertFalse(Ed25519.verify(hex(pub), hex(msg), hex(sig).copyOfRange(0, 32) + sBytes))
    }

    @Test fun `agrees with the JDK's own Ed25519 on random keys and messages`() {
        val rnd = Random(7)
        repeat(20) { n ->
            val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val raw = kp.public.encoded.copyOfRange(12, 44) // SPKI prefix is 12 bytes
            val msg = rnd.nextBytes(rnd.nextInt(0, 3000))
            val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(msg); sign() }
            assertTrue("case $n", Ed25519.verify(raw, msg, sig))
            if (msg.isNotEmpty()) {
                val bad = msg.copyOf().also { it[rnd.nextInt(it.size)] = (it[0].toInt() + 1).toByte() }
                if (!bad.contentEquals(msg)) assertFalse("case $n tampered", Ed25519.verify(raw, bad, sig))
            }
        }
    }
}
