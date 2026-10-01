package com.arkiv.player.companion

import android.content.Context
import android.util.Log
import com.arkiv.player.data.plugin.EncryptedSecretStore
import com.arkiv.player.data.plugin.SecretStore
import org.json.JSONObject

/** The end-to-end key shared with each paired device ([PairingCrypto]), by that device's id. */
interface PeerKeyStore {
    fun get(deviceId: String): ByteArray?
    fun put(deviceId: String, key: ByteArray)
    fun remove(deviceId: String)
}

/** Production [PeerKeyStore]: the Keystore-backed encrypted preferences, in their own file. */
class KeystorePeerKeyStore(private val store: SecretStore) : PeerKeyStore {
    constructor(context: Context) : this(EncryptedSecretStore(context, FILE))

    override fun get(deviceId: String): ByteArray? =
        store.get(name(deviceId))?.let { runCatching { PairingCrypto.unb64(it) }.getOrNull() }?.takeIf { it.size == PairingCrypto.KEY_BYTES }

    override fun put(deviceId: String, key: ByteArray) = store.put(name(deviceId), PairingCrypto.b64(key))
    override fun remove(deviceId: String) = store.remove(name(deviceId))

    private fun name(deviceId: String) = "peer.$deviceId"

    private companion object {
        const val FILE = "kino_companion_keys"
    }
}

/**
 * The key exchange that rides the companion hello/welcome (see [PairingCrypto] for the cryptography).
 * Fields, all optional so either side may be an older build that knows none of them:
 * - controller `hello`: `kx` (its X25519 public key) when it holds no key for that host -- a new
 *   pairing, or an existing one from before this feature -- else `kxId` (its key's short id);
 * - host `welcome`, answering `kx`: its own public key `kx` and the confirmation `kxc`; answering a
 *   `kxId` it does not hold: `kxReset`, and the controller drops its key and agrees a new one on its
 *   next connection. A host that never saw `kx` (an older one) answers nothing: no key, so no
 *   password ever goes to it.
 * Existing pairings get a key on their next connection, with nothing for the person to do.
 */
class CompanionKeyAgreement(
    private val keys: PeerKeyStore,
    private val log: (String) -> Unit = { runCatching { Log.i("CompanionKx", it) } },
) {
    /** The controller's half in flight, until the host's welcome. */
    class Pending internal constructor(internal val pair: PairingCrypto.KeyPair?)

    /** Controller: adds this side's fields to [hello] for the host [hostId] (null on a first pairing). */
    fun controllerHello(hostId: String?, hello: JSONObject): Pending {
        val key = hostId?.let { runCatching { keys.get(it) }.getOrNull() }
        if (key != null) {
            hello.put(KX_ID, PairingCrypto.keyId(key))
            return Pending(null)
        }
        val pair = PairingCrypto.newKeyPair()
        hello.put(KX, PairingCrypto.b64(pair.publicKey))
        return Pending(pair)
    }

    /**
     * Host, once the controller [controllerId] is accepted: agrees a new key when [hello] asks for one,
     * checks the controller's key id otherwise, and adds the answer to [welcome].
     */
    fun hostAccept(hostId: String, controllerId: String, hello: JSONObject, welcome: JSONObject) {
        val theirs = hello.optString(KX).takeIf { it.isNotEmpty() }
        if (theirs != null) {
            try {
                val controllerPublic = PairingCrypto.unb64(theirs)
                val pair = PairingCrypto.newKeyPair()
                val key = PairingCrypto.derive(pair.privateKey, controllerPublic, controllerId, hostId, controllerPublic, pair.publicKey)
                keys.put(controllerId, key)
                welcome.put(KX, PairingCrypto.b64(pair.publicKey)).put(KX_CONFIRM, PairingCrypto.confirmation(key))
                log("key agreed with a controller")
            } catch (e: Exception) {
                log("key exchange refused: ${e.javaClass.simpleName}")
            }
            return
        }
        val theirId = hello.optString(KX_ID).takeIf { it.isNotEmpty() } ?: return
        val mine = runCatching { keys.get(controllerId) }.getOrNull()
        if (mine == null || PairingCrypto.keyId(mine) != theirId) {
            runCatching { keys.remove(controllerId) }
            welcome.put(KX_RESET, true)
            log("controller key unknown here: asked for a new one")
        }
    }

    /**
     * Controller, on the host [hostId]'s [welcome]. Returns false when the link should be dialed again
     * right away to agree a new key (the host did not know this side's key).
     */
    fun controllerWelcome(controllerId: String, hostId: String, pending: Pending, welcome: JSONObject): Boolean {
        if (welcome.optBoolean(KX_RESET)) {
            runCatching { keys.remove(hostId) }
            log("host lost our key: agreeing a new one")
            return false
        }
        val pair = pending.pair ?: return true
        val theirs = welcome.optString(KX).takeIf { it.isNotEmpty() } ?: return true // an older host: no key
        try {
            val hostPublic = PairingCrypto.unb64(theirs)
            val key = PairingCrypto.derive(pair.privateKey, hostPublic, controllerId, hostId, pair.publicKey, hostPublic)
            if (!PairingCrypto.confirms(key, welcome.optString(KX_CONFIRM))) {
                log("key confirmation failed: not stored")
                return true
            }
            keys.put(hostId, key)
            log("key agreed with the host")
        } catch (e: Exception) {
            log("key exchange refused: ${e.javaClass.simpleName}")
        }
        return true
    }

    fun forget(deviceId: String) {
        runCatching { keys.remove(deviceId) }
    }

    companion object {
        const val KX = "kx"
        const val KX_ID = "kxId"
        const val KX_CONFIRM = "kxc"
        const val KX_RESET = "kxReset"
    }
}
