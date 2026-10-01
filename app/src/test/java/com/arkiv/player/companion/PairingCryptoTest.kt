package com.arkiv.player.companion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MapPeerKeys : PeerKeyStore {
    val map = HashMap<String, ByteArray>()
    override fun get(deviceId: String) = map[deviceId]
    override fun put(deviceId: String, key: ByteArray) { map[deviceId] = key }
    override fun remove(deviceId: String) { map.remove(deviceId) }
}

class PairingCryptoTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun `both sides derive the same key, bound to who they are`() {
        val c = PairingCrypto.newKeyPair()
        val h = PairingCrypto.newKeyPair()
        val onController = PairingCrypto.derive(c.privateKey, h.publicKey, "phone", "tv", c.publicKey, h.publicKey)
        val onHost = PairingCrypto.derive(h.privateKey, c.publicKey, "phone", "tv", c.publicKey, h.publicKey)
        assertArrayEquals(onController, onHost)
        assertEquals(32, onController.size)
        val otherIds = PairingCrypto.derive(c.privateKey, h.publicKey, "phone", "tv2", c.publicKey, h.publicKey)
        assertFalse(onController.contentEquals(otherIds))
    }

    @Test fun `a low-order or malformed public key is refused`() {
        val c = PairingCrypto.newKeyPair()
        assertTrue(runCatching { PairingCrypto.derive(c.privateKey, ByteArray(32), "a", "b", c.publicKey, ByteArray(32)) }.isFailure)
        assertTrue(runCatching { PairingCrypto.derive(c.privateKey, ByteArray(5), "a", "b", c.publicKey, ByteArray(5)) }.isFailure)
    }

    @Test fun `a sealed value opens with the same key and binding, and is never the plaintext`() {
        val sealed = PairingCrypto.seal(key, "s3cr3t", "own|password")
        assertFalse(sealed.contains("s3cr3t"))
        assertEquals("s3cr3t", PairingCrypto.open(key, sealed, "own|password"))
        assertNotEquals(sealed, PairingCrypto.seal(key, "s3cr3t", "own|password")) // fresh nonce
    }

    @Test fun `the binding (plugin and setting) is enforced`() {
        val sealed = PairingCrypto.seal(key, "s3cr3t", "own|password")
        assertNull(PairingCrypto.open(key, sealed, "own|user"))
        assertNull(PairingCrypto.open(key, sealed, "evil|password"))
    }

    @Test fun `a tampered value, a wrong key or garbage never opens`() {
        val sealed = PairingCrypto.seal(key, "s3cr3t", "own|password")
        val ct = sealed.substringAfterLast('.')
        val flipped = ct.replaceFirst(ct[3], if (ct[3] == 'A') 'B' else 'A')
        assertNull(PairingCrypto.open(key, sealed.substringBeforeLast('.') + "." + flipped, "own|password"))
        assertNull(PairingCrypto.open(ByteArray(32) { 7 }, sealed, "own|password"))
        assertNull(PairingCrypto.open(key, "s3cr3t", "own|password"))
        assertNull(PairingCrypto.open(key, "k1.!!.??", "own|password"))
        assertNull(PairingCrypto.open(key, "k1.AAAA.AAAA", "own|password"))
    }

    @Test fun `confirmation proves the key without being it`() {
        val conf = PairingCrypto.confirmation(key)
        assertTrue(PairingCrypto.confirms(key, conf))
        assertFalse(PairingCrypto.confirms(ByteArray(32) { 9 }, conf))
        assertFalse(PairingCrypto.confirms(key, "nope"))
        assertNotEquals(PairingCrypto.b64(key), conf)
    }
}

class CompanionKeyAgreementTest {
    private val phoneKeys = MapPeerKeys()
    private val tvKeys = MapPeerKeys()
    private val phone = CompanionKeyAgreement(phoneKeys) {}
    private val tv = CompanionKeyAgreement(tvKeys) {}

    /** One hello/welcome round; returns whether the controller keeps the link. */
    private fun round(hostIdKnown: Boolean = true, tvSide: CompanionKeyAgreement? = tv): Boolean {
        val hello = JSONObject().put("deviceId", "phone")
        val pending = phone.controllerHello(if (hostIdKnown) "tv" else null, hello)
        val welcome = JSONObject().put("deviceId", "tv")
        tvSide?.hostAccept("tv", "phone", hello, welcome)
        return phone.controllerWelcome("phone", "tv", pending, welcome)
    }

    @Test fun `a first pairing agrees the same key on both sides`() {
        assertTrue(round(hostIdKnown = false))
        assertArrayEquals(tvKeys.map["phone"], phoneKeys.map["tv"])
    }

    @Test fun `an existing pairing without a key gets one on its next connection, then keeps it`() {
        assertTrue(round())
        val first = phoneKeys.map["tv"]!!.copyOf()
        assertTrue(round()) // same key: only its id travels
        assertArrayEquals(first, phoneKeys.map["tv"])
        assertArrayEquals(first, tvKeys.map["phone"])
    }

    @Test fun `a host that lost the key asks for a new one, and the next connection agrees it`() {
        round()
        tvKeys.map.clear()
        assertFalse(round())
        assertNull(phoneKeys.map["tv"])
        assertTrue(round())
        assertArrayEquals(tvKeys.map["phone"], phoneKeys.map["tv"])
    }

    @Test fun `an older host that ignores the exchange leaves no key on the controller`() {
        assertTrue(round(tvSide = null))
        assertNull(phoneKeys.map["tv"])
    }

    @Test fun `a forged confirmation is never stored`() {
        val hello = JSONObject()
        val pending = phone.controllerHello("tv", hello)
        val attacker = PairingCrypto.newKeyPair()
        val welcome = JSONObject().put("kx", PairingCrypto.b64(attacker.publicKey)).put("kxc", PairingCrypto.confirmation(ByteArray(32)))
        phone.controllerWelcome("phone", "tv", pending, welcome)
        assertNull(phoneKeys.map["tv"])
    }

    @Test fun `a hello with a broken public key is ignored by the host`() {
        val welcome = JSONObject()
        tv.hostAccept("tv", "phone", JSONObject().put("kx", "@@@"), welcome)
        assertFalse(welcome.has("kx"))
        assertNull(tvKeys.map["phone"])
    }
}
